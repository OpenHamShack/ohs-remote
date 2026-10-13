package com.openhamshack.remote.shack

/**
 * RF gain on a FLEX-6000/8000: one stepped control that is the preamp above
 * 0 dB and the attenuator below it - SmartSDR works out which stages to
 * switch from the one number (FlexRadio's SmartSDR API, "TCPIP display
 * pan": "Preamp and attenuator selection and settings are calculated
 * automatically by SmartSDR based on this parameter"). Receive only.
 *
 * **Which steps a radio takes differs by model**, and the radio says so
 * itself (`display pan rfgain_info <pan>` answers low, high, step and
 * markers, e.g. "-10,30,10,0,20"). The module asks it for each panadapter
 * and publishes the answer as `rf_gain_steps` (flex 0.3.1-beta.2, API.md
 * 4a), so that answer is the ladder whenever there is one. The table below
 * is only for when there is not - a module older than that, or a radio
 * whose firmware will not say:
 * - FLEX-6400 and 6400M: -8 to +32 dB in 8s (Brian's brief, 10/10/2026,
 *   for his own radio; the module's fake answers the same).
 * - anything else: -10 to +30 in 10s, the API document's own example
 *   answer, because no model's published figure is to hand here. A step the
 *   radio does not take is refused by it and said; the readout stays on
 *   what the radio last reported.
 * The radio's current value is always on the ladder, so the control can
 * show it and step away from it whatever the ladder says.
 */
object FlexRfGain {
    private val FLEX_6400 = listOf(-8, 0, 8, 16, 24, 32)
    private val FALLBACK = listOf(-10, 0, 10, 20, 30)

    /** The radio's own [radio] ladder when it gave one, else the table for [model]; [current] always on it. */
    fun steps(model: String, current: Int? = null, radio: List<Int>? = null): List<Int> {
        val m = model.uppercase().trim()
        val base = radio?.takeIf { it.isNotEmpty() }
            ?: if (m == "FLEX-6400" || m == "FLEX-6400M") FLEX_6400 else FALLBACK
        return if (current == null || current in base) base else (base + current).sorted()
    }

    /** The step [by] places from [current] on [steps], held at either end. */
    fun next(steps: List<Int>, current: Int, by: Int): Int {
        if (steps.isEmpty()) return current
        val i = steps.indexOf(current).takeIf { it >= 0 } ?: steps.indexOfFirst { it >= current }.takeIf { it >= 0 } ?: (steps.size - 1)
        return steps[(i + by).coerceIn(0, steps.size - 1)]
    }

    /** What the setting is to a ham: the attenuator below 0 dB, off at 0, the preamp above. */
    enum class Kind { ATT, OFF, PREAMP }

    fun kind(db: Int): Kind = when {
        db < 0 -> Kind.ATT
        db == 0 -> Kind.OFF
        else -> Kind.PREAMP
    }
}
