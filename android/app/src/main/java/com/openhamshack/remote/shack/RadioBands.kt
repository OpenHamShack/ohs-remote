package com.openhamshack.remote.shack

import org.json.JSONObject
import kotlin.math.abs

// Radio's band buttons (Brian, 13/10/2026: "an easier way of changing/
// selecting the band"; he chose a row of big buttons, always on screen).
// One press goes to where this device last left that band on this box, or
// on a first visit to the band's usual place by Brian's rules (target);
// pressed again, it steps through FT8, SSB and CW (next). The jump to
// another band is Radio's channel keys on a TV (REMOTE-KEYS §4: Ch± is band
// ± on Radio).
//
// Remembered here, per device, not with the server's band stacks
// (`bandStacks`, the desktop app's band bar): Brian asked for "where you last
// were" on this screen, and a stack register a desktop moves would make a
// button land somewhere this person never was. Receive only: a jump is the
// dial and the mode through `api/set`, as a bookmark is; nothing keys.

/** Where a band button goes: the receive dial and the mode, set together. */
data class BandSpot(val hz: Double, val mode: String)

/** A band with a button: the dataset's band (edges, name) and the word on the button. */
data class BandButton(val band: PlanBand, val label: String) {
    val name: String get() = band.name
}

object RadioBands {
    /**
     * The bands with buttons, in this order (Brian's list): HF, 6 m, 4 m and
     * the two VHF/UHF bands a Radio is most often on; then (23/10/2026, "23cm
     * band") the microwave bands the dataset has that an SDR may reach - 23,
     * 13, 9, 6 and 3 cm - which a receiver's rxRange leaves out when it
     * cannot (the RSPdx stops at 2 GHz, after 23 cm). Which bands exist and
     * where they start is the dataset's; this is only which get a button.
     */
    val NAMES = listOf("160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m", "4m", "2m", "70cm",
        "23cm", "13cm", "9cm", "6cm", "3cm")

    /** "160m" -> "160", "70cm" -> "70cm": metres go without their unit, as on a radio's band keys. */
    fun label(name: String): String = if (name.endsWith("cm") || name.endsWith("mm")) name else name.removeSuffix("m")

    /**
     * The buttons for a receiver that covers [rxLoHz]..[rxHiHz], when the
     * server says (`rxRange` in its status); every band when it does not.
     * A band the receiver reaches any part of keeps its button.
     */
    fun buttons(vocab: PlanVocab, rxLoHz: Double? = null, rxHiHz: Double? = null): List<BandButton> =
        NAMES.mapNotNull { vocab.band(it) }
            .filter { b -> rxLoHz == null || rxHiHz == null || (b.hiHz >= rxLoHz && b.loHz <= rxHiHz) }
            .map { BandButton(it, label(it.name)) }

    /** Which button is lit: the server's band name (`band`) when it gives one, else the band the dial is in; null between bands. */
    fun current(buttons: List<BandButton>, serverBand: String, dialHz: Double?): BandButton? =
        buttons.firstOrNull { it.name == serverBand && serverBand.isNotEmpty() }
            ?: dialHz?.let { f -> buttons.firstOrNull { f in it.band } }

    /**
     * The button [by] along from the dial's band (Ch±), stopping at the ends.
     * Between bands, the next one up or down from the dial, so a TV tuned
     * to 5 MHz with no button lit still moves the way the key says.
     */
    fun step(buttons: List<BandButton>, serverBand: String, dialHz: Double?, by: Int): BandButton? {
        if (buttons.isEmpty() || by == 0) return null
        val at = current(buttons, serverBand, dialHz)
        if (at != null) return buttons.getOrNull((buttons.indexOf(at) + by).coerceIn(0, buttons.size - 1))?.takeIf { it != at }
        val f = dialHz ?: return if (by > 0) buttons.first() else buttons.last()
        return if (by > 0) buttons.firstOrNull { it.band.loHz > f } else buttons.lastOrNull { it.band.hiHz < f }
    }

