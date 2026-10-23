package com.openhamshack.remote.shack

import org.json.JSONObject

// Radio's local frequencies (Brian approved TvPolishLocal, 23/10/2026):
// what is on the air near the station - airports, area control, marine,
// PMR · CB · LPD, the HF beacons - and the operator's own, nearest first,
// OK tuning with the mode and the step. Radio serves it as
// `api/local-frequencies` (openhamshack-nb SERVER-API "Local frequencies",
// fb64699); every field is still read defensively, so a missing one leaves
// its part of the screen out rather than the screen.

/** One frequency. [id] is the server's; "mine" ones need it to be changed or removed. */
data class LocalEntry(
    val id: String?,
    val category: String,
    val name: String,
    val sub: String,
    val freqHz: Double,
    val mode: String?,
    val stepHz: Int?,
    val distanceKm: Double?,
    val bearing: Double?,
    val source: String,
    /** The operator's own grouping of one of theirs ("repeater"), and their notes. */
    val userCategory: String = "",
    val notes: String = "",
    val locator: String = "",
) {
    val mine: Boolean get() = category == LocalFreqs.MINE
}

/** A source's attribution, which the licences require shown wherever the list is: its words, licence and address as plain text. */
data class LocalAttribution(val source: String, val text: String, val licence: String, val url: String)

/** One NCDXF beacon frequency (Hz). */
data class BeaconBand(val freqHz: Double)

/** Who is on one beacon frequency now. */
data class BeaconNow(val band: BeaconBand, val call: String, val location: String)

/**
 * The NCDXF/IARU beacons as Radio describes them (`ncdxf`): the five
 * frequencies, the 18 beacons in slot order, who was on each frequency at
 * [atMillis] (server time), and the project's credit. [skewMillis] is the
 * server's clock less this device's when the list was read, so the card
 * keeps time with the server between reads.
 */
data class Beacons(
    val bands: List<BeaconBand>,
    val schedule: List<Pair<String, String>>,
    val now: List<BeaconNow>,
    val atMillis: Long?,
    val credit: String,
    val skewMillis: Long = 0,
)

data class LocalList(
    val radiusKm: Double?,
    val locator: String,
    val attributions: List<LocalAttribution>,
    val entries: List<LocalEntry>,
    val beacons: Beacons?,
    /** The server's sentence when it cannot list what is near (no locator in the station profile). */
    val why: String = "",
)

/** Where OK on an entry goes: the dial, the mode (null: leave it) and the step (null: leave it). */
data class LocalSpot(val hz: Double, val mode: String?, val stepHz: Int?)

/** The screen's chips (TvPolishLocal), each one or more of Radio's categories; every chip shows, empty or not. */
enum class LocalChip(val categories: Set<String>?) {
    ALL(null),
    AIRPORTS(setOf(LocalFreqs.AIRBAND)),
    AREA(setOf(LocalFreqs.ENROUTE)),
    MARINE(setOf(LocalFreqs.MARINE)),
    PMR(setOf(LocalFreqs.PMR, LocalFreqs.CB, LocalFreqs.LPD)),
    BEACONS(setOf(LocalFreqs.BEACON)),
    MINE(setOf(LocalFreqs.MINE)),
}

object LocalFreqs {
    const val AIRBAND = "airband"
    const val ENROUTE = "enroute"
    const val MARINE = "marine"
    const val PMR = "pmr"
    const val CB = "cb"
    const val LPD = "lpd"
    const val BEACON = "beacon"
    const val MINE = "mine"

    /** The radii the range chip offers, km; the server's setting is `localRadiusKm`, 100 by default. */
    val RADII = listOf(25, 50, 100, 200, 400)

    /** The next radius after [km] (wrapping), so OK on the TV's range chip walks them. */
    fun nextRadius(km: Double?): Int = RADII.firstOrNull { it > (km ?: 0.0) + 0.5 } ?: RADII.first()

