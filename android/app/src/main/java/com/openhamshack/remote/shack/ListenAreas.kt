package com.openhamshack.remote.shack

import kotlin.math.roundToLong

// Radio's listening areas (Brian, 23/10/2026: "airband, broadcast FM band,
// DAB and other known areas such as PMR 446, CB etc."): receive-only bands
// that sit in the band row among the amateur bands, in frequency order,
// tagged RX (RADIO-LISTEN-CONTRACT §2). They are Remote's own table because
// they are not amateur allocations and the shared band-plan dataset will
// never carry them; the server only has to receive there. Nothing here
// transmits: a press is a dial, a mode and a step, as a bookmark is.
//
// Each area has a raster: where its channels are. Most are a whole step
// from 0 Hz (MW's 9 kHz, marine's 25 kHz), but PMR446's 12.5 kHz channels
// start at 446.00625 and UK CB's 10 kHz ones at 27.60125, so stepping from
// 0 Hz would walk between every pair of channels. The first channel is
// therefore the origin a step is counted from while the dial is in the area
// with that area's step (Tuning.stepped's origin).

/**
 * One listening area: its edges, the mode it is listened to in, its
 * channel step and where a press lands ([spotHz], a channel everyone can
 * find something on or recognise). [mode] null leaves the mode alone (DAB,
 * which is not a demodulator mode but a decoder session on a block);
 * [fallback] is the mode used when the server does not offer [mode] (wide
 * FM on a server that has no `wfm`).
 */
data class ListenArea(
    val id: String,
    val loHz: Long,
    val hiHz: Long,
    val mode: String?,
    val stepHz: Int,
    val spotHz: Double,
    /** The area's first channel: steps of [stepHz] are counted from here. */
    val originHz: Double = 0.0,
    val fallback: String? = null,
    /**
     * Tagged RX and never transmitted in. Not LPD433 (the Radio session,
     * 23/10/2026): 433.075-434.775 MHz is inside the UK 70 cm amateur band,
     * so a licensed operator may transmit there; it stays a listening area
     * (its raster, its spot, the Listen filter) without the tag.
     */
    val receiveOnly: Boolean = true,
) {
    operator fun contains(hz: Double) = hz >= loHz && hz <= hiHz
    val band: PlanBand get() = PlanBand(id, loHz, hiHz)
}

/** Where a listening area's button goes: the dial, the mode (null: leave it) and the step it sets. */
data class AreaSpot(val hz: Double, val mode: String?, val stepHz: Int)

object ListenAreas {
    const val DAB = "dab"

    /**
     * The table (RADIO-LISTEN-CONTRACT §2). Spots: LW 198 kHz and MW
     * 909 kHz (long-standing UK broadcast channels, on the 9 kHz raster);
     * CB channel 19 on both plans (the channel any CB set calls on); FM
     * 88.1 MHz; airband 121.5 MHz (the distress guard, which every aircraft
     * keeps and every airband listener knows); marine channel 16 (156.800,
     * calling and distress); DAB block 11D (DabBlocks); LPD433 and PMR446
     * channel 1.
     */
    val ALL = listOf(
        ListenArea("lw", 148_500, 283_500, "am", 9_000, 198_000.0),
        ListenArea("mw", 526_500, 1_606_500, "am", 9_000, 909_000.0),
        ListenArea("cb", 26_965_000, 27_405_000, "fm", 10_000, 27_185_000.0, originHz = 26_965_000.0),
        ListenArea("cb-uk", 27_601_250, 27_991_250, "fm", 10_000, 27_781_250.0, originHz = 27_601_250.0),
        ListenArea("fm-broadcast", 87_500_000, 108_000_000, "wfm", 100_000, 88_100_000.0, fallback = "fm"),
        ListenArea("airband", 118_000_000, 137_000_000, "am", Tuning.AIRBAND_833, 121_500_000.0),
        ListenArea("marine", 156_000_000, 162_025_000, "fm", 25_000, 156_800_000.0),
        ListenArea(DAB, 174_000_000, 240_000_000, null, 100_000, DabBlocks.DEFAULT.centreHz),
        ListenArea("lpd433", 433_075_000, 434_775_000, "fm", 25_000, 433_075_000.0, originHz = 433_075_000.0, receiveOnly = false),
        ListenArea("pmr446", 446_006_250, 446_193_750, "fm", 12_500, 446_006_250.0, originHz = 446_006_250.0),
    )