    /**
     * Where a press on [band] goes, by Brian's rules for desktop Radio's band
     * bar (13/10/2026): [remembered] (this device's last spot there), else the
     * band's usual place for the mode Radio is in now -
     *
     * - a digital mode ([PlanVocab.isDigital]): that mode's dial ([digital]),
     *   FT4's from FT4, else FT8's;
     * - FM, on a band whose plan names an FM calling channel (2 m 145.500,
     *   70 cm 433.500): that channel, staying in FM ([fmCalling]);
     * - otherwise the band's SSB segment ([ssb]); a band with none (30 m)
     *   goes to FT8 instead, then CW, then its lower edge.
     */
    fun target(band: PlanBand, remembered: BandSpot?, mode: String, memories: List<Bookmark>, data: PlanData, planId: String?): BandSpot {
        remembered?.takeIf { it.hz in band }?.let { return it }
        if (data.vocab.isDigital(mode)) digital(band, mode, memories, data, planId)?.let { return it }
        if (mode == FM) fmCalling(band, data, planId)?.let { return it }
        return ssb(band, data, planId) ?: digital(band, FT8, memories, data, planId) ?: cw(band, data, planId)
            ?: BandSpot(band.loHz.toDouble(), sideband(band, band.loHz.toDouble()))
    }

    /**
     * The same band pressed again: the next of FT8, SSB and CW after what
     * Radio is in now (a digital mode counts as FT8, SSB as either sideband,
     * anything else starts at FT8), skipping any the band has no place for.
     * The desktop's band bar steps its registers the same way.
     */
    fun next(band: PlanBand, mode: String, memories: List<Bookmark>, data: PlanData, planId: String?): BandSpot? {
        val order = listOf(FT8, SSB, CW)
        val at = when {
            data.vocab.isDigital(mode) -> 0
            mode == "usb" || mode == "lsb" -> 1
            mode == CW -> 2
            else -> -1
        }
        for (k in 1..order.size) {
            val spot = when (order[(at + k).mod(order.size)]) {
                FT8 -> digital(band, FT8, memories, data, planId)
                SSB -> ssb(band, data, planId)
                else -> cw(band, data, planId)
            }
            if (spot != null) return spot
        }
        return null
    }

    /**
     * A digital mode's dial in [band]: the server's own memory for [mode]
     * there, else its FT8 memory ("FT8 20m": WSJT-X's dials, ohsradio
     * radio.presets, which no plan publishes; the server has no FT4 memories
     * yet, so FT4 lands on FT8's dial in FT8), else a plan centre for that
     * mode (IARU R3 lists FT8's). Null when there is none.
     */
    fun digital(band: PlanBand, mode: String, memories: List<Bookmark>, data: PlanData, planId: String?): BandSpot? {
        val inBand = memories.filter { it.hz in band }
        (inBand.firstOrNull { it.mode == mode } ?: inBand.firstOrNull { it.mode == FT8 })?.let { return BandSpot(it.hz, it.mode) }
        val centres = data.centresFor(planId, band)
        return (centres.firstOrNull { it.mode == mode } ?: centres.firstOrNull { it.mode == FT8 })?.let { BandSpot(it.hz.toDouble(), it.mode) }
    }

    /**
     * The FM calling channel the plan names in [band], in FM: the centre
     * labelled "FM calling" (RSGB 2026 and IARU R1 2 m 145.500, 70 cm
     * 433.500), else any FM centre that is a calling channel and not a DV
     * one (IARU R2's "FM simplex calling"). Labels are the dataset's own
     * words, which is where the plan says which channel calls.
     */
    fun fmCalling(band: PlanBand, data: PlanData, planId: String?): BandSpot? {
        val fm = data.centresFor(planId, band).filter { it.mode == FM }
        val c = fm.firstOrNull { it.label.equals("FM calling", ignoreCase = true) }
            ?: fm.firstOrNull { it.label.contains("calling", ignoreCase = true) && !it.label.startsWith("DV", ignoreCase = true) }
        return c?.let { BandSpot(it.hz.toDouble(), FM) }
    }

