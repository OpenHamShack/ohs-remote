package com.openhamshack.kind.stationflow

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.openhamshack.kind.stationflow.api.Spectrum

/**
 * The band scope's history: the newest sweep as a trace, and the last
 * [rows] sweeps as colours, newest first. Filled from the socket thread,
 * read by the screen at draw time; [version] ticks once per sweep so the
 * screen redraws exactly when there is something new.
 *
 * The history keeps each point's level (0..255), not its colour, so a
 * colour map, contrast or brightness chosen on the screen (core ScopeLook)
 * recolours the whole history at once rather than only rows yet to come.
 * Colouring is one read of a 256-entry table per point, done when the
 * screen copies the history out for a new sweep (8.5 a second from a 9700),
 * not per frame drawn.
 */
class Waterfall(val rows: Int = 160) {
    @Volatile var latest: Spectrum? = null
        private set
    @Volatile var width = 0
        private set
    private var ring = IntArray(0)
    private var head = 0       // the row the newest sweep went into
    private var filled = 0
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    @Synchronized
    fun add(s: Spectrum) {
        val prev = latest
        val w = s.data.size
        // A new width, a different span (another scope span chosen at the
        // radio) or a jump further than the span (a band change): the history
        // no longer lines up with the trace. Start again rather than draw
        // signals at frequencies they were never on - seen on the emulator,
        // 2 m signals left in the waterfall after a jump to 70 cm.
        val moved = if (prev == null) 0L else s.startHz - prev.startHz
        if (w != width || prev == null || prev.spanHz != s.spanHz || kotlin.math.abs(moved) >= s.spanHz) {
            width = w
            ring = IntArray(w * rows)
            head = 0
            filled = 0
        } else if (moved != 0L) {
            // Tuned within the span, in centre mode: the edges moved, so move
            // the history with them and every signal stays on its own
            // frequency, as the radio's own waterfall does.
            shift(Math.round(moved.toDouble() / s.spanHz * (w - 1)).toInt())
        }
        head = (head - 1 + rows) % rows
        val off = head * w
        for (i in 0 until w) ring[off + i] = (s.data[i].toFloat() / s.max * 255f).toInt().coerceIn(0, 255)
        filled = minOf(filled + 1, rows)
        latest = s
        _version.value = _version.value + 1
    }

    /** Move every row [px] points left (right if negative), filling with the floor colour. */
    private fun shift(px: Int) {
        if (px == 0 || filled == 0) return
        val w = width
        val floor = 0
        val row = IntArray(w)
        for (r in 0 until rows) {
            val off = r * w
            java.util.Arrays.fill(row, floor)
            for (i in 0 until w) {
                val src = i + px
                if (src in 0 until w) row[i] = ring[off + src]
            }
            System.arraycopy(row, 0, ring, off, w)
        }
    }

    /**
     * The history newest-first into [out] (width x rows), coloured by
     * [table] (256 colours, level 0 first: ScopeLook.table). Returns how many
     * rows are real.
     */
    @Synchronized
    fun copyInto(out: IntArray, table: IntArray = DEFAULT_TABLE): Int {
        val w = width
        if (w == 0 || out.size < w * rows) return 0
        for (r in 0 until filled) {
            val from = ((head + r) % rows) * w
            val to = r * w
            for (i in 0 until w) out[to + i] = table[ring[from + i]]
        }
        java.util.Arrays.fill(out, filled * w, rows * w, 0xFF000000.toInt())
        return filled
    }

    @Synchronized
    fun clear() {
        latest = null; width = 0; ring = IntArray(0); head = 0; filled = 0
        _version.value = _version.value + 1
    }

    companion object {
        /** [colour] at each of 256 levels: the radio's own map, as the waterfall was before looks existed. */
        val DEFAULT_TABLE: IntArray = IntArray(256) { colour(it / 255f) }

        /**
         * Level 0..1 to colour, in the order Icom's own waterfall uses -
         * "Red, Yellow, Green, Light-blue, Blue, and Black", strongest first
         * (IC-9700 Advanced manual p. 2-4; docs/RADIO-DISPLAYS.md) - so the
         * noise floor is the radio's deep blue and the strongest signal red,
         * and a waterfall on the TV reads like the one on the rig.
         */
        fun colour(v: Float): Int {
            val x = v.coerceIn(0f, 1f)
            val stops = floatArrayOf(0f, 0.12f, 0.35f, 0.55f, 0.75f, 1f)
            val cols = intArrayOf(0x000010, 0x0A1E9A, 0x10A8F0, 0x30E040, 0xF0F020, 0xFF2010)
            var i = 0
            while (i < stops.size - 2 && x > stops[i + 1]) i++
            val t = ((x - stops[i]) / (stops[i + 1] - stops[i])).coerceIn(0f, 1f)
            val a = cols[i]; val b = cols[i + 1]
            fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * t).toInt()
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
