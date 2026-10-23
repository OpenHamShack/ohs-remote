package com.openhamshack.remote.shack

import org.json.JSONArray
import org.json.JSONObject

// Radio's station lists and RDS (openhamshack-nb SERVER-API "Station lists"
// and "Wide FM"; Brian approved the FM picker, TvPolishFm, 23/10/2026).
// Radio scans the FM band and Band III by itself once a week, at night, and
// keeps one entry per programme; `POST api/scan` scans now. While it is
// tuned to a wide FM station, `rds` in state carries the station's own name,
// RadioText and programme type. Everything here is read defensively and
// kept out of Compose, so the JVM tests run the parsing, the grouping, the
// sorting and the choice of where a picker's list comes from.

/** A station's RDS as Radio publishes it (`rds`); each field only once stable. Null fields were not heard. */
data class Rds(
    val pi: String?,
    val ps: String?,
    val rt: String?,
    val pty: Int?,
    val ptyName: String?,
    val ta: Boolean,
    val tp: Boolean,
    val stereo: Boolean,
) {
    /** The station's name as shown: the 8 characters it sends, padding trimmed; null when it sent none. */
    val name: String? get() = ps?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Which traffic marker to show: TA only on a station that itself carries
     * traffic news (TP). A station can send TA without TP for another
     * programme's announcement (EON: real BBC Radio 3 sends ta true, tp
     * false), which is not this station's traffic news.
     */
    val traffic: Traffic? get() = when {
        ta && tp -> Traffic.TA
        tp -> Traffic.TP
        else -> null
    }

    companion object {
        /** Null for null or `{}` (no PI heard yet, outside wfm, a retune). */
        fun parse(o: JSONObject?): Rds? {
            if (o == null || o.length() == 0) return null
            // The PS is sent padded to 8 ("BBC R3  "): trimmed here, once, so nothing shows or sorts the padding.
            return Rds(o.textOf("pi"), o.textOf("ps"), o.textOf("rt"),
                o.num("pty")?.toInt(), o.textOf("ptyName"), o.optBoolean("ta"), o.optBoolean("tp"), o.optBoolean("stereo"))
        }
    }
}

/** RDS's traffic marker: TA (traffic news on now) or TP (this station carries it). */
enum class Traffic { TA, TP }

/** A band scan under way (`bandScan`): which band, how far, and Radio's own words for where it is. */
data class BandScan(val band: String, val percent: Int, val phase: String, val freqHz: Double?, val text: String) {
    companion object {
        /** Null for `{}` (no scan). */
        fun parse(o: JSONObject?): BandScan? {
            val band = o?.textOf("band") ?: return null
            return BandScan(band, (o.num("percent") ?: 0.0).toInt().coerceIn(0, 100), o.optString("phase"), o.num("freqHz"), o.optString("text").trim())
        }
    }
}

/** One frequency a station was heard on: its level (dBFS, relative) and whether its stereo pilot was. */
data class FmHeard(val hz: Double, val level: Double?, val stereo: Boolean)

/** One FM programme: its RDS identity (null for a station without RDS) and where it is best heard. */
data class FmStation(
    val pi: String?,
    val ps: String?,
    val pty: Int?,
    val ptyName: String?,
    val rt: String?,
    val best: FmHeard,
    val alts: List<FmHeard>,
) {
    /** The name shown: the PS, trimmed; null for a station without RDS (shown by its frequency). */
    val name: String? get() = ps?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * What a badge says for a station without RDS: its whole megahertz
     * ("103" for 103.3), since a name's initials would give "10" to 100.3,
     * 103.3 and 107.6 alike. Null for a named station (its initials).
     */
    val badgeText: String? get() = if (name != null) null else (best.hz / 1e6).toInt().toString()

    /** The key a badge's colour comes from: the PI, the same on every frequency and in every scan; the frequency for one without. */
    val badgeKey: String get() = pi ?: Math.round(best.hz).toString()
}

/** One DAB service from the scan. */
data class DabListed(val sid: String, val label: String, val kind: String, val bitrate: Int?)

/** One DAB ensemble from the scan: its block (the session's), centre, and services. */
data class DabEnsemble(val block: String, val freqHz: Double?, val id: String, val label: String, val services: List<DabListed>)

/** How a band's list stands (`status`): only `ok` has a list made by the last scan. */
enum class ListStatus { NEVER, SCANNING, OK, BUSY, ABORTED, UNSUPPORTED, FAILED, UNKNOWN;
    companion object {
        fun of(s: String): ListStatus = entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: UNKNOWN
    }
}

/** What a station list says about itself: when it was made, the next attempt, its status and the server's sentence. */
data class ListHeader(val scannedAt: String?, val nextDue: String?, val status: ListStatus, val statusText: String) {
    /**
     * Whether the header says when the list was made ("Station list from the
     * scan on ...") rather than the server's sentence: a list that is there
     * and not just now being remade, refused or failed.
     */
    val showsDates: Boolean get() = status == ListStatus.OK || (scannedAt != null && statusText.isEmpty())

    companion object {
        fun parse(o: JSONObject): ListHeader =
            ListHeader(o.textOf("scannedAt"), o.textOf("nextDue"), ListStatus.of(o.optString("status")), o.optString("statusText").trim())
    }
}

data class FmList(val header: ListHeader, val stations: List<FmStation>)
data class DabList(val header: ListHeader, val ensembles: List<DabEnsemble>)