    fun byId(id: String): ListenArea? = ALL.firstOrNull { it.id == id }

    /**
     * Where a press on [area] goes, with the server's [modes] (`api/spec`
     * choices): the area's mode when the server offers it (or says nothing
     * of its modes), else its fallback, else the mode is left alone.
     */
    fun target(area: ListenArea, modes: List<String>): AreaSpot {
        val mode = area.mode?.let { m -> if (modes.isEmpty() || m in modes) m else area.fallback?.takeIf { it in modes } }
        return AreaSpot(area.spotHz, mode, area.stepHz)
    }

    /**
     * The origin a step of [stepHz] counts from at [dialHz]: the channel
     * raster of the area the dial is in when the step is that area's own,
     * else 0 Hz (the plain grid every other step uses).
     */
    fun origin(dialHz: Double?, stepHz: Int): Double {
        val f = dialHz ?: return 0.0
        return ALL.firstOrNull { f in it && it.stepHz == stepHz }?.originHz ?: 0.0
    }
}

/** One DAB Band III block (ETSI EN 300 401 table): its label and its centre, which is the dial. */
data class DabBlock(val label: String, val centreHz: Double)

object DabBlocks {
    /** ETSI EN 300 401 Band III, kHz: 5A-13F, with the 10N/11N/12N blocks of the same table. */
    val ALL: List<DabBlock> = listOf(
        "5A" to 174_928, "5B" to 176_640, "5C" to 178_352, "5D" to 180_064,
        "6A" to 181_936, "6B" to 183_648, "6C" to 185_360, "6D" to 187_072,
        "7A" to 188_928, "7B" to 190_640, "7C" to 192_352, "7D" to 194_064,
        "8A" to 195_936, "8B" to 197_648, "8C" to 199_360, "8D" to 201_072,
        "9A" to 202_928, "9B" to 204_640, "9C" to 206_352, "9D" to 208_064,
        "10A" to 209_936, "10N" to 210_096, "10B" to 211_648, "10C" to 213_360, "10D" to 215_072,
        "11A" to 216_928, "11N" to 217_088, "11B" to 218_640, "11C" to 220_352, "11D" to 222_064,
        "12A" to 223_936, "12N" to 224_096, "12B" to 225_648, "12C" to 227_360, "12D" to 229_072,
        "13A" to 230_784, "13B" to 232_496, "13C" to 234_208, "13D" to 235_776, "13E" to 237_488, "13F" to 239_200,
    ).map { (l, khz) -> DabBlock(l, khz * 1000.0) }

    /** 11D: the UK's national BBC multiplex, so a first press in the UK hears something. */
    val DEFAULT: DabBlock = ALL.first { it.label == "11D" }

    fun byLabel(label: String): DabBlock? = ALL.firstOrNull { it.label.equals(label, ignoreCase = true) }

    /** The block whose centre is within half a block (768 kHz) of [hz]; null off the raster. */
    fun at(hz: Double?): DabBlock? = hz?.let { f -> ALL.minByOrNull { kotlin.math.abs(it.centreHz - f) }?.takeIf { kotlin.math.abs(it.centreHz - f) < 768_000 } }

    /** The block [by] along from [from], held at the ends of the table. */
    fun step(from: DabBlock, by: Int): DabBlock = ALL[(ALL.indexOf(from).coerceAtLeast(0) + by).coerceIn(0, ALL.size - 1)]
}

/** Whole Hz, as the server is sent an 8.33 kHz channel (RADIO-LISTEN-CONTRACT §2: never rounded to a coarser grid). */
internal fun wholeHz(hz: Double): Double = hz.roundToLong().toDouble()
