package com.openhamshack.remote.shack

import kotlin.math.roundToLong

// The FM channels of a band plan (Brian, 23/10/2026: "when in the fm
// section of 2m can you auto select fm and 12.5khz step"). Tuning into a
// part of the plan that is FM channels and nothing else - 2 m's FM simplex,
// its repeater inputs and outputs, 70 cm's and 4 m's channels - puts the
// receiver in FM, steps 12.5 kHz and lands on a channel, as a handheld's
// channel mode would.
//
// Only on the way in, from outside every such part, and only for this
// device's own tuning: someone who then picks SSB there, or tunes from FM
// simplex on into the repeater outputs, is not overruled each step, and a
// dial another device moves never makes this one send a mode.

object FmChannels {
    const val STEP_HZ = 12_500

    /** What tuning to [hz] should do on the way in: the channel to land on, and the mode when FM is not already in use. */
    data class Entry(val hz: Double, val mode: String?)

    /**
     * Whether [hz] is in a part of the plan carried only by FM and digital
     * voice (the dataset's `modes`), or a repeater output (`kind` output,
     * whose modes the dataset leaves empty: an output is FM).
     */
    fun inSection(data: PlanData, planId: String?, hz: Double?): Boolean {
        val band = data.vocab.bandAt(hz) ?: return false
        val f = hz ?: return false
        val rows = data.rowsFor(planId, band).filter { f >= it.loHz && f < it.hiHz }
        val row = rows.minByOrNull { it.hiHz - it.loHz } ?: return false
        return row.kind == "output" || (row.modes.isNotEmpty() && row.modes.all { it == "fm" || it == "dv" })
    }

    /**
     * Tuning from [fromHz] to [toHz] in [mode]: null unless it crosses into
     * an FM section; else the nearest 12.5 kHz channel and "fm" when the
     * mode in use is not already FM or digital voice (FreeDV stays) and
     * the server offers FM ([offered]).
     */
    fun entering(data: PlanData, planId: String?, fromHz: Double?, toHz: Double, mode: String, offered: List<String>): Entry? {
        if (!inSection(data, planId, toHz) || inSection(data, planId, fromHz)) return null
        val channel = (toHz / STEP_HZ).roundToLong() * STEP_HZ.toDouble()
        val cls = data.vocab.modeClass[mode.lowercase()]
        val want = if (cls == "fm" || cls == "dv" || "fm" !in offered) null else "fm"
        return Entry(channel, want)
    }
}
