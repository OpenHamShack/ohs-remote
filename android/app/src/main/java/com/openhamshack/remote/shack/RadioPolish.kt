package com.openhamshack.remote.shack

import kotlin.math.abs

// The TV polish pass (Brian approved the mockups TvPolish, TvPolishBands and
// TvPolishDab on 23/10/2026): the decisions behind it, kept out of Compose
// for the JVM tests - how many segments the S-meter lights, the readout's
// dimmer last digits, and a station's initials badge and its colour.

/**
 * The segmented S-meter: S1 to S9, then +10 dB to +60 dB over S9, fifteen
 * segments in all, labelled 1 3 5 7 9 +20 +40 +60. The calibrated reading
 * (SMeter.s, "S7", "S9+20") says how many are lit; without one the bar's
 * own fraction does, as the plain bar always has.
 */
object SMeterScale {
    const val SEGMENTS = 15

    /** Segments lit for a reading such as "S7" or "S9+23" (S9 plus whole tens); null when it is not one. */
    fun litFor(s: String?): Int? {
        val m = Regex("""^S(\d)(?:\+(\d+))?$""").find(s?.trim()?.uppercase() ?: return null) ?: return null
        val units = m.groupValues[1].toInt()
        val over = m.groupValues[2].toIntOrNull() ?: 0
        if (units > 9) return null
        return if (units < 9) units.coerceAtLeast(0) else (9 + over / 10).coerceAtMost(SEGMENTS)
    }

    /** Lit from the bar's 0..1 fraction (-120 to -20 dBFS) when there is no calibrated reading. */
    fun litFor(fraction: Float): Int = Math.round(fraction.coerceIn(0f, 1f) * SEGMENTS)

    /** The label under segment [i] ("" for most): S-units 1 3 5 7 9, then +20 +40 +60. */
    fun label(i: Int): String = when {
        i < 9 -> if (i % 2 == 0) (i + 1).toString() else ""
        (i - 8) % 2 == 0 -> "+" + (i - 8) * 10
        else -> ""
    }

    /** Whether segment [i] is over S9 (drawn lighter, as the mockup's). */
    fun over(i: Int): Boolean = i >= 9
}

/**
 * The readout's figures split for the polish (TvPolish: "97.700" big and
 * "00" smaller and dimmer): the last two digits of a five-decimal MHz
 * reading are the 10 Hz the operator rarely reads. kHz readings and
 * anything shorter are left whole.
 */
fun splitTail(number: String, unit: String): Pair<String, String> {
    if (unit != "MHz" || number.length < 4 || !number.takeLast(2).all { it.isDigit() }) return number to ""
    return number.dropLast(2) to number.takeLast(2)
}

/** A station's badge: up to two letters or digits for its name ("BBC Radio 4" -> "R4", "Clyde 1" -> "C1", "Signal 1" -> "S1"). */
object StationBadge {
    /** Words that say whose station it is, not which: left out of the initials. */
    private val SKIP = setOf("BBC", "THE", "RADIO")

    fun initials(name: String): String {
        val words = name.split(Regex("""[\s\-_/]+""")).filter { w -> w.any { it.isLetterOrDigit() } }
        if (words.isEmpty()) return "?"
        // "BBC Radio 4" is R4, not BR: the brand is the same on every station of a multiplex.
        val kept = words.filter { it.uppercase() !in SKIP }.ifEmpty { words }
        val radioNumber = words.size >= 2 && words.any { it.equals("radio", true) } && words.last().all { it.isDigit() }
        if (radioNumber) return "R" + words.last().take(1)
        val first = kept.first().first { it.isLetterOrDigit() }.uppercaseChar()
        val second = kept.getOrNull(1)?.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
            ?: kept.first().drop(1).firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
        return listOfNotNull(first, second).joinToString("")
    }

    /**
     * The badge's colour, one of [palette] picked by the service's own id
     * (a DAB SId, an FM station's PI or frequency): the same station is
     * always the same colour, on every device, in every scan.
     */
    fun colourIndex(key: String, palette: Int): Int {
        if (palette <= 0) return 0
        var h = 0
        for (c in key) h = 31 * h + c.code
        return abs(h % palette)
    }
}
