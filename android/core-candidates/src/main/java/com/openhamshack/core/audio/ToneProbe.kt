package com.openhamshack.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * What the audio sounds like, in two numbers: its level and its strongest
 * frequency. For the on-screen level bar, and for testing against the fake
 * server's 1 kHz tone - an emulator plays to no speaker, so "is the right
 * audio arriving" has to be answered by measuring it.
 */
object ToneProbe {
    /** RMS level as a fraction of full scale. */
    fun rms(s: ShortArray, n: Int = s.size): Double {
        if (n == 0) return 0.0
        var sum = 0.0
        for (i in 0 until n) { val v = s[i] / 32768.0; sum += v * v }
        return sqrt(sum / n)
    }

    /** Goertzel power at [hz]. */
    fun power(s: ShortArray, n: Int, rate: Int, hz: Double): Double {
        val w = 2 * PI * hz / rate
        val c = 2 * cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (i in 0 until n) {
            val s0 = s[i] / 32768.0 + c * s1 - s2
            s2 = s1; s1 = s0
        }
        return s1 * s1 + s2 * s2 - c * s1 * s2
    }

    /** The strongest frequency between [lo] and [hi] Hz, to [step] Hz. */
    fun peakHz(s: ShortArray, n: Int, rate: Int, lo: Int = 100, hi: Int = 3000, step: Int = 10): Int {
        var best = lo; var bestP = -1.0
        var f = lo
        while (f <= hi) {
            val p = power(s, n, rate, f.toDouble())
            if (p > bestP) { bestP = p; best = f }
            f += step
        }
        return best
    }
}