    /**
     * The band's SSB segment, in its sideband: the widest plan row in the band
     * that carries SSB, is not a beacon/satellite/repeater row, and leaves FM
     * out. Widest, because the first SSB row is often a few-kHz shared window
     * (IARU R1 160 m 1840-1843, beside FT8) or, on 2 m, satellite downlinks;
     * without FM, because a VHF row that also carries FM is a whole-band
     * all-mode block (IARU R1 70 cm 430-432 "national planning"). USB lands on
     * the row's start; LSB 3 kHz in, so the whole of an LSB signal is inside
     * the segment (Brian). Measured against the shipped data (RadioBandsTest):
     * RSGB 2026 gives 3.603 and 7.053 LSB, 14.125, 21.151, 28.225, 144.150 and
     * 432.000 USB. Null when the plan has no such row.
     */
    fun ssb(band: PlanBand, data: PlanData, planId: String?): BandSpot? {
        val row = widest(band, data.rowsFor(planId, band).filter { it.kind.isEmpty() && "ssb" in it.modes && "fm" !in it.modes })
            ?: return null
        val start = maxOf(row.loHz, band.loHz).toDouble()
        val side = sideband(band, start)
        return BandSpot(if (side == "lsb") start + LSB_IN_HZ else start, side)
    }

    /** The middle of the band's widest CW-only row, to the kHz, in CW: away from the band edge the row starts at. */
    fun cw(band: PlanBand, data: PlanData, planId: String?): BandSpot? {
        val row = widest(band, data.rowsFor(planId, band).filter { it.kind.isEmpty() && it.modes == setOf(CW) })
            ?: widest(band, data.rowsFor(planId, band).filter { it.kind.isEmpty() && CW in it.modes })
            ?: return null
        val mid = (maxOf(row.loHz, band.loHz) + minOf(row.hiHz, band.hiHz)) / 2
        return BandSpot((mid / 1000 * 1000).toDouble(), CW)
    }

    private fun widest(band: PlanBand, rows: List<PlanSegment>): PlanSegment? =
        rows.maxWithOrNull(compareBy<PlanSegment> { minOf(it.hiHz, band.hiHz) - maxOf(it.loHz, band.loHz) }.thenByDescending { it.loHz })

    /**
     * LSB below 10 MHz and USB above, as every band change on a radio does -
     * except 60 m, which is USB: the UK's rule, and IARU R1's own 60 m row says
     * "all modes, USB", so it is USB whatever the plan.
     */
    fun sideband(band: PlanBand, hz: Double): String = if (hz < 10e6 && band.name != "60m") "lsb" else "usb"

    const val FT8 = "ft8"
    const val CW = "cw"
    const val FM = "fm"
    private const val SSB = "ssb"
    /** How far inside the SSB segment an LSB dial goes: a voice signal's width. */
    const val LSB_IN_HZ = 3_000.0

    // --- this device's last spot on each band, per box -----------------------

    /** A box's remembered spots as one preference value: {"20m": {"hz": 14074000, "mode": "ft8"}, ...}. */
    fun encode(spots: Map<String, BandSpot>): String =
        JSONObject().apply { spots.forEach { (k, v) -> put(k, JSONObject().put("hz", v.hz).put("mode", v.mode)) } }.toString()

    fun decode(raw: String?): Map<String, BandSpot> {
        val o = runCatching { JSONObject(raw ?: return emptyMap()) }.getOrNull() ?: return emptyMap()
        return o.keys().asSequence().mapNotNull { k ->
            val s = o.optJSONObject(k) ?: return@mapNotNull null
            val hz = s.optDouble("hz", Double.NaN)
            val mode = s.optString("mode")
            if (!hz.isFinite() || hz <= 0 || mode.isEmpty()) null else k to BandSpot(hz, mode)
        }.toMap()
    }

    /**
     * The spots after the dial moved to [hz] in [mode]: the band it is in
     * now remembers it. Between bands nothing changes, so a trip to a
     * broadcast station does not overwrite the band left behind.
     */
    fun remember(spots: Map<String, BandSpot>, vocab: PlanVocab, hz: Double?, mode: String): Map<String, BandSpot> {
        if (hz == null || mode.isEmpty()) return spots
        val band = NAMES.firstNotNullOfOrNull { n -> vocab.band(n)?.takeIf { hz in it } } ?: return spots
        val spot = BandSpot(hz, mode)
        val was = spots[band.name]
        return if (was != null && abs(was.hz - hz) < 0.5 && was.mode == mode) spots else spots + (band.name to spot)
    }
}

/** This device's remembered band spots, per box ([key]: the controller and the app). */
interface BandMemory {
    fun load(key: String): Map<String, BandSpot>
    fun save(key: String, spots: Map<String, BandSpot>)
}