/** How the FM picker orders its tiles (TvPolishFm's "A–Z · MHz"). */
enum class FmSort { AZ, MHZ }

/** Where the DAB picker's list comes from. */
enum class DabSource { SERVER, LOCAL_SCAN }

object Stations {
    const val FM = "fm"
    const val DAB = "dab"
    const val BOTH = "both"

    /** `GET api/fm/stations`; a station with no usable best frequency is dropped. */
    fun parseFm(o: JSONObject): FmList {
        val a = o.optJSONArray("stations")
        val stations = (0 until (a?.length() ?: 0)).mapNotNull { i ->
            val s = a!!.optJSONObject(i) ?: return@mapNotNull null
            val best = heard(s.optJSONObject("best")) ?: return@mapNotNull null
            FmStation(s.textOf("pi"), s.textOf("ps"), s.num("pty")?.toInt(), s.textOf("ptyName"),
                s.textOf("rt"), best, heardList(s.optJSONArray("alts")))
        }
        return FmList(ListHeader.parse(o), stations)
    }

    /** `GET api/dab/stations`; an ensemble with no block, or a service with no id, is dropped. */
    fun parseDab(o: JSONObject): DabList {
        val a = o.optJSONArray("ensembles")
        val ens = (0 until (a?.length() ?: 0)).mapNotNull { i ->
            val e = a!!.optJSONObject(i) ?: return@mapNotNull null
            val block = e.textOf("block") ?: return@mapNotNull null
            val sa = e.optJSONArray("services")
            val services = (0 until (sa?.length() ?: 0)).mapNotNull { j ->
                val s = sa!!.optJSONObject(j) ?: return@mapNotNull null
                val sid = s.textOf("sid") ?: return@mapNotNull null
                DabListed(sid, s.textOf("label") ?: sid, s.optString("kind"), s.num("bitrate")?.toInt()?.takeIf { it > 0 })
            }
            DabEnsemble(block, e.num("freqHz"), e.optString("id"), e.textOf("label") ?: block, services)
        }
        return DabList(ListHeader.parse(o), ens)
    }

    private fun heard(o: JSONObject?): FmHeard? {
        val hz = o?.num("hz")?.takeIf { it > 0 } ?: return null
        return FmHeard(hz, o.num("level"), o.optBoolean("stereo"))
    }

    private fun heardList(a: JSONArray?): List<FmHeard> = (0 until (a?.length() ?: 0)).mapNotNull { heard(a!!.optJSONObject(it)) }

    /** The genre chips: the programme types present, in the order first met in the list (by frequency), each once. */
    fun genres(list: FmList): List<String> = list.stations.sortedBy { it.best.hz }.mapNotNull { it.ptyName?.trim()?.takeIf { n -> n.isNotEmpty() } }.distinct()

    /**
     * The tiles for [genre] (null: all), sorted: A–Z by name, the stations
     * without a name after the named ones by frequency; or by frequency.
     */
    fun tiles(list: FmList, genre: String?, sort: FmSort): List<FmStation> {
        val chosen = list.stations.filter { genre == null || it.ptyName?.trim() == genre }
        return when (sort) {
            FmSort.MHZ -> chosen.sortedBy { it.best.hz }
            FmSort.AZ -> chosen.sortedWith(compareBy<FmStation>({ it.name == null }, { it.name?.lowercase() ?: "" }, { it.best.hz }))
        }
    }

    /** Which listed station is on the air at [dialHz]: its best frequency or one of its alternatives, within 50 kHz. */
    fun playing(list: FmList?, dialHz: Double?, pi: String?): FmStation? {
        val stations = list?.stations ?: return null
        pi?.let { p -> stations.firstOrNull { it.pi == p }?.let { return it } }
        val f = dialHz ?: return null
        return stations.firstOrNull { s -> (listOf(s.best) + s.alts).any { kotlin.math.abs(it.hz - f) < 50_000 } }
    }

    /**
     * Where the DAB picker's list comes from: the server's own (its weekly
     * scan) whenever it has one to say anything about, and Remote's own
     * block-by-block scan only when the server has never scanned, cannot
     * (`unsupported`), or has no such list at all (an older Radio: [missing],
     * a 404).
     */
    fun dabSource(list: DabList?, missing: Boolean): DabSource = when {
        missing -> DabSource.LOCAL_SCAN
        list == null -> DabSource.SERVER
        list.header.status == ListStatus.NEVER || list.header.status == ListStatus.UNSUPPORTED -> DabSource.LOCAL_SCAN
        else -> DabSource.SERVER
    }

    /** `POST api/scan`'s body. */
    fun scanBody(band: String): JSONObject = JSONObject().put("band", band)

    /** `stationsAt` from state: band -> when its list was made (null: never). */
    fun stationsAt(o: JSONObject?): Map<String, String?> =
        o?.keys()?.asSequence()?.associateWith { k -> o.textOf(k) }.orEmpty()

    /** A `stations` event's band ("fm", "dab"), null for anything else. */
    fun stationsEvent(o: JSONObject): String? = o.textOf("band")?.takeIf { o.optString("kind") == "stations" }
}

/** A key's text, trimmed; null when absent, null or blank. */
private fun JSONObject.textOf(k: String): String? = if (isNull(k)) null else optString(k).trim().takeIf { it.isNotEmpty() }
