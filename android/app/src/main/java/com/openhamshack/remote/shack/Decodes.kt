package com.openhamshack.remote.shack

import org.json.JSONObject
import kotlin.math.abs

// What the NB and Radio engines' decoders send on api/events (kind
// "events", ohscore engine._emit_event), as the screens keep it: whole
// messages from the slotted decoders (FT8, FT4, Q65, JS8) and a rolling
// line from the character decoders (CW, RTTY, PSK). Pure, so it is tested
// on the JVM against recorded events.

/**
 * One FT8/FT4/Q65/JS8 decode (ohscore/modes/ft8.py `_ohsdigi_event`): [slot]
 * is the slot's start, Unix seconds; [df] the audio frequency in Hz.
 */
data class SlotDecode(
    val mode: String, val slot: Double, val snr: Int, val dt: Double, val df: Int, val text: String,
    val lowConfidence: Boolean = false,
    /** The sender's call and grid, when the decoder named them; a reply sends them back as they came. */
    val call: String = "", val grid: String = "",
    /**
     * The sender's DXCC entity as the server named it (openhamshack-nb engine
     * `_with_country`); empty from an older box, a Flex or an Icom, when
     * the list looks it up itself ([BandActivity]).
     */
    val country: String = "",
    /**
     * Our own over, not a station heard: Enable TX's message as the server
     * sent it (api/events `"tx"`), listed in its slot as the desktop's TX
     * rows are. Never answered, never "mentions" us.
     */
    val tx: Boolean = false,
) {
    /**
     * Who sent it, for a reply: the decoder's own [call] when it named one;
     * after CQ, the first word that is a callsign (so "CQ DX OH8ZZ KP24" and
     * "CQ POTA EA8XYZ IL18" give the station, not the modifier); otherwise
     * the second word, as in "GM4BRI EA8XYZ -12". Empty when there is none.
     * The server would take the second word blindly, so the client always
     * sends what this says.
     */
    val sender: String get() {
        if (call.isNotEmpty()) return call
        val w = text.uppercase().split(' ').filter { it.isNotEmpty() }
        if (w.firstOrNull() == "CQ") return w.drop(1).firstOrNull { looksLikeCall(it) }.orEmpty()
        return w.getOrNull(1)?.takeIf { looksLikeCall(it.trim('<', '>')) }?.trim('<', '>').orEmpty()
    }

    /** A CQ, as WSJT-X colours it: "CQ ...", "CQ DX ...". */
    val isCq: Boolean get() = text == "CQ" || text.startsWith("CQ ")

    /**
     * The message names [call] (the station's own, from its profile): as a
     * word, or as the base of a portable form (GM4BRI/P, EA8/GM4BRI).
     */
    fun mentions(call: String): Boolean {
        val c = call.trim().uppercase()
        if (c.isEmpty() || tx) return false
        return text.uppercase().split(' ').any { w -> w == c || w.trim('<', '>') == c || w.split('/').contains(c) }
    }

    companion object {
        /**
         * Letters and a digit, ending in a letter: G4XYZ, 3D2AG, EA8/GM4BRI,
         * GM4BRI/P - and not DX, POTA, a report (-12), a grid (KP24) or RR73,
         * which all end in a digit or have none.
         */
        fun looksLikeCall(w: String): Boolean =
            w.length in 3..13 && w.any { it.isDigit() } && w.last().isLetter() && w.all { it.isLetterOrDigit() || it == '/' }

        fun parse(o: JSONObject): SlotDecode? {
            val slot = o.num("slot") ?: return null
            return SlotDecode(o.optString("mode"), slot, o.optInt("snr"), o.optDouble("dt", 0.0), o.optInt("df"),
                o.optString("text").trim(), o.optBoolean("lowConfidence"), o.optString("call"), o.optString("grid"),
                country = o.optString("country"))
        }

        /**
         * Our own over (openhamshack-nb SERVER-API "Each over sent"): `{"mode",
         * "tx": {"slot", "df", "text"}}`, nested so that no reader of
         * decodes takes it for a station heard.
         */
        fun parseTx(o: JSONObject): SlotDecode? {
            val t = o.optJSONObject("tx") ?: return null
            val slot = t.num("slot") ?: return null
            val text = t.optString("text").trim().ifEmpty { return null }
            return SlotDecode(o.optString("mode"), slot, 0, 0.0, t.optInt("df"), text, tx = true)
        }
    }
}

/**
 * Where a run of decodes was heard, for the heading above it: the band
 * (Radio names one; NB has none, "" then), the dial, the mode and when the
 * section began, Unix seconds by this device's clock.
 */
data class SectionHead(val band: String, val dialHz: Double, val mode: String, val since: Double)

/** A run of decodes heard on one band and dial; [head] is null for a list never told where it is (Flex's). */
class Section(val head: SectionHead?, val items: List<SlotDecode>)

/**
 * The decodes kept for the list: newest last, at most [max], one slot's
 * decodes together. A new mode starts the list again (an FT4 list under an
 * FT8 heading would mislead).
 *
 * Sections (Brian, 13/10/2026, on the Tab S9 going 40 m to 20 m): the list
 * is told where the dial is ([retune]) and, when the band changes or the
 * dial jumps further than the mode's passband, opens a new labelled
 * section instead of letting the old band's calls sit unmarked among the
 * new. At most [MAX_SECTIONS] are kept, the older one shown dimmed above.
 * A caller that never calls [retune] (Flex) has one headless section and
 * the behaviour it always had.
 */
