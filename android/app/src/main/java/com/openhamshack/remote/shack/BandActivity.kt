package com.openhamshack.remote.shack

import com.openhamshack.core.decode.Cty

// The decode list as Band Activity, the desktop NB's name for it (ohscore
// ui/panels/ft8.py): what each row says and how it stands out. Brian
// asked for it on 12/10/2026, on his Tab S9 at QO-100 NB: "make the
// decode more prominent on the screen, show countries on it like we do in
// the linux/windows app". Pure, so the JVM tests decide the rules and the
// screens (ui/EngineDecodes.kt) only draw them.

/**
 * How a row stands out, strongest first. The desktop's order: a message
 * naming us beats a CQ (someone answering us matters more than someone
 * calling), and our own over is its own colour whatever it says - our CQ
 * is not a CQ to answer.
 */
enum class Emphasis { TX, MINE, CQ, NONE }

/** One row: the decode, the country to show (maybe ""), its emphasis, and whether a slot starts here. */
data class BandRow(val decode: SlotDecode, val country: String, val emphasis: Emphasis, val startsSlot: Boolean) {
    /** A tap may answer it: a station heard, with a call to answer. Our own over never. */
    val answerable: Boolean get() = !decode.tx && decode.sender.isNotEmpty()
}

/**
 * The columns a list shows. Message is always there; the others go in a
 * fixed order as the width shrinks: DT first (a number few act on), then
 * Freq, then the time column (the slot rule carries the time instead),
 * Country last but one - it is what was asked for - and dB never.
 */
data class BandColumns(val time: Boolean, val dt: Boolean, val freq: Boolean, val country: Boolean) {
    companion object {
        val ALL = BandColumns(time = true, dt = true, freq = true, country = true)

        /**
         * What fits in [chars] character cells of the list's monospace
         * font. Measured in cells rather than dp so that a TV's 18 sp, a
         * tablet's 18 sp and a phone's 14 sp, and Android's font scale,
         * all choose by what fits, not by which device it is.
         */
        fun forWidth(chars: Float): BandColumns = when {
            chars >= 58f -> ALL
            chars >= 52f -> BandColumns(time = true, dt = false, freq = true, country = true)
            chars >= 46f -> BandColumns(time = true, dt = false, freq = false, country = true)
            // 33: dB, a 17-character exchange ("G4XYZ EA4QRP IN80") and ten
            // of a country - what a TV's docked list beside the waterfall has
            // at 18 sp, where 37 left the column off.
            chars >= 33f -> BandColumns(time = false, dt = false, freq = false, country = true)
            else -> BandColumns(time = false, dt = false, freq = false, country = false)
        }
    }
}

object BandActivity {
    /** How [d] stands out to the station [myCall]. */
    fun emphasis(d: SlotDecode, myCall: String): Emphasis = when {
        d.tx -> Emphasis.TX
        d.mentions(myCall) -> Emphasis.MINE
        d.isCq -> Emphasis.CQ
        else -> Emphasis.NONE
    }

    /**
     * The sender's country: the server's when it named one (it reads the
     * operator's own cty.dat first), else [cty]'s lookup of the sender,
     * else "". Our own over has none: the column would only ever say
     * where we are.
     */
    fun country(d: SlotDecode, cty: Cty?): String = when {
        d.tx -> ""
        d.country.isNotEmpty() -> d.country
        else -> d.sender.takeIf { it.isNotEmpty() }?.let { cty?.lookup(it)?.name }.orEmpty()
    }

    /**
     * Every kept decode as a row, in [decodes]'s order (oldest slot first,
     * our over at the head of its slot, then by audio frequency): the
     * list follows the newest at its foot as the desktop's and WSJT-X's do.
     */
    fun rows(decodes: SlotDecodes, myCall: String, cty: Cty?): List<BandRow> =
        decodes.items.mapIndexed { i, d -> BandRow(d, country(d, cty), emphasis(d, myCall), decodes.startsSlot(i)) }
}

/**
 * What the list draws, top to bottom: a heading for each section that has
 * one, then its rows. [dimmed] is everything above the newest section -
 * kept to scroll back through, but not where the dial is now, so a tap on
 * it never answers a station on a band we have left.
 */
sealed interface BandItem {
    val dimmed: Boolean
    data class Heading(val head: SectionHead, override val dimmed: Boolean) : BandItem
    data class Line(val row: BandRow, override val dimmed: Boolean) : BandItem
}

object BandSections {
    /** The list as headings and rows, sections in order. */
    fun items(decodes: SlotDecodes, myCall: String, cty: Cty?): List<BandItem> {
        val out = ArrayList<BandItem>()
        var flat = 0
        decodes.sections.forEachIndexed { n, s ->
            val dimmed = n < decodes.sections.lastIndex
            s.head?.let { out += BandItem.Heading(it, dimmed) }
            for (d in s.items) {
                out += BandItem.Line(BandRow(d, BandActivity.country(d, cty), BandActivity.emphasis(d, myCall), decodes.startsSlot(flat)), dimmed)
                flat++
            }
        }
        return out
    }

    /** "20m" -> "20 m", "70cm" -> "70 cm": a band name as it reads in a heading; "" for none. */
    fun bandText(band: String): String {
        val unit = band.takeLastWhile { it.isLetter() }
        return if (unit.isEmpty() || unit.length == band.length) band else band.dropLast(unit.length) + " " + unit
    }
}
