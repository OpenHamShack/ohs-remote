package com.openhamshack.core.decode

/**
 * A character decoder's rolling line: characters appended as they come,
 * the oldest dropped past [cap] so a long session does not grow without
 * end, and a change of mode starting afresh.
 *
 * Moved here from the NB kind (app shack/Decodes.kt) on 10/10/2026 when
 * OpenHamShack Icom gained the same CW decoder (ohs-libs shack_cw): the NB
 * and Radio kinds build it character by character from api/events, the
 * Icom kind from the line its state carries, and both draw [DecodedStrip].
 */
data class DecodedLine(val mode: String = "", val text: String = "", val wpm: Double? = null, val cap: Int = 160) {
    /** One decoded event's [chars] (a letter, a space, a prosign) appended. */
    fun add(mode: String, chars: String, wpm: Double?): DecodedLine {
        val same = mode == this.mode
        val joined = ((if (same) text else "") + chars).let { if (it.length > cap) it.takeLast(cap) else it }
        return DecodedLine(mode, joined, wpm ?: if (same) this.wpm else null, cap)
    }

    val isEmpty: Boolean get() = text.isBlank()
}