    /**
     * `GET api/local-frequencies`, read defensively: an entry with no name or
     * no usable frequency is dropped; the rest kept in the server's order of
     * categories, nearest first inside each. [localMillis] is this device's
     * clock as the answer came, for the beacons' time.
     */
    fun parse(o: JSONObject, localMillis: Long = System.currentTimeMillis()): LocalList {
        val a = o.optJSONArray("entries")
        val entries = (0 until (a?.length() ?: 0)).mapNotNull { i ->
            val e = a!!.optJSONObject(i) ?: return@mapNotNull null
            val hz = e.num("freqHz")?.takeIf { it > 0 } ?: return@mapNotNull null
            val name = e.str("name") ?: return@mapNotNull null
            LocalEntry(e.str("id"), e.str("category") ?: "", name, e.str("sub").orEmpty(), hz, e.str("mode")?.lowercase(),
                e.num("step")?.toInt()?.takeIf { it > 0 }, e.num("distanceKm")?.takeIf { it >= 0 }, e.num("bearingDeg") ?: e.num("bearing"),
                e.str("source").orEmpty(), e.str("userCategory").orEmpty(), e.str("notes").orEmpty(), e.str("locator").orEmpty())
        }
        val att = o.optJSONArray("attributions")
        val attributions = (0 until (att?.length() ?: 0)).mapNotNull { i ->
            val x = att!!.optJSONObject(i)
            // An object as Radio sends it; a plain line as the first fake did. An empty one is no attribution.
            if (x != null) LocalAttribution(x.str("source").orEmpty(), x.str("text").orEmpty(), x.str("licence").orEmpty(), x.str("url").orEmpty())
                .takeIf { it.text.isNotEmpty() || it.source.isNotEmpty() }
            else att.optString(i).trim().takeIf { it.isNotEmpty() }?.let { LocalAttribution("", it, "", "") }
        }
        return LocalList(
            radiusKm = o.num("radiusKm")?.takeIf { it > 0 },
            locator = o.str("locator").orEmpty(),
            attributions = attributions,
            entries = byCategory(entries),
            beacons = o.optJSONObject("ncdxf")?.let { beacons(it, localMillis) },
            why = o.str("why").orEmpty(),
        )
    }

    private fun beacons(n: JSONObject, localMillis: Long): Beacons? {
        val f = n.optJSONArray("frequencies") ?: return null
        val bands = (0 until f.length()).mapNotNull { i -> f.optDouble(i, Double.NaN).takeIf { it.isFinite() && it > 0 }?.let { BeaconBand(it) } }
        if (bands.isEmpty()) return null
        val s = n.optJSONArray("schedule")
        val schedule = (0 until (s?.length() ?: 0)).mapNotNull { i ->
            s!!.optJSONObject(i)?.let { x -> x.str("call")?.let { it to x.str("location").orEmpty() } }
        }
        val nowObj = n.optJSONObject("now")
        val now = bands.mapNotNull { b ->
            val x = nowObj?.optJSONObject(Math.round(b.freqHz).toString()) ?: return@mapNotNull null
            BeaconNow(b, x.str("call") ?: return@mapNotNull null, x.str("location").orEmpty())
        }
        val at = n.str("at")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
        return Beacons(bands, schedule, now, at, n.str("credit").orEmpty(), at?.let { it - localMillis } ?: 0)
    }

    /** Radio's order of categories, then nearest first inside each, those with no position (the channel tables) after them. */
    private val ORDER = listOf(AIRBAND, ENROUTE, MARINE, PMR, CB, LPD, BEACON, MINE)

    fun byCategory(list: List<LocalEntry>): List<LocalEntry> = list.sortedWith(
        compareBy({ ORDER.indexOf(it.category).let { i -> if (i < 0) ORDER.size else i } }, { it.distanceKm == null }, { it.distanceKm ?: 0.0 }))

