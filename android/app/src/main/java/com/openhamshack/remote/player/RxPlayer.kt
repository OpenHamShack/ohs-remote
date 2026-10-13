package com.openhamshack.remote.player

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import com.openhamshack.remote.data.PlayerSettings
import java.io.File

enum class PlayPhase { Stopped, Connecting, Playing, Reconnecting }

data class PlayerState(
    val phase: PlayPhase = PlayPhase.Stopped,
    /** Times the stream was opened afresh since play() - the overlay shows it, so a bad link is visible. */
    val reconnects: Int = 0,
    val lastProblem: String? = null,
    /** The stream playing now has a video track with a size. */
    val hasVideo: Boolean = false,
    /** The picture's sound is off (Play/Pause on a TV, REMOTE-KEYS.md): the stream plays on. */
    val muted: Boolean = false,
)

/**
 * The receive picture: GET /api/rx/stream.ts in libVLC, tuned for an
 * over in progress rather than for a film.
 *
 * Latency: network-caching is the operator's setting (250 ms default); the
 * TS demuxer is named so nothing is spent probing; clock jitter and synchro
 * are off so a late frame is shown late rather than the clock waiting for
 * it.
 *
 * Marginal signals: a stream from a tuner near its threshold has lost
 * packets, continuity errors, PCR jumps and half-decodable frames. The
 * picture must keep moving, blocky or smeared, as mpv's does on the box
 * itself: a blocky picture tells the operator far more than a frozen one.
 * So libVLC is told not to look for lost packets at all (see newLibVlc for
 * why that, and not the obvious options, is what keeps it moving), to
 * decode MP2 with libavcodec rather than mpg123, to show corrupted frames,
 * and to drop late frames rather than fall behind. What it cannot ride out - the connection closing (the box drops
 * a client that falls 2 MB behind, Wi-Fi dropped, the box restarted), or a
 * connection that stays open with no bytes at all - the watchdog below
 * handles by opening the stream again,
 * after 1, 2, then every 3 s, for as long as the picture is wanted.
 */
class RxPlayer(context: Context) {
    private val appContext = context.applicationContext
    /** Debug builds: options from a file on the device, and a stats line in the log (PlayerDebug.kt). */
    private val debug = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val debugOptions = if (debug) appContext.getExternalFilesDir(null)?.let { DebugOptions(File(it, "vlc-options.txt")) } else null
    private val statsLog = StatsLog()

    private var libVlc = newLibVlc()
    private var mp = newPlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var url: String? = null
    private var settings = PlayerSettings()
    private var watchdog: Job? = null
    private var vlcEvent = MediaPlayer.Event.Stopped
    private var playingSince = 0L
    private var attempt = 0
    private var attached: VLCVideoLayout? = null
    private var openedAt = 0L

    private fun newLibVlc(): LibVLC {
        debugOptions?.refresh()
        return LibVLC(
            appContext,
            arrayListOf(
                // Do not check TS continuity counters. This is the weak-signal
                // fix (October 2026, measured on a Fire TV against fakebox
                // dropping 0.5-2 % of TS packets): with the check on, every
                // lost packet marks the next PES "discontinuity", and VLC 3's
                // H.264 and HEVC packetizers answer that by throwing away the
                // frame in progress and everything after it until the next
                // clean I-frame - at 2 % loss almost no GOP is clean, so the
                // picture froze for 5-35 s at a time, with hardware or
                // software decoding alike. Unchecked, the damaged frames go
                // to the decoder, which conceals the holes the way mpv does.
                // Tried first and no help, because the clock was never the
                // cause: clock-jitter 1000/5000, clock-synchro default,
                // ts-trust-pcr off, drop-late-frames/skip-frames/hurry-up off.
                // An instance option because the media option
                // (:ts-cc-check=0) is not honoured by Android's libVLC 3.7.
                "--no-ts-cc-check",
                "--avcodec-corrupted",      // show damaged frames instead of freezing
                "--drop-late-frames",
                "--skip-frames",
                // Stats stay on (the default): the watchdog reads readBytes.
                "--verbose=0",
                // ...but libavcodec's own messages off. Now that damaged
                // frames reach the decoder it reports every broken
                // macroblock: hundreds of lines a second at 2-5 % loss, which
                // costs a weak TV CPU in logd and overran its 256 KB log
                // within seconds (Android then drops lines, ours included).
                // libVLC's own errors still reach logcat.
                "--quiet",
            ).apply { debugOptions?.let { addAll(it.current.instance) } },
        )
    }

