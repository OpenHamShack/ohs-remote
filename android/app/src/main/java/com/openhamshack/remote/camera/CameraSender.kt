package com.openhamshack.remote.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaFormat
import android.util.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.thibaultbee.streampack.core.interfaces.setCameraId
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig
import io.github.thibaultbee.streampack.core.streamers.single.cameraSingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.setConfig
import io.github.thibaultbee.streampack.ext.srt.configuration.mediadescriptor.SrtMediaDescriptor
import io.github.thibaultbee.streampack.ui.views.PreviewView

/** Where to send: always the operator's box address, with the Stream source's port and passphrase. */
data class SrtTarget(val host: String, val port: Int, val passphrase: String?, val latencyMs: Int)

data class CameraState(
    val ready: Boolean = false,
    val front: Boolean = false,
    /** The operator asked to send. */
    val sending: Boolean = false,
    /** StreamPack says the SRT connection is up and frames are going. */
    val streaming: Boolean = false,
    val error: String? = null,
    val hasFront: Boolean = false,
    val hasBack: Boolean = false,
)

/**
 * The phone's camera and microphone to the box's Stream source over SRT,
 * as Larix Broadcaster does from the console's QR code: SRT caller,
 * 1280x720, 30 fps, 2.5 Mb/s H.264, AAC. The box re-encodes for the air
 * whatever arrives, so these are the settings a phone's hardware encoder
 * does best, not the transmission's.
 *
 * StreamPack (Apache-2.0, Thibault Beyou) does the capture, encoding, TS
 * muxing and SRT, through srtdroid's libsrt; nothing here touches libsrt
 * directly. A dropped connection - the box restarts its listener each time
 * a sender leaves, Wi-Fi blips - is retried every 2 s while [CameraState.sending].
 */
class CameraSender(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private var streamer: SingleStreamer? = null
    private var target: SrtTarget? = null
    private var retryJob: Job? = null
    private val collectors = mutableListOf<Job>()
    private val _state = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = _state.asStateFlow()

    private val cameras: Pair<String?, String?> by lazy {
        val cm = appContext.getSystemService(CameraManager::class.java)
        var back: String? = null
        var front: String? = null
        for (id in cm.cameraIdList) {
            when (cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> if (back == null) back = id
                CameraCharacteristics.LENS_FACING_FRONT -> if (front == null) front = id
                else -> if (back == null) back = id   // an external camera counts as "back"
            }
        }
        back to front
    }

    /** Create the camera and encoders. The caller has CAMERA and RECORD_AUDIO already. */
    @SuppressLint("MissingPermission")
    private suspend fun ensure(): SingleStreamer = mutex.withLock {
        streamer?.let { return it }
        val (back, front) = cameras
        val id = back ?: front ?: throw IllegalStateException("This device has no camera")
        val s = cameraSingleStreamer(appContext, cameraId = id)
        s.setConfig(
            AudioConfig(mimeType = MediaFormat.MIMETYPE_AUDIO_AAC, startBitrate = 128_000, sampleRate = 44_100),
            VideoConfig(
                mimeType = MediaFormat.MIMETYPE_VIDEO_AVC,
                startBitrate = 2_500_000,
                resolution = Size(1280, 720),
                fps = 30,
                gopDurationInS = 1f,
            ),
        )
        collectors += scope.launch {
            s.throwableFlow.filterNotNull().collect { t -> onStreamError(t) }
        }
        collectors += scope.launch {
            s.isStreamingFlow.collect { on -> _state.value = _state.value.copy(streaming = on) }
        }
        streamer = s
        _state.value = _state.value.copy(ready = true, front = back == null, hasBack = back != null,
            hasFront = front != null, error = null)
        s
    }

    suspend fun attachPreview(view: PreviewView) {
        try {
            view.setVideoSourceProvider(ensure())
        } catch (t: Throwable) {
            _state.value = _state.value.copy(error = "Camera: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** Fire and forget: called as the view leaves the screen, when the caller's scope is going too. */
    fun detachPreview(view: PreviewView) {
        CoroutineScope(Dispatchers.Main.immediate).launch { runCatching { view.setVideoSourceProvider(null) } }
    }

    @SuppressLint("MissingPermission")
    suspend fun switchCamera() {
        val s = streamer ?: return
        val (back, front) = cameras
        val toFront = !_state.value.front
        val id = (if (toFront) front else back) ?: return
        try {
            s.setCameraId(id)
            _state.value = _state.value.copy(front = toFront)
        } catch (t: Throwable) {
            _state.value = _state.value.copy(error = "Could not switch camera: ${t.message}")
        }
    }

    /** Start sending to [to]; keeps retrying until [stopSending]. */
    suspend fun startSending(to: SrtTarget) {
        target = to
        _state.value = _state.value.copy(sending = true, error = null)
        connect()
    }

    suspend fun stopSending() {
        target = null
        retryJob?.cancel()
        _state.value = _state.value.copy(sending = false, error = null)
        runCatching { streamer?.stopStream() }
        runCatching { streamer?.close() }
    }

    /**
     * Stop sending and give the camera back (the app went to the background).
     * Usable again afterwards: the next preview creates the camera afresh.
     */
    fun release() {
        target = null
        retryJob?.cancel()
        val s = streamer
        streamer = null
        _state.value = CameraState()
        // Not on [scope]: the collectors of the old streamer live there and
        // are cancelled with it below.
        CoroutineScope(Dispatchers.Main.immediate).launch {
            runCatching { s?.stopStream() }
            runCatching { s?.close() }
            runCatching { s?.release() }
        }
        collectors.forEach { it.cancel() }
        collectors.clear()
    }

    /** [release], and never used again (the box screen was closed). */
    fun destroy() {
        release()
        scope.cancel()
    }

    private suspend fun connect() {
        val t = target ?: return
        try {
            val s = ensure()
            val descriptor = SrtMediaDescriptor(
                host = t.host,
                port = t.port,
                passPhrase = t.passphrase?.ifBlank { null },
                latency = t.latencyMs,
                connectionTimeout = 3000,
            )
            s.startStream(descriptor)
        } catch (e: Throwable) {
            onStreamError(e)
        }
    }

    private fun onStreamError(t: Throwable) {
        val msg = t.message ?: t.javaClass.simpleName
        _state.value = _state.value.copy(error = "Sending: $msg")
        if (target == null) return
        retryJob?.cancel()
        retryJob = scope.launch {
            runCatching { streamer?.stopStream() }
            runCatching { streamer?.close() }
            delay(2000)
            if (target != null) connect()
        }
    }
}
