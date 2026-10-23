package com.openhamshack.remote.shack

import org.json.JSONObject
import kotlin.math.floor

// Radio's local frequencies (Brian approved TvPolishLocal, 23/10/2026):
// what is on the air near the station - airports, area control, marine,
// PMR · CB · LPD, HF beacons - and the operator's own, nearest first, OK
// tuning with the mode and the step. RADIO-LISTEN-CONTRACT §5 has the shape
// Remote expects; Radio's API is not out, so every field is read defensively
// (a missing one leaves its part of the screen out, an unknown category is
// still listed) and the names may change.

/** One frequency: [id] is the server's (needed to change or remove one of the operator's own). */
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
) {
    val mine: Boolean get() = category == LocalFreqs.MINE
}

/** An NCDXF beacon band: its name ("20m") and the beacons' frequency there. */
data class BeaconBand(val band: String, val freqHz: Double)

/** Who is on one band now. */
data class BeaconNow(val band: BeaconBand, val call: String, val location: String)

/** The NCDXF/IARU beacons as the server describes them: the bands, the 18 calls in schedule order, where each is. */
data class Beacons(val bands: List<BeaconBand>, val schedule: List<String>, val locations: Map<String, String>, val now: List<BeaconNow>)

data class LocalList(
    val radiusKm: Double?,
    val locator: String,
    val attributions: List<String>,
    val entries: List<LocalEntry>,
    val beacons: Beacons?,
)

/** Where OK on an entry goes: the dial, the mode (null: leave it) and the step (null: leave it). */
data class LocalSpot(val hz: Double, val mode: String?, val stepHz: Int?)

object LocalFreqs {
    const val AIRPORT = "airport"
    const val AREA = "area-control"
    const val MARINE = "marine"
    const val PMR = "pmr"
    const val BEACON = "beacon"
    const val MINE = "mine"

    /** The chips' order (TvPolishLocal); any other category the server sends follows, in its own order. */
    val ORDER = listOf(AIRPORT, AREA, MARINE, PMR, BEACON, MINE)

    /** `GET api/local`, read defensively: an entry with no name or no usable frequency is dropped; the rest kept nearest first. */
    fun parse(o: JSONObject): LocalList {
        val a = o.optJSONArray("entries")
        val entries = (0 until (a?.length() ?: 0)).mapNotNull { i ->
            val e = a!!.optJSONObject(i) ?: return@mapNotNull null
            val hz = e.num("freqHz")?.takeIf { it > 0 } ?: return@mapNotNull null
            val name = e.str("name") ?: return@mapNotNull null
            LocalEntry(e.str("id"), e.str("category") ?: "", name, e.str("sub").orEmpty(), hz, e.str("mode")?.lowercase(),
                e.num("step")?.toInt()?.takeIf { it > 0 }, e.num("distanceKm")?.takeIf { it >= 0 }, e.num("bearing"), e.str("source").orEmpty())
        }
        val att = o.optJSONArray("attributions")
        return LocalList(
            radiusKm = o.num("radiusKm")?.takeIf { it > 0 },
            locator = o.str("locator").orEmpty(),
            attributions = (0 until (att?.length() ?: 0)).mapNotNull { att!!.optString(it).trim().ifEmpty { null } },
            entries = nearestFirst(entries),
            beacons = o.optJSONObject("ncdxf")?.let { beacons(it) },
        )
    }

    private fun beacons(n: JSONObject): Beacons? {
        val b = n.optJSONArray("bands") ?: return null
        val bands = (0 until b.length()).mapNotNull { i ->
            val x = b.optJSONObject(i) ?: return@mapNotNull null
            BeaconBand(x.str("band") ?: return@mapNotNull null, x.num("freqHz")?.takeIf { it > 0 } ?: return@mapNotNull null)
        }
        if (bands.isEmpty()) return null
        val s = n.optJSONArray("schedule")
        val schedule = (0 until (s?.length() ?: 0)).mapNotNull { s!!.optString(it).trim().ifEmpty { null } }
        val loc = n.optJSONObject("locations")
        val locations = loc?.keys()?.asSequence()?.associateWith { loc.optString(it) }.orEmpty()
        val nw = n.optJSONArray("now")
        val now = (0 until (nw?.length() ?: 0)).mapNotNull { i ->
            val x = nw!!.optJSONObject(i) ?: return@mapNotNull null
            val band = bands.firstOrNull { it.band == x.str("band") } ?: return@mapNotNull null
            val call = x.str("call") ?: return@mapNotNull null
            BeaconNow(band, call, locations[call].orEmpty())
        }
        return Beacons(bands, schedule, locations, now)
    }

