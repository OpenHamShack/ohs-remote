package com.openhamshack.remote.shack

import kotlin.math.abs

/**
 * The bandscope and waterfall a screen draws, built from [SpectrumFrame]s.
 * Pure (no Android types) so the column mapping is tested on the JVM.
 *
 * Every frame is folded to [width] columns by the strongest bin in each:
 * NB's wide frame is ~2000 bins for a screen a few hundred pixels across,
 * and averaging would bury a CW or FT8 trace in its neighbours' noise.
 *
 * The colour range follows the noise: the floor is the frame's 20th
 * percentile, the top 45 dB above it. The engine's levels are relative
 * (no calibration in the simulator, a different one per radio), so a
 * fixed range showed a black or a white waterfall depending on the radio.
 */
class Scope(val width: Int = 480, val height: Int = 150) {
    /** The latest frame's columns, dB. Empty until the first frame. */
    var line: FloatArray = FloatArray(0)
        private set
    var startHz: Double = 0.0
        private set
    var endHz: Double = 0.0
        private set
    var floorDb: Float = -120f
        private set
    /** ARGB, newest row at the top. */
    val pixels: IntArray = IntArray(width * height) { BACKGROUND }
    /**
     * The waterfall's colours, 0 (the floor) to 1 (45 dB over it). [colour]
     * by default; Radio's TV screen sets [polished] (the polish pass Brian
     * approved, 23/10/2026). Rows already drawn keep theirs.
     */
    @Volatile var palette: (Float) -> Int = ::colour
    /** Bumped on each frame, so a UI knows to redraw. */
    var version: Int = 0
        private set

    @Synchronized
    fun add(f: SpectrumFrame) {
        // A different span (wide to zoom, a zoom moved) makes the old rows
        // lie about frequency: start the waterfall again.
        // Not on every small shift: NB's wide frame follows the beacon
        // correction, so its start moves by a few Hz between frames, and
        // clearing on any change left a waterfall one row deep. Less than
        // half a column is not a move anyone could see.
        val col = (endHz - startHz) / width
        val moved = abs(f.startHz - startHz) > col / 2 || abs(f.endHz - endHz) > col / 2
        if (moved && line.isNotEmpty()) pixels.fill(BACKGROUND)
        startHz = f.startHz
        endHz = f.endHz
        line = fold(f.db, width)
        floorDb = percentile(line, 0.2f)
        System.arraycopy(pixels, 0, pixels, width, width * (height - 1))
        val p = palette
        for (x in 0 until width) pixels[x] = p((line[x] - floorDb) / RANGE_DB)
        version++
    }

    /** 0..1 of the screen's width for [hz], or null off the scope. */
    fun xOf(hz: Double): Float? {
        if (endHz <= startHz || hz < startHz || hz > endHz) return null
        return ((hz - startHz) / (endHz - startHz)).toFloat()
    }

    companion object {
        const val RANGE_DB = 45f
        const val BACKGROUND = 0xFF000814.toInt()

        /** [db] into [columns] by the maximum of each column's bins (every bin lands in exactly one). */
        fun fold(db: FloatArray, columns: Int): FloatArray {
            if (db.isEmpty() || columns <= 0) return FloatArray(0)
            val out = FloatArray(columns) { Float.NEGATIVE_INFINITY }
            for (i in db.indices) {
                val c = (i.toLong() * columns / db.size).toInt()
                if (db[i] > out[c]) out[c] = db[i]
            }
            // More columns than bins: a column no bin fell in takes its left neighbour.
            for (c in 1 until columns) if (out[c] == Float.NEGATIVE_INFINITY) out[c] = out[c - 1]
            return out
        }

        fun percentile(v: FloatArray, p: Float): Float {
            if (v.isEmpty()) return -120f
            val s = v.copyOf().also { it.sort() }
            return s[((s.size - 1) * p).toInt()]
        }

        /** The polished palette's stops, floor to top: night navy, deep blue, teal, sea green, amber (TvPolish mockup). */
        private val POLISHED = arrayOf(intArrayOf(6, 10, 18), intArrayOf(16, 58, 92), intArrayOf(32, 140, 170), intArrayOf(120, 200, 190),
            intArrayOf(244, 192, 99))

        /**
         * The TV polish's waterfall: navy at the floor through teal to amber
         * at the top, evenly between its stops. Calmer than [colour]'s red
         * and yellow from a sofa, and the amber still says "strong".
         */
        fun polished(t: Float): Int {
            val x = t.coerceIn(0f, 0.999f) * (POLISHED.size - 1)
            val i = x.toInt()
            val f = x - i
            val a = POLISHED[i]
            val b = POLISHED[i + 1]
            fun ch(k: Int) = Math.round(a[k] + (b[k] - a[k]) * f)
            return (0xFF shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
        }

        /** 0 floor (dark blue) through cyan and yellow to red at 1 and over. */
        fun colour(t: Float): Int {
            val x = t.coerceIn(0f, 1f)
            val (r, g, b) = when {
                x < 0.25f -> Triple(0f, 0f, 0.08f + x / 0.25f * 0.5f)
                x < 0.5f -> ((x - 0.25f) / 0.25f).let { k -> Triple(0f, k, 0.58f + k * 0.42f) }
                x < 0.75f -> ((x - 0.5f) / 0.25f).let { k -> Triple(k, 1f, 1f - k) }
                else -> ((x - 0.75f) / 0.25f).let { k -> Triple(1f, 1f - k, 0f) }
            }
            return (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
        }
    }
}
