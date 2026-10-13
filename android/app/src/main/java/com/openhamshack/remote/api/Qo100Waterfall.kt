package com.openhamshack.remote.api

/**
 * The waterfall under the spectrum, built up from ?wf=<seq> answers: the
 * box keeps the last three minutes (one row a second) and sends only the
 * rows after the seq asked for, so a page opened late shows the last three
 * minutes at once and each poll after that carries one new row.
 *
 * Pixels, not a picture: [pixels] is ARGB, newest row at the top, [width] x
 * [height], for the UI to wrap in a Bitmap. Colours are the console's
 * (qo100.js WF_LUT): floor dark, through blue and cyan to yellow at 10 dB
 * and red at 15 dB and over, so the beacon and a strong station stand out
 * and the noise does not.
 */
class Qo100Waterfall {
    /** The seq to ask for next: the newest row held. */
    var seq: Int = 0
        private set
    var width: Int = 0
        private set
    var height: Int = 0
        private set
    var pixels: IntArray = IntArray(0)
        private set
    /** Bumped on every change, so a UI can tell a new picture from the same one. */
    var version: Int = 0
        private set

    fun add(wf: Qo100WaterfallRows) {
        val rows = wf.rows
        val w = rows.firstOrNull()?.size ?: width
        if (wf.full || w != width || wf.maxRows != height) {
            width = w
            height = wf.maxRows
            pixels = IntArray(width * height) { BACKGROUND }
        }
        seq = wf.seq
        if (rows.isEmpty() || width == 0) {
            version++
            return
        }
        // Shift what is there down by the number of new rows, then draw the
        // new ones at the top, the last sent (newest) first.
        val n = minOf(rows.size, height)
        System.arraycopy(pixels, 0, pixels, n * width, (height - n) * width)
        val newest = rows.takeLast(n)
        for (i in 0 until n) {
            val row = newest[n - 1 - i]
            val o = i * width
            for (x in 0 until width) pixels[o + x] = if (x < row.size) LUT[row[x].toInt() and 0xFF] else BACKGROUND
        }
        version++
    }

    fun clear() {
        seq = 0
        width = 0
        height = 0
        pixels = IntArray(0)
        version++
    }

    companion object {
        const val BACKGROUND = 0xFF0B1020.toInt()

        /** byte -> ARGB, from -3 dB in 0.125 dB steps (the box's WF_DB_LOW, WF_DB_STEP). */
        val LUT: IntArray = IntArray(256).also { lut ->
            val stops = listOf(
                -3.0 to intArrayOf(8, 12, 28), 0.5 to intArrayOf(10, 20, 70), 3.0 to intArrayOf(20, 70, 190),
                6.0 to intArrayOf(20, 190, 220), 10.0 to intArrayOf(250, 230, 60), 15.0 to intArrayOf(240, 60, 30),
            )
            for (b in 0 until 256) {
                val db = -3 + b * 0.125
                var k = 0
                while (k < stops.size - 2 && db > stops[k + 1].first) k++
                val (d0, c0) = stops[k]
                val (d1, c1) = stops[k + 1]
                val t = ((db - d0) / (d1 - d0)).coerceIn(0.0, 1.0)
                val c = IntArray(3) { j -> (c0[j] + (c1[j] - c0[j]) * t).toInt().coerceIn(0, 255) }
                lut[b] = (0xFF shl 24) or (c[0] shl 16) or (c[1] shl 8) or c[2]
            }
        }
    }
}