    /** By distance, nearest first; those with none (a nationwide channel) after them, in the server's order. */
    fun nearestFirst(list: List<LocalEntry>): List<LocalEntry> = list.sortedWith(compareBy({ it.distanceKm == null }, { it.distanceKm ?: 0.0 }))

    /** The chips: the categories present, in [ORDER] then any others; "mine" always, so the first can be added. */
    fun categories(list: LocalList): List<String> {
        val present = list.entries.map { it.category }.filter { it.isNotEmpty() }.toSet() + MINE + (if (list.beacons != null) setOf(BEACON) else emptySet())
        return ORDER.filter { it in present } + present.filter { it !in ORDER }.sorted()
    }

    /** The entries under [category], or all of them for null. */
    fun filter(list: LocalList, category: String?): List<LocalEntry> = if (category == null) list.entries else list.entries.filter { it.category == category }

    /**
     * The NCDXF beacon on each band now, by the clock: the 18 beacons send
     * in a fixed 3-minute cycle of 10 s slots, beacon i on band b in slot
     * (i + b) mod 18, so who is on is known without asking the server again.
     * The server's own `now` when there is no schedule to work from.
     */
    fun beaconsNow(b: Beacons, utcMillis: Long): List<BeaconNow> {
        if (b.schedule.size != NCDXF_SLOTS) return b.now
        val slot = (floor((utcMillis / 1000.0) % 180.0 / 10.0).toInt()) % NCDXF_SLOTS
        return b.bands.mapIndexed { k, band ->
            val call = b.schedule[Math.floorMod(slot - k, NCDXF_SLOTS)]
            BeaconNow(band, call, b.locations[call].orEmpty())
        }
    }

    /** Milliseconds until the next slot change: when the card must say who is on next. */
    fun msToNextSlot(utcMillis: Long): Long = 10_000 - utcMillis % 10_000

    private const val NCDXF_SLOTS = 18

    /**
     * Where OK on [e] goes, with the server's [modes] (the offered ones): its
     * mode when the server offers it (or says nothing of its modes), else
     * the mode is left alone; its step when it gives one.
     */
    fun target(e: LocalEntry, modes: List<String>): LocalSpot =
        LocalSpot(e.freqHz, e.mode?.takeIf { modes.isEmpty() || it in modes }, e.stepHz?.takeIf { it in Tuning.RADIO_STEPS })

    /** A beacon is tuned in CW, on the 10 Hz grid. */
    fun target(b: BeaconBand, modes: List<String>): LocalSpot = LocalSpot(b.freqHz, "cw".takeIf { modes.isEmpty() || it in modes }, null)

    /** `POST api/local/mine` (or `/{id}`)'s body. */
    fun mineBody(name: String, hz: Double, mode: String, stepHz: Int?): JSONObject =
        JSONObject().put("name", name.trim()).put("freqHz", Math.round(hz)).put("mode", mode).apply { stepHz?.let { put("step", it) } }

    /** One of eight compass points for a bearing in degrees ("N", "NE", ... "NW"); null without one. */
    fun compass(bearing: Double?): Compass? {
        val b = bearing?.takeIf { it.isFinite() } ?: return null
        return Compass.entries[Math.floorMod(Math.round(b / 45.0).toInt(), 8)]
    }

    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).trim().takeIf { it.isNotEmpty() }
}

/** The eight compass points, the screen's words for each in its catalog. */
enum class Compass { N, NE, E, SE, S, SW, W, NW }
