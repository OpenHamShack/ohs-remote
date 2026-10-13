package com.openhamshack.remote.shack

// The radio's own receive controls as OpenHamShack Flex offers them (its
// API.md 5 and the slice.agc / slice.antenna actions): noise reduction and
// the blankers, AGC and the receive antenna. Every one is the radio's DSP or
// switching, per slice, and none reaches the transmitter - which is why the
// module puts them at Control and Remote may offer them on a TV. Pure, so
// what is shown and what is sent are tested on the JVM.

/**
 * One of the radio's DSP functions as the module names it. [hasLevel] is
 * false for RNN and ANFT, which the module refuses a level for (400);
 * [extended] marks the six newer ones only some radios have, which are
 * shown only when the slice's `dsp_offered` lists them (else the module
 * answers 409 dsp_not_offered and nothing reaches the radio).
 */
data class FlexDspFunction(val name: String, val hasLevel: Boolean, val extended: Boolean)

object FlexDspTable {
    /** API.md 5's table, in its order: noise reduction first. (`dsp_offered` lists them in the module's own order.) */
    val ALL = listOf(
        FlexDspFunction("nr", true, false),
        FlexDspFunction("nb", true, false),
        FlexDspFunction("wnb", true, false),
        FlexDspFunction("anf", true, false),
        FlexDspFunction("apf", true, false),
        FlexDspFunction("nrl", true, true),
        FlexDspFunction("nrs", true, true),
        FlexDspFunction("rnn", false, true),
        FlexDspFunction("nrf", true, true),
        FlexDspFunction("anfl", true, true),
        FlexDspFunction("anft", false, true),
    )

    fun of(name: String): FlexDspFunction? = ALL.firstOrNull { it.name == name }

    /**
     * What to show for [slice]: each function the radio offers, in the
     * table's order (noise reduction first), whatever order the module
     * listed them in. A name this build does not know is left out rather
     * than guessed at: its level or lack of one is not known here.
     */
    fun offered(slice: FlexSlice?): List<FlexDspFunction> {
        if (slice == null) return emptyList()
        return ALL.filter { it.name in slice.dspOffered }
    }

    /**
     * What is switched on for [slice] now, in the table's order, for the
     * status chips above the dial: every function at once, so NB or ANF
     * shows the same way NR always did (Brian, 13/10/2026). A function
     * this build does not know is left out, as in [offered].
     */
    fun onNow(slice: FlexSlice?): List<FlexDspFunction> {
        if (slice == null) return emptyList()
        return ALL.filter { slice.dsp[it.name]?.on == true }
    }

    /** One step of a level on a D-pad or a +/- button: 0-100 in fives, as the radio's own knobs go. */
    const val LEVEL_STEP = 5

    fun nextLevel(level: Int, by: Int): Int = ((level / LEVEL_STEP + by) * LEVEL_STEP).coerceIn(0, 100)
}

/** The radio's AGC modes, in the order a control steps through them (slice.agc `mode`). */
object FlexAgc {
    val MODES = listOf("off", "slow", "med", "fast")
    const val STEP = 5
}

/**
 * A change this device asked for that the module does not show in its
 * state until the radio reports it: an antenna (the module applies
 * slice.antenna to its model only when the radio's status says so, and
 * the radio never tells the client that made a change: PROTOCOL.md 1).
 * Shown while the state still says what it said when asked ([was]), and
 * forgotten the moment it says anything else - the radio's word, or
 * somebody else's change at the radio, wins.
 */
data class FlexHeld<T>(val slice: Int, val was: T, val want: T) {
    fun shown(sliceNow: Int?, now: T): T = if (sliceNow == slice && now == was) want else now

    /** Still worth holding against [now]. */
    fun holds(sliceNow: Int?, now: T): Boolean = sliceNow == slice && now == was && want != was
}
