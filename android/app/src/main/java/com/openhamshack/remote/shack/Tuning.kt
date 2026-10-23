package com.openhamshack.remote.shack

import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Tuning steps and arithmetic for the NB and Radio screens, pure for tests.
 *
 * Steps are the client's: the store API takes a dial frequency (`vfo`) and
 * has no step setting of its own. NB's run from 10 Hz (zero-beating an
 * SSB voice) to 5 kHz (crossing the transponder in a hundred presses);
 * Radio's add the channel steps of VHF/UHF FM (12.5 and 25 kHz) and
 * 100 kHz for moving across HF bands, and (23/10/2026, the listening areas)
 * the broadcast and airband rasters: 8.33 kHz airband channels, 9 kHz
 * LW/MW, 10 kHz CB, and 200 kHz for crossing Band II and Band III.
 */
object Tuning {
    /**
     * The 8.33 kHz airband step. Its real size is 25 kHz / 3 (8333.33 Hz),
     * which no Int holds: this value is the step's name, and [stepped]
     * counts in thirds of 25 kHz whenever it is the step, so three presses
     * are exactly 25 kHz and a channel is never a hertz adrift.
     */
    const val AIRBAND_833 = 8_333
    private const val AIRBAND_UNIT = 25_000.0 / 3

    val NB_STEPS = listOf(10, 50, 100, 500, 1_000, 5_000)
    val RADIO_STEPS = listOf(10, 100, 1_000, 5_000, AIRBAND_833, 9_000, 10_000, 12_500, 25_000, 100_000, 200_000)

    fun steps(kind: Kind): List<Int> = if (kind == Kind.RADIO) RADIO_STEPS else NB_STEPS

    /** 100 Hz on NB, where everything is SSB, CW or data; 1 kHz on Radio. */
    fun defaultStep(kind: Kind): Int = if (kind == Kind.RADIO) 1_000 else 100

    /** The next step up ([by] > 0) or down, held at the ends. */
    fun nextStep(kind: Kind, current: Int, by: Int): Int {
        val list = steps(kind)
        val i = list.indexOf(current).let { if (it < 0) list.indexOf(defaultStep(kind)) else it }
        return list[(i + by).coerceIn(0, list.size - 1)]
    }

    /**
     * [by] steps from [hz], landing on the step's grid: from 10 489 703 Hz
     * a 100 Hz step up is 10 489 800, not 10 489 803, so the dial reads as
     * the operator expects after the first press.
     *
     * The grid is counted from [origin] (a listening area's first channel,
     * ListenAreas.origin: PMR446's channels are 12.5 kHz apart from
     * 446.00625 MHz, not from 0 Hz). [AIRBAND_833] counts in 25 kHz / 3 and
     * lands on whole Hz: 118.000 up one is 118.008333 MHz, sent as
     * 118 008 333 Hz, which the server takes as it is (RADIO-LISTEN-CONTRACT §2).
     */
    fun stepped(hz: Double, step: Int, by: Int, min: Double? = null, max: Double? = null, origin: Double = 0.0): Double {
        val unit = if (step == AIRBAND_833) AIRBAND_UNIT else step.toDouble()
        val k = floor((hz - origin) / unit)
        val base = origin + k * unit
        // Within half a hertz of a channel is on it: an 8.33 kHz channel is sent rounded.
        val onGrid = hz - base < 0.5 || base + unit - hz < 0.5
        val from = if (base + unit - hz < 0.5) k + 1 else k
        val n = when {
            by > 0 -> from + by
            onGrid -> from + by
            else -> from + by + 1
        }
        var out = (origin + n * unit).let { if (step == AIRBAND_833) it.roundToLong().toDouble() else it }
        if (min != null && out < min) out = min
        if (max != null && out > max) out = max
        return out
    }

    /** The channel of [step] nearest [hz], counted from [origin] as [stepped] counts (8.33 kHz in thirds of 25 kHz, whole Hz). */
    fun nearest(hz: Double, step: Int, origin: Double = 0.0): Double {
        val unit = if (step == AIRBAND_833) AIRBAND_UNIT else step.toDouble()
        val out = origin + Math.round((hz - origin) / unit) * unit
        return if (step == AIRBAND_833) out.roundToLong().toDouble() else out
    }

    /**
     * A drag along the scope: [fromHz] (the dial when the finger went down)
     * moved by [dxFraction] of the scope's width, which shows [spanHz], and
     * landed on the step's nearest channel. [grab] is the zoomed scope,
     * which recentres on the dial: there the finger drags the spectrum, as
     * a waterfall is dragged in any SDR program, so a drag to the right
     * tunes down. On the wide scope the spectrum stays put and the finger
     * carries the dial line, so a drag to the right tunes up.
     */
    fun dragged(fromHz: Double, dxFraction: Double, spanHz: Double, step: Int, grab: Boolean, origin: Double = 0.0,
                min: Double? = null, max: Double? = null): Double {
        val moved = fromHz + (if (grab) -1 else 1) * dxFraction * spanHz
        var out = nearest(moved, step, origin)
        if (min != null && out < min) out = min
        if (max != null && out > max) out = max
        return out
    }

    /** The next mode in [choices] after [current], wrapping; [current] if there are none. */
    fun nextMode(choices: List<String>, current: String, by: Int): String {
        if (choices.isEmpty()) return current
        val i = choices.indexOf(current)
        if (i < 0) return choices.first()
        return choices[Math.floorMod(i + by, choices.size)]
    }
}