    /** The entries under [chip]; an unknown category is listed under All only. */
    fun filter(list: LocalList, chip: LocalChip): List<LocalEntry> {
        val cats = chip.categories ?: return list.entries
        return list.entries.filter { it.category in cats }
    }

    /**
     * The NCDXF beacon on each frequency at [deviceMillis], in server time
     * (the device's clock plus the skew measured at the read): each beacon
     * sends 10 s on a frequency and moves up one, so beacon i is on frequency
     * k in slot (i + k) mod 18 of the 3-minute cycle - the card changes every
     * 10 s without asking the server. The server's own `now` when there is no
     * whole schedule to work from.
     */
    fun beaconsNow(b: Beacons, deviceMillis: Long): List<BeaconNow> {
        if (b.schedule.size != NCDXF_SLOTS) return b.now
        val t = deviceMillis + b.skewMillis
        val slot = (Math.floorMod(t / 1000, 180L) / 10).toInt()
        return b.bands.mapIndexed { k, band ->
            val (call, location) = b.schedule[Math.floorMod(slot - k, NCDXF_SLOTS)]
            BeaconNow(band, call, location)
        }
    }

    /** Milliseconds until the next slot change, in server time: when the card must say who is on next. */
    fun msToNextSlot(b: Beacons?, deviceMillis: Long): Long = 10_000 - Math.floorMod(deviceMillis + (b?.skewMillis ?: 0), 10_000L)

    private const val NCDXF_SLOTS = 18

    /**
     * Where OK on [e] goes, with the server's [modes] (the offered ones): its
     * mode when the server offers it (or says nothing of its modes), else the
     * mode is left alone; its step when Remote has that step.
     */
    fun target(e: LocalEntry, modes: List<String>): LocalSpot =
        LocalSpot(e.freqHz, e.mode?.takeIf { modes.isEmpty() || it in modes }, e.stepHz?.takeIf { it in Tuning.RADIO_STEPS })

    /** A beacon is tuned in CW. */
    fun target(b: BeaconBand, modes: List<String>): LocalSpot = LocalSpot(b.freqHz, "cw".takeIf { modes.isEmpty() || it in modes }, null)

    /**
     * `POST api/local-frequencies/mine`'s body: only the fields Radio takes
     * (name, freqHz, mode, and category, locator, sub, notes when given) -
     * any other is refused 400 bad_field, so nothing more is ever sent.
     */
    fun mineBody(name: String, hz: Double, mode: String, category: String? = null, locator: String? = null, sub: String? = null,
                 notes: String? = null): JSONObject = JSONObject().put("name", name.trim()).put("freqHz", Math.round(hz)).put("mode", mode).apply {
        category?.trim()?.takeIf { it.isNotEmpty() }?.let { put("category", it) }
        locator?.trim()?.takeIf { it.isNotEmpty() }?.let { put("locator", it) }
        sub?.trim()?.takeIf { it.isNotEmpty() }?.let { put("sub", it) }
        notes?.trim()?.takeIf { it.isNotEmpty() }?.let { put("notes", it) }
    }

    /** `PUT api/local-frequencies/mine/{id}`'s body: only what changed from [was]. */
    fun mineChanges(was: LocalEntry, name: String, hz: Double): JSONObject = JSONObject().apply {
        if (name.trim() != was.name) put("name", name.trim())
        if (Math.round(hz) != Math.round(was.freqHz)) put("freqHz", Math.round(hz))
    }

    /** One of eight compass points for a bearing in degrees ("N", "NE", ... "NW"); null without one. */
    fun compass(bearing: Double?): Compass? {
        val b = bearing?.takeIf { it.isFinite() } ?: return null
        return Compass.entries[Math.floorMod(Math.round(b / 45.0).toInt(), 8)]
    }

    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).trim().takeIf { it.isNotEmpty() }
}

/** The eight compass points, the screen's words for each in its catalog. */
enum class Compass { N, NE, E, SE, S, SW, W, NW }
