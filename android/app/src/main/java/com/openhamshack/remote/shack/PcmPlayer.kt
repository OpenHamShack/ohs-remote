package com.openhamshack.remote.shack

// Copied with JitterBuffer.kt from ~/stationflow-tv's :core-candidates
// (same author, MIT there) for the NB and Radio kinds' receive audio. One
// copy is to remain: when openhamshack-remote splits into :core and kinds
// (HANDOVER "Split openhamshack-remote Android"), these move to :core and the
// StationFlow kind takes them from there.

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the receive audio is doing, for a line on the screen. */
data class PcmStats(
    val rate: Int = 0,
    /** 1 or 2: what the track plays, as the stream announced it. */
    val channels: Int = 0,
    val playing: Boolean = false,
    /** Audio waiting to be played, ms: the latency this end adds. */
    val bufferedMs: Int = 0,
    /** RMS level of the last block, 0..1, over both channels of a stereo stream. */
    val level: Float = 0f,
    val underruns: Int = 0,
    val droppedMs: Long = 0,
)

/**
 * Signed 16-bit PCM from the network, mono or interleaved stereo, to the
 * speaker, now.
 *
 * An AudioTrack in streaming mode with the smallest buffer the device allows
 * (and low-latency performance mode, which a TV may or may not honour), fed
 * from a [JitterBuffer] by one thread at audio priority. Total latency is the
 * jitter buffer's ~150 ms cushion plus the track's own buffer - well under a
 * quarter of a second on a LAN, so the S-meter and the sound agree.
 *
 * Volume is the TV's: the track plays as media, so the remote's volume keys
 * (and a soundbar's) work as they do for any app. [muted] silences the
 * track without stopping it, so unmuting is instant and the stream is not
 * reconnected for it.
 *
 * Stereo (Radio's wide FM from 23/10/2026) is played as stereo, on a
 * CHANNEL_OUT_STEREO track; everything sized here is sized in frames, so a
 * stereo stream gets the same milliseconds of cushion and track buffer as a
 * mono one, not half of them. [tap] still hands its consumers mono: they
 * are probes and meters that measure one signal, and a downmix is what a
 * mono FM receiver would hear.
 *
 * Underruns are filled with silence by writing nothing; the jitter buffer
 * then waits for its cushion again rather than playing each packet the
 * moment it arrives (which would crackle for as long as the network is bad).
 */
class PcmPlayer(
    // TV-API.md section 7's advice: start after ~150 ms, catch up past ~500.
    private val startMs: Int = 150,
    private val maxMs: Int = 500,
) {
    private val _stats = MutableStateFlow(PcmStats())
    val stats: StateFlow<PcmStats> = _stats.asStateFlow()

    @Volatile var muted: Boolean = false
        set(v) { field = v; track?.setVolume(if (v) 0f else 1f) }

    /**
     * Called with each block written, for probes and tests; on the audio
     * thread. Always mono (samples, count, rate): a stereo block is
     * averaged down first, and only when a tap is set.
     */
    @Volatile var tap: ((ShortArray, Int, Int) -> Unit)? = null

    @Volatile private var track: AudioTrack? = null
    @Volatile private var buffer: JitterBuffer? = null
    @Volatile private var rate = 0
    @Volatile private var channels = 0
    private var thread: Thread? = null
    @Volatile private var running = false
    private var underruns = 0

    /**
     * Prepare for [sampleRate] and [channelCount] (1 or 2); a different
     * rate or channel count rebuilds the track. Idempotent.
     */
    @Synchronized
    fun start(sampleRate: Int, channelCount: Int = 1) {
        require(channelCount in 1..2)
        if (running && sampleRate == rate && channelCount == channels) return
        stop()
        rate = sampleRate
        channels = channelCount
        val buf = JitterBuffer(sampleRate * startMs / 1000, sampleRate * maxMs / 1000, channelCount)
        buffer = buf
        val mask = if (channelCount == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(mask)
                .build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, trackBytes(sampleRate, channelCount, 40)))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        t.setVolume(if (muted) 0f else 1f)
        t.play()
        track = t
        running = true
        underruns = 0
        thread = Thread({ pump(t, buf, sampleRate, channelCount) }, "pcm-player").apply { start() }
        _stats.value = PcmStats(rate = sampleRate, channels = channelCount)
    }

    /** Samples from the network, interleaved and whole frames for the channels [start] was given. Any thread. */
    fun write(samples: ShortArray) {
        buffer?.put(samples)
    }

    /** Drop what is queued (a retune, a reconnect): stale audio is worse than a gap. */
    fun flush() {
        buffer?.clear()
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(500)
        thread = null
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        track = null
        buffer = null
        channels = 0
        _stats.value = _stats.value.copy(playing = false, bufferedMs = 0, level = 0f)
    }

    private fun pump(t: AudioTrack, buf: JitterBuffer, sampleRate: Int, ch: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val block = ShortArray(blockFrames(sampleRate) * ch)
        val mono = if (ch == 1) block else ShortArray(blockFrames(sampleRate))
        var lastStats = 0L
        var wasStarted = false
        while (running) {
            val n = buf.take(block)
            if (n == 0) {
                if (wasStarted) { underruns++; wasStarted = false }
                try { Thread.sleep(5) } catch (_: InterruptedException) { break }
            } else {
                wasStarted = true
                tap?.let { f -> f(if (ch == 1) block else downmix(block, n, ch, mono), n, sampleRate) }
                // Blocking write: paces this loop at the track's own rate. n is frames; the track counts shorts.
                val w = t.write(block, 0, n * ch)
                if (w < 0) { Log.w(TAG, "AudioTrack.write: $w"); break }
            }
            val now = System.currentTimeMillis()
            if (now - lastStats > 200) {
                lastStats = now
                _stats.value = PcmStats(
                    rate = sampleRate, channels = ch, playing = buf.started,
                    bufferedMs = buf.queued * 1000 / sampleRate,
                    level = if (n > 0) rms(block, n * ch).toFloat() else 0f,
                    underruns = underruns, droppedMs = buf.dropped * 1000 / sampleRate,
                )
            }
        }
    }

    companion object {
        private const val TAG = "PcmPlayer"

        /** The pump's block: 20 ms of frames, whatever the channel count. */
        fun blockFrames(sampleRate: Int): Int = sampleRate / 50

        /** Bytes of 16-bit PCM in [ms] of audio: frames times two bytes times the channels. */
        fun trackBytes(sampleRate: Int, channels: Int, ms: Int): Int = sampleRate * ms / 1000 * 2 * channels

        /**
         * The first [frames] interleaved frames of [s], averaged to one
         * channel, into [out] (returned). Summed as Int, so full-scale
         * L and R do not wrap.
         */
        fun downmix(s: ShortArray, frames: Int, channels: Int, out: ShortArray = ShortArray(frames)): ShortArray {
            for (i in 0 until frames) {
                var sum = 0
                for (c in 0 until channels) sum += s[i * channels + c]
                out[i] = (sum / channels).toShort()
            }
            return out
        }

        fun rms(s: ShortArray, n: Int): Double {
            if (n == 0) return 0.0
            var sum = 0.0
            for (i in 0 until n) { val v = s[i] / 32768.0; sum += v * v }
            return kotlin.math.sqrt(sum / n)
        }
    }
}
