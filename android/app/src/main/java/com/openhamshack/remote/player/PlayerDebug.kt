package com.openhamshack.remote.player

import android.util.Log
import java.io.File

/**
 * Debug builds only: libVLC options read from a file on the device, so a
 * player setting can be tried on a real TV without a rebuild, a reinstall
 * and walking the D-pad back to the picture each time. Written for the
 * weak-signal work of October 2026, where some thirty combinations were
 * compared on a Fire TV.
 *
 *   adb push opts.txt /sdcard/Android/data/com.openhamshack.remote/files/vlc-options.txt
 *
 * One option per line, added AFTER the built-in ones (so the file wins):
 * `--name[=value]` for the libVLC instance (the player is rebuilt),
 * `:name=value` for the media, `hw=0|1` overrides the hardware-decoding
 * setting. `#` starts a comment. The file is read again every two seconds
 * and a change reopens the stream; deleting it returns to the built-ins.
 */
internal class DebugOptions(private val file: File) {
    data class Opts(val instance: List<String>, val media: List<String>, val hw: Boolean?)

    private var stamp = -1L
    var current = Opts(emptyList(), emptyList(), null)
        private set

    /** Re-read the file if it changed; true when it did. */
    fun refresh(): Boolean {
        val m = if (file.exists()) file.lastModified() xor file.length() else 0L
        if (m == stamp) return false
        stamp = m
        val lines = if (file.exists()) file.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() } else emptyList()
        current = Opts(
            instance = lines.filter { it.startsWith("--") },
            media = lines.filter { it.startsWith(":") },
            hw = lines.firstOrNull { it.startsWith("hw=") }?.substringAfter('=')?.let { it == "1" || it == "true" },
        )
        Log.i(TAG, "options: $current")
        return true
    }
}

/**
 * Debug builds only: what libVLC says it did with the picture and sound, as
 * one log line every ten seconds (`adb logcat -s RxStats`), so a change to
 * the player can be judged by numbers on a device that refuses screenshots
 * (Fire OS). Counters are deltas over the window; a gap is the longest time
 * the displayed-picture (or played-audio-buffer) count stood still, sampled
 * every 250 ms - a frozen picture is a long video gap.
 */
internal class StatsLog {
    private var base: Sample? = null
    private var windowStart: Sample? = null
    private var lastVideoMove = 0L
    private var lastAudioMove = 0L
    private var maxVideoGap = 0L
    private var maxAudioGap = 0L
    private var prev: Sample? = null

    data class Sample(
        val t: Long, val decoded: Int, val displayed: Int, val lost: Int,
        val aPlayed: Int, val aLost: Int, val corrupted: Int, val discontinuity: Int, val bytes: Int,
    )

    fun reset() {
        base = null; windowStart = null; prev = null
    }

    fun add(s: Sample, label: String) {
        val p = prev
        if (p == null) {
            base = s; windowStart = s; prev = s
            lastVideoMove = s.t; lastAudioMove = s.t
            maxVideoGap = 0; maxAudioGap = 0
            return
        }
        if (s.displayed != p.displayed) lastVideoMove = s.t
        if (s.aPlayed != p.aPlayed) lastAudioMove = s.t
        maxVideoGap = maxOf(maxVideoGap, s.t - lastVideoMove)
        maxAudioGap = maxOf(maxAudioGap, s.t - lastAudioMove)
        prev = s
        val w = windowStart!!
        if (s.t - w.t < 10_000) return
        val b = base!!
        Log.i(
            TAG,
            "$label t=${(s.t - b.t) / 1000}s win=${(s.t - w.t) / 1000}s decoded=+${s.decoded - w.decoded} " +
                "displayed=+${s.displayed - w.displayed} lost=+${s.lost - w.lost} " +
                "audio=+${s.aPlayed - w.aPlayed} audioLost=+${s.aLost - w.aLost} " +
                "corrupt=+${s.corrupted - w.corrupted} discont=+${s.discontinuity - w.discontinuity} " +
                "kB=+${(s.bytes - w.bytes) / 1024} videoGap=${maxVideoGap}ms audioGap=${maxAudioGap}ms",
        )
        windowStart = s
        maxVideoGap = s.t - lastVideoMove
        maxAudioGap = s.t - lastAudioMove
    }
}

internal const val TAG = "RxStats"