    private fun newPlayer() = MediaPlayer(libVlc).apply {
        setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> {
                    vlcEvent = e.type
                    playingSince = now()
                    // Each opening starts at full volume: a muted picture must stay muted across a reconnect.
                    if (_state.value.muted) volume = 0
                    if (url != null) _state.value = _state.value.copy(phase = PlayPhase.Playing, lastProblem = null)
                }
                // Not Stopped: that only ever follows our own stop(), and it is
                // delivered after the reopen has begun - taken as "ended", it
                // made a stream with no signal (no Playing event to mask it)
                // reopen every few seconds for ever.
                MediaPlayer.Event.EndReached, MediaPlayer.Event.EncounteredError -> {
                    vlcEvent = e.type
                }
            }
        }
    }

    /**
     * Mute or unmute the picture's sound. Only the volume: the stream, the
     * decoder and the reconnect watchdog carry on, so unmuting is instant
     * and a muted TV still shows when the signal goes.
     */
    fun mute(on: Boolean) {
        _state.value = _state.value.copy(muted = on)
        mp.volume = if (on) 0 else 100
    }

    /** Debug builds: the options file changed - a new libVLC with them, and the stream opened again. */
    private fun rebuildForDebug() {
        val layout = attached
        mp.stop()
        if (layout != null) mp.detachViews()
        mp.setEventListener(null)
        mp.release()
        libVlc.release()
        libVlc = newLibVlc()
        mp = newPlayer()
        attached = null
        layout?.let { attach(it) }
        if (url != null) open()
    }

    fun attach(layout: VLCVideoLayout) {
        if (attached === layout) return
        if (attached != null) mp.detachViews()
        // SurfaceView, not TextureView: cheaper, and a picture that is only
        // ever shown full-bleed has no use for TextureView's transforms.
        mp.attachViews(layout, null, false, false)
        attached = layout
    }

    fun detach(layout: VLCVideoLayout) {
        if (attached !== layout) return
        mp.detachViews()
        attached = null
    }

    /** Play [streamUrl] and keep it playing until [stop]. Calling again with new settings reopens. */
    fun play(streamUrl: String, playerSettings: PlayerSettings) {
        val changed = streamUrl != url || playerSettings != settings
        url = streamUrl
        settings = playerSettings
        if (!changed && watchdog?.isActive == true) return
        attempt = 0
        _state.value = PlayerState(PlayPhase.Connecting)
        open()
        watchdog?.cancel()
        watchdog = scope.launch { watch() }
    }

    /**
     * Open the stream afresh now: the box was unreachable and is back, so
     * the TCP connection under the player may be dead without knowing it.
     */
    fun reconnect() {
        if (url == null) return
        mp.stop()
        _state.value = _state.value.copy(reconnects = _state.value.reconnects + 1)
        open()
    }

    fun stop() {
        url = null
        watchdog?.cancel()
        watchdog = null
        mp.stop()
        _state.value = PlayerState(PlayPhase.Stopped)
    }

    fun release() {
        stop()
        attached?.let { mp.detachViews() }
        attached = null
        mp.setEventListener(null)
        mp.release()
        libVlc.release()
        scope.cancel()
    }

    private fun open() {
        val u = url ?: return
        val dbg = debugOptions?.current
        val media = Media(libVlc, Uri.parse(u)).apply {
            val hw = dbg?.hw ?: settings.hwDecode
            setHWDecoderEnabled(hw, false)
            // Audio through libavcodec, not mpg123 (VLC's first choice for
            // MP2): with continuity unchecked, mpg123 fed a damaged MP2
            // stream fell steadily behind the clock - over a second in 20 s -
            // and libVLC dropped every buffer as late, 10-38 s of silence at
            // 2 % loss. libavcodec resyncs at the next frame and the sound
            // clicks instead. A later :codec replaces setHWDecoderEnabled's;
            // MediaCodec still takes the video (it declines audio unless
            // mediacodec-audio is set).
            addOption(if (hw) ":codec=mediacodec_ndk,mediacodec_jni,avcodec,all" else ":codec=avcodec,all")
            addOption(":network-caching=${settings.cachingMs}")
            addOption(":demux=ts")
            addOption(":clock-jitter=0")
            addOption(":clock-synchro=0")
            dbg?.media?.forEach { addOption(it) }
        }
        statsLog.reset()
        openedAt = now()
        mp.media = media
        media.release()
        vlcEvent = MediaPlayer.Event.Opening
        mp.play()
    }

    /**
     * Reopen the stream only when the connection is gone. "No picture" is
     * not a reason: with nothing received the box keeps the connection open
     * and sends one TS null packet a second (docs/REMOTE-CONTRACT.md), and
     * the picture appears by itself when a signal locks. So the test is
     * bytes: libVLC's readBytes still moving means the box is still there.
     */
    private suspend fun watch() {
        var deadSince = 0L
        var lastBytes = -1L
        var lastBytesAt = now()
        var tick = 0
        while (scope.isActive && url != null) {
            delay(if (debug) 250 else 500)
            if (debug) {
                debugSample()
                if (++tick % 8 == 0 && debugOptions?.refresh() == true) {
                    rebuildForDebug()
                    lastBytes = -1
                    lastBytesAt = now()
                    continue
                }
                if (tick % 2 == 1) continue
            }
            val t = now()
            val bytes = readBytes()
            if (bytes != lastBytes) {
                lastBytes = bytes
                lastBytesAt = t
            }
            // "Picture" is a video elementary stream selected in the media
            // playing now (its ES id; -1 for none). Not the Vout event:
            // libVLC keeps its output across a reopen and does not announce
            // it again. Not the picture counters in the media stats, nor
            // currentVideoTrack's size: on Android's libVLC 3.7 both stay 0
            // for a network stream (seen on the emulator).
            val video = mp.videoTrack >= 0
            if (video != _state.value.hasVideo) _state.value = _state.value.copy(hasVideo = video)
            // Silence counts only while a picture is coming: libVLC's HTTP
            // access reads in large blocks, so the box's no-signal trickle
            // (188 bytes a second) moves readBytes only every minute or two.
            // Without a picture, a connection that died silently is caught by
            // the status poll instead (reconnect(), from BoxSession).
            val silentLimit = 15_000L + settings.cachingMs
            val problem = when (vlcEvent) {
                MediaPlayer.Event.EncounteredError -> "could not connect to the stream"
                MediaPlayer.Event.EndReached -> "the box closed the stream"
                else -> if (_state.value.hasVideo && t - lastBytesAt > silentLimit)
                    "nothing from the box for ${silentLimit / 1000} s" else null
            }
            if (problem == null) {
                deadSince = 0
                if (_state.value.phase == PlayPhase.Reconnecting && vlcEvent == MediaPlayer.Event.Playing) {
                    _state.value = _state.value.copy(phase = PlayPhase.Playing, lastProblem = null)
                }
                // Ten seconds of playing: the next failure starts the backoff afresh.
                if (vlcEvent == MediaPlayer.Event.Playing && t - playingSince > 10_000) attempt = 0
                continue
            }
            if (deadSince == 0L) {
                deadSince = t
                _state.value = _state.value.copy(phase = PlayPhase.Reconnecting, lastProblem = problem)
            }
            val wait = when (attempt) { 0 -> 1000L; 1 -> 2000L; else -> 3000L }
            if (t - deadSince >= wait) {
                attempt++
                deadSince = 0
                lastBytes = -1
                lastBytesAt = t
                mp.stop()
                _state.value = _state.value.copy(reconnects = _state.value.reconnects + 1)
                open()
            }
        }
    }

    private fun readBytes(): Long {
        val m = mp.media ?: return -1
        return try {
            m.stats?.readBytes?.toLong() ?: -1
        } finally {
            m.release()
        }
    }

    private fun debugSample() {
        val m = mp.media ?: return
        try {
            val st = m.stats ?: return
            statsLog.add(
                StatsLog.Sample(
                    now(), st.decodedVideo, st.displayedPictures, st.lostPictures, st.playedAbuffers,
                    st.lostAbuffers, st.demuxCorrupted, st.demuxDiscontinuity, st.readBytes,
                ),
                // lag: wall time since the stream was opened minus the
                // stream time played - compared between two option sets it
                // shows whether one plays further behind live than the other.
                (if (debugOptions?.current?.hw ?: settings.hwDecode) "hw" else "sw") +
                    " lag=${now() - openedAt - mp.time}ms",
            )
        } finally {
            m.release()
        }
    }

    private fun now() = SystemClock.elapsedRealtime()
}
