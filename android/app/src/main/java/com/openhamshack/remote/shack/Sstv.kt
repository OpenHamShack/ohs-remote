package com.openhamshack.remote.shack

import org.json.JSONObject
import java.util.Base64

// SSTV pictures as the NB and Radio engines send them on api/events
// (ohscore/modes/sstv.py): {"state": "start", name, fmt, width, height}, then
// one {"state": "line", name, line, of, row} per line - row is base64 of
// width x 3 RGB bytes, already RGB in every mode (Robot and PD are turned
// from Y/R-Y/B-Y by the engine's _yryby_to_rgb before they are sent) - and
// {"state": "done", name, path}. The path is on the server and there is no
// route to fetch a finished picture, so the device keeps what it painted.

sealed interface SstvEvent {
    val name: String

    data class Start(override val name: String, val fmt: String, val width: Int, val height: Int) : SstvEvent
    /** [line] counts from 1, as the engine numbers it. */
    data class Line(override val name: String, val line: Int, val of: Int, val rgb: ByteArray) : SstvEvent
    /** [aborted]: Flex's module switched SSTV off mid-picture and kept nothing (its API.md 7). */
    data class Done(override val name: String, val aborted: Boolean = false) : SstvEvent

    companion object {
        /** Null for anything this cannot paint: an unknown state, a size out of reason, a row that is not base64. */
        fun parse(o: JSONObject): SstvEvent? {
            val name = o.optString("name")
            return when (o.optString("state")) {
                "start" -> {
                    val w = o.optInt("width"); val h = o.optInt("height")
                    if (w !in 1..2048 || h !in 1..2048) null else Start(name, o.optString("fmt"), w, h)
                }
                "line" -> runCatching { Base64.getDecoder().decode(o.optString("row")) }.getOrNull()
                    ?.let { Line(name, o.optInt("line"), o.optInt("of"), it) }
                "done" -> Done(name, o.optBoolean("aborted"))
                else -> null
            }
        }
    }
}

/**
 * One picture as it arrives: [pixels] are ARGB, the whole picture from the
 * start at the size the start event gave, [UNPAINTED] until a line comes,
 * so the frame is reserved at its true shape and fills top to bottom.
 * Mutable for speed (a PD120 picture is 640 x 496), so [lines] and [done]
 * are what a screen watches; it is owned by one session.
 */
class SstvPicture(val name: String, val fmt: String, val width: Int, val height: Int) {
    val pixels = IntArray(width * height) { UNPAINTED }
    var lines: Int = 0
        private set
    var done: Boolean = false
        private set

    /** Paint one line; false (and nothing painted) for a row of the wrong length or a line outside the picture. */
    fun paint(l: SstvEvent.Line): Boolean {
        val y = l.line - 1
        if (y !in 0 until height || l.rgb.size != width * 3) return false
        for (x in 0 until width) {
            val i = x * 3
            pixels[y * width + x] = (0xFF shl 24) or ((l.rgb[i].toInt() and 0xFF) shl 16) or
                ((l.rgb[i + 1].toInt() and 0xFF) shl 8) or (l.rgb[i + 2].toInt() and 0xFF)
        }
        lines = maxOf(lines, l.line)
        return true
    }

    fun finish() {
        done = true
    }

    /** Width over height: 5:4 for Martin and Scottie's 320 x 256. */
    val aspect: Float get() = width.toFloat() / height

    /**
     * Where the picture goes in a [boxW] x [boxH] space: the largest
     * rectangle of its own shape that fits, centred, as left, top, width,
     * height. Drawn this way, whatever the layout gives the canvas: on the
     * Fire TV (09/10/2026) a Scottie 1 picture at line 61 showed as a
     * strip about 4.7:1 that grew downward, stretched to the panel's width,
     * because the shape was left to Compose's aspectRatio modifier, which a
     * fixed height beside a fixed width defeats (GUIDE 5).
     */
    fun fit(boxW: Float, boxH: Float): FloatArray {
        if (boxW <= 0f || boxH <= 0f) return floatArrayOf(0f, 0f, 0f, 0f)
        val w = minOf(boxW, boxH * aspect)
        val h = w / aspect
        return floatArrayOf((boxW - w) / 2, (boxH - h) / 2, w, h)
    }

    companion object {
        /** A row not yet received: dark grey, so the frame it will fill can be seen against black. */
        const val UNPAINTED = 0xFF1C1C1C.toInt()
    }
}

/**
 * This session's SSTV: the picture coming in now, and the last [keep]
 * finished ones, newest first. A line for a picture that never started
 * here (the app opened mid-picture) is dropped: half a picture with no
 * name or format would only mislead. [version] changes on every paint.
 */
class SstvReceiver(private val keep: Int = 5) {
    var current: SstvPicture? = null
        private set
    var gallery: List<SstvPicture> = emptyList()
        private set
    var version: Int = 0
        private set

    /** Returns whether a new picture started (a TV opens its panel on that). */
    fun apply(e: SstvEvent): Boolean {
        when (e) {
            is SstvEvent.Start -> {
                current = SstvPicture(e.name, e.fmt, e.width, e.height)
                version++
                return true
            }
            is SstvEvent.Line -> {
                val c = current?.takeIf { it.name == e.name } ?: return false
                if (c.paint(e)) version++
            }
            is SstvEvent.Done -> {
                val c = current?.takeIf { it.name == e.name } ?: return false
                if (e.aborted) {
                    // Nothing was kept at the other end; half a picture here would only mislead.
                    current = null
                    version++
                    return false
                }
                c.finish()
                gallery = (listOf(c) + gallery).take(keep)
                version++
            }
        }
        return false
    }
}
