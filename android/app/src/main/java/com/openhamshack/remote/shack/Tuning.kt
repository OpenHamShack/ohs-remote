package com.openhamshack.remote.shack

import kotlin.math.floor

/**
 * Tuning steps and arithmetic for the NB and Radio screens, pure for tests.
 *
 * Steps are the client's: the store API takes a dial frequency (`vfo`) and
 * has no step setting of its own. NB's run from 10 Hz (zero-beating an
 * SSB voice) to 5 kHz (crossing the transponder in a hundred presses);
 * Radio's add the channel steps of VHF/UHF FM (12.5 and 25 kHz) and
 * 100 kHz for moving across HF bands.
 */
object Tuning {
    val NB_STEPS = listOf(10, 50, 100, 500, 1_000, 5_000)
    val RADIO_STEPS = listOf(10, 100, 1_000, 5_000, 12_500, 25_000, 100_000)

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
     */
    fun stepped(hz: Double, step: Int, by: Int, min: Double? = null, max: Double? = null): Double {
        val base = floor(hz / step) * step
        val onGrid = hz - base < 0.5
        val n = when {
            by > 0 -> base + step.toDouble() * by
            onGrid -> base + step.toDouble() * by
            else -> base + step.toDouble() * (by + 1)
        }
        var out = n
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