class SlotDecodes private constructor(
    val sections: List<Section>, private val max: Int,
    /** The last dial and band [retune] was told, to judge the next against (not the section's, so a slow drift never splits it). */
    private val seenDial: Double?, private val seenBand: String,
) {
    constructor(items: List<SlotDecode> = emptyList(), max: Int = 200) : this(listOf(Section(null, items)), max, null, "")

    /** Every decode kept, oldest first, across sections. */
    val items: List<SlotDecode> = sections.flatMap { it.items }

    fun add(d: SlotDecode): SlotDecodes {
        val last = sections.last()
        if (items.isNotEmpty() && items.last().mode != d.mode) {
            // A new mode starts the list again, under the heading it had.
            return SlotDecodes(listOf(Section(last.head?.copy(mode = d.mode), listOf(d))), max, seenDial, seenBand)
        }
        // The engine decodes a slot after it has ended, so a decode of a slot
        // that began before the latest retune was heard on the band before:
        // it belongs above the heading, not under it. A little slack for the
        // two clocks.
        var k = sections.lastIndex
        while (k > 0 && sections[k].head.let { it != null && d.slot < it.since - CLOCK_SLACK_S }) k--
        val into = sections[k]
        // The engine sends a slot's decodes together; sorting within a slot
        // by audio frequency is WSJT-X's order. Our own over heads its slot,
        // where the desktop puts its TX row: it is sent as the slot starts,
        // and what is heard in the same slot arrives as it ends.
        val next = (into.items + d).sortedWith(compareBy({ it.slot }, { !it.tx }, { it.df }))
        val grown = sections.toMutableList().also { it[k] = Section(into.head, next) }
        return SlotDecodes(capped(grown), max, seenDial, seenBand)
    }

    /**
     * The engine says the dial is at [dialHz] in [band] ("" if it names
     * none) and [mode], at [now]. A different band, or a jump beyond the
     * mode's passband from where it was last (a small retune within it is
     * the same section), starts a new section; a section with nothing in it
     * yet is relabelled instead, so tuning about before the first decode
     * leaves no empty headings behind.
     */
    fun retune(band: String, dialHz: Double, mode: String, now: Double): SlotDecodes {
        val last = sections.last()
        // A heading still describing the same dial keeps the time it began:
        // the engine tells us its state every few seconds, and a heading
        // that moved later with each would claim the new band's first decodes
        // for the band before.
        val old = last.head
        val fresh = SectionHead(band, dialHz, mode, now)
        val relabelled = if (old != null && old.band == band && old.dialHz == dialHz) old.copy(mode = mode) else fresh
        val prev = seenDial
        val moved = prev != null && ((band.isNotEmpty() && seenBand.isNotEmpty() && band != seenBand) ||
            abs(dialHz - prev) > passbandHz(mode))
        val keepBand = band.ifEmpty { seenBand }
        return when {
            last.items.isEmpty() -> SlotDecodes(sections.dropLast(1) + Section(relabelled, emptyList()), max, dialHz, keepBand)
            moved -> SlotDecodes(capped(sections + Section(fresh, emptyList())), max, dialHz, keepBand)
            else -> SlotDecodes(sections, max, dialHz, keepBand)
        }
    }

    /**
     * Clear: nothing displayed, and only the display. The newest heading
     * stays, so the next decodes are still labelled with where they were
     * heard.
     */
    fun cleared(): SlotDecodes = SlotDecodes(listOf(Section(sections.last().head, emptyList())), max, seenDial, seenBand)

    /** Whether [i] starts a new slot (a separator goes above it): the first of its section, or a new slot time. */
    fun startsSlot(i: Int): Boolean {
        var at = 0
        for (s in sections) {
            if (i < at + s.items.size) return i == at || s.items[i - at].slot != s.items[i - at - 1].slot
            at += s.items.size
        }
        return false
    }

    /** At most [MAX_SECTIONS] sections and [max] decodes, the oldest dropped first. */
    private fun capped(all: List<Section>): List<Section> {
        val secs = all.takeLast(MAX_SECTIONS)
        var over = secs.sumOf { it.items.size } - max
        if (over <= 0) return secs
        return secs.map { s ->
            val drop = minOf(over, s.items.size).also { over -= it }
            Section(s.head, s.items.drop(drop))
        }
    }

    companion object {
        const val MAX_SECTIONS = 2
        private const val CLOCK_SLACK_S = 2.0

        /** How far the dial may move and still be the same band activity: the mode's audio window, 3 kHz for one this build has none for. */
        fun passbandHz(mode: String): Double =
            ModeAudio.of(mode, null, null, null, null, null)?.let { it.hi - it.lo } ?: 3000.0
    }
}

/**
 * A character decoder's rolling line. It lives in the shared kit now
 * (core decode/DecodedLine.kt) because the Icom kind shows the same CW
 * strip; this name stays so the NB screens and their tests are unchanged.
 */
typealias DecodedLine = com.openhamshack.core.decode.DecodedLine

/** One api/events character (a letter, a space, a prosign) appended. */
fun DecodedLine.add(e: EngineEvent.Decoded): DecodedLine = add(e.mode, e.text, e.wpm)
