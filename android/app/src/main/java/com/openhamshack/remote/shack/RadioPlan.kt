package com.openhamshack.remote.shack

import org.json.JSONArray
import org.json.JSONObject

// The band plan along Radio's waterfall (Brian, 13/10/2026: "the band plan
// overlay on the radio app", as Flex has). Flex's module sends its segments
// already classified (flex app/bandplan.py); Radio's server sends only the
// segment under the dial and the plan's id (`segment`, `planInUse`), so
// Remote reads the plan itself - from the shared dataset, never a table
// typed in here (BETA-PLAN arch change 5, openhamshack
// docs/contract/BANDPLAN-DATA.md). The files in assets/bandplan/ are
// ohs-libs data/bandplan copied byte for byte by tools/sync-bandplan.sh,
// with the commit and dataVersion in assets/bandplan/SOURCE.
//
// The classification and colours are Flex's (flex app/bandplan.py kind_of,
// COLOURS, _innermost), so a segment reads the same on both faces; they are
// copied rather than shared because Flex's strip belongs to another session
// and must not move (FlexPlanStrip). Pure, so it is tested on the JVM
// against the shipped data.

/** One row of a plan: [loHz]..[hiHz], the plan's own [label], its mode classes, and `kind` ("", beacon, satellite, protected, output). */
data class PlanSegment(val loHz: Long, val hiHz: Long, val label: String, val modes: Set<String>, val kind: String)

/** A plan's centre of activity (`centres`): a frequency, the plan's own name for it ("FM calling") and its mode. */
data class PlanCentre(val hz: Long, val label: String, val mode: String)

/** One plan file (`plans/<id>.json`): its id, IARU region, rows and centres of activity. */
data class BandPlan(val id: String, val region: Int, val segments: List<PlanSegment>, val centres: List<PlanCentre> = emptyList()) {
    companion object {
        fun parse(o: JSONObject): BandPlan {
            val a = o.optJSONArray("segments") ?: JSONArray()
            val segs = (0 until a.length()).mapNotNull { i ->
                val s = a.optJSONObject(i) ?: return@mapNotNull null
                val lo = s.optLong("loHz", -1)
                val hi = s.optLong("hiHz", -1)
                if (lo < 0 || hi <= lo) return@mapNotNull null
                // `modes` is null on a row that names none (all modes): kind_of calls that "all".
                val m = s.optJSONArray("modes")
                PlanSegment(lo, hi, s.optString("label"), (0 until (m?.length() ?: 0)).map { m!!.optString(it) }.toSet(),
                    if (s.isNull("kind")) "" else s.optString("kind"))
            }.sortedBy { it.loHz }
            val c = o.optJSONArray("centres") ?: JSONArray()
            val centres = (0 until c.length()).mapNotNull { i ->
                val x = c.optJSONObject(i) ?: return@mapNotNull null
                PlanCentre(x.optLong("hz", -1), x.optString("label"), x.optString("mode")).takeIf { it.hz > 0 }
            }
            return BandPlan(o.optString("id"), o.optInt("region", 1), segs, centres)
        }
    }
}

/** A band as the dataset names it (vocab.json `bands`): "20m", 14 000 000..14 350 000. */
data class PlanBand(val name: String, val loHz: Long, val hiHz: Long) {
    operator fun contains(hz: Double) = hz >= loHz && hz <= hiHz
}

/**
 * The dataset's vocabulary that Remote reads: the bands (the widest
 * allocation in any region, so "which band is this?" has one answer
 * everywhere) and each engine mode's class (`ft8` -> `digi`).
 */
data class PlanVocab(val bands: List<PlanBand>, val modeClass: Map<String, String>) {
    fun band(name: String): PlanBand? = bands.firstOrNull { it.name == name }
    fun bandAt(hz: Double?): PlanBand? = hz?.let { f -> bands.firstOrNull { f in it } }
    /** A mode the dataset does not know has no class (GUIDE 5): not digital. */
    fun isDigital(mode: String): Boolean = modeClass[mode.lowercase()] == "digi"

    companion object {
        fun parse(o: JSONObject): PlanVocab {
            val a = o.optJSONArray("bands") ?: JSONArray()
            val bands = (0 until a.length()).mapNotNull { i ->
                val b = a.optJSONObject(i) ?: return@mapNotNull null
                PlanBand(b.optString("name"), b.optLong("loHz"), b.optLong("hiHz")).takeIf { it.hiHz > it.loHz && it.name.isNotEmpty() }
            }
            val mc = o.optJSONObject("modeClass") ?: JSONObject()
            return PlanVocab(bands, mc.keys().asSequence().associateWith { mc.optString(it) })
        }
    }
}

/** What the strip draws for one stretch: where, and in which of the strip's words ([kind]) and colours. */
data class PlanStripSegment(val loHz: Long, val hiHz: Long, val kind: String, val colour: Int, val label: String)

/** Every plan Remote carries, and the vocabulary. */
class PlanData(val vocab: PlanVocab, val plans: Map<String, BandPlan>) {
    /**
     * The plan for [planId] (Radio's `planInUse`), else IARU Region 1's: a
     * server too old to say still gets a strip, and Region 1 is what the
     * dataset itself falls back to for a station that has said nothing.
     */
    fun plan(planId: String?): BandPlan? = plans[planId] ?: plans[IARU_R1]

    /**
     * The rows that speak for [band]: the plan in force's, or where it has
     * nothing there its region's IARU plan's - as Flex's module fills a band
     * (flex app/bands.py _plan_segments). RSGB 2026 has no 6 m rows; the
     * IARU R1 plan does, and a blank strip there would read as "no plan".
     */
    fun rowsFor(planId: String?, band: PlanBand): List<PlanSegment> {
        val p = plan(planId) ?: return emptyList()
        val own = p.segments.filter { it.hiHz > band.loHz && it.loHz < band.hiHz }
        if (own.isNotEmpty()) return own
        val regional = plans["iaru-r${p.region}"] ?: return emptyList()
        return regional.segments.filter { it.hiHz > band.loHz && it.loHz < band.hiHz }
    }

    /** The plan in force's centres of activity in [band], else its region's IARU plan's (as [rowsFor]). */
    fun centresFor(planId: String?, band: PlanBand): List<PlanCentre> {
        val p = plan(planId) ?: return emptyList()
        val own = p.centres.filter { it.hz >= band.loHz && it.hz <= band.hiHz }
        if (own.isNotEmpty()) return own
        return plans["iaru-r${p.region}"]?.centres.orEmpty().filter { it.hz >= band.loHz && it.hz <= band.hiHz }
    }

    /** The strip's segments over [lowHz]..[highHz]: every band in sight, each from [rowsFor], innermost first. */
    fun strip(planId: String?, lowHz: Double, highHz: Double): List<PlanStripSegment> {
        if (highHz <= lowHz) return emptyList()
        val out = ArrayList<PlanStripSegment>()
        val seen = HashSet<Triple<Long, Long, String>>()
        for (band in vocab.bands) {
            if (band.hiHz < lowHz || band.loHz > highHz) continue
            for (s in RadioPlanStrip.innermost(rowsFor(planId, band))) {
                val kind = RadioPlanStrip.kindOf(s)
                if (!seen.add(Triple(s.loHz, s.hiHz, kind))) continue
                out += PlanStripSegment(s.loHz, s.hiHz, kind, RadioPlanStrip.colour(kind), s.label)
            }
        }
        return out.sortedBy { it.loHz }
    }

    companion object {
        const val IARU_R1 = "iaru-r1"
        /** The plan files assets/bandplan/plans carries (manifest.json `plans`). */
        val PLAN_IDS = listOf("iaru-r1", "iaru-r2", "iaru-r3", "rsgb-2026")
    }
}

object RadioPlanStrip {
    // Flex's colours (flex app/bandplan.py): by what a segment is for, so a
    // plan read at a glance is read by colour and only then by the words.
    private const val CW = 0xFF3060FF.toInt()
    private const val DATA = 0xFFC03030.toInt()
    private const val PHONE = 0xFFFF8000.toInt()
    private const val BEACON = 0xFF00D0D0.toInt()
    private const val SPECIAL = 0xFFFFD000.toInt()
    private const val SATELLITE = 0xFF9030C0.toInt()
    private const val FM = 0xFF30B030.toInt()

    fun colour(kind: String): Int = when (kind) {
        "cw" -> CW
        "data" -> DATA
        "phone", "all" -> PHONE
        "beacon" -> BEACON
        "satellite" -> SATELLITE
        "repeater", "image" -> SPECIAL
        "fm" -> FM
        else -> 0xFF808080.toInt()
    }

    /**
     * What a row is for, in the strip's few words: decided by its modes,
     * which the dataset states, not its label, which is a sentence. Flex's
     * kind_of rule for rule, so the two faces agree on every segment.
     */
    fun kindOf(s: PlanSegment): String {
        when (s.kind) {
            "beacon" -> return "beacon"
            "satellite" -> return "satellite"
            "output" -> return "repeater"
        }
        val m = s.modes
        return when {
            m.isEmpty() -> "all"
            m == setOf("cw") -> "cw"
            "ssb" !in m && "am" !in m && (m.contains("digi") || m.contains("cw")) -> "data"
            m.all { it == "fm" || it == "dv" } -> "fm"
            m.all { it == "image" || it == "atv" || it == "datv" } -> "image"
            "cw" in m && "digi" in m && "ssb" in m -> "all"
            else -> "phone"
        }
    }

    /**
     * Each row shortened around any narrower row inside it, because a
     * ribbon cannot show two things in one place (flex _innermost): IARU
     * R1's 14.099-14.101 beacons sit inside nothing, but 2 m's beacon and
     * satellite sub-segments sit inside the all-mode rows around them.
     */
    fun innermost(rows: List<PlanSegment>): List<PlanSegment> {
        val out = ArrayList<PlanSegment>()
        for (seg in rows) {
            val inner = rows.filter { it !== seg && it.loHz >= seg.loHz && it.hiHz <= seg.hiHz && (it.hiHz - it.loHz) < (seg.hiHz - seg.loHz) }
                .map { it.loHz to it.hiHz }.sortedBy { it.first }
            var at = seg.loHz
            for ((a, b) in inner) {
                if (a > at) out += seg.copy(loHz = at, hiHz = a)
                at = maxOf(at, b)
            }
            if (at < seg.hiHz) out += seg.copy(loHz = at)
        }
        return out.sortedBy { it.loHz }
    }

    /** One piece of the strip in pixels; [label] when there is room for its word. */
    data class Piece(val x: Float, val w: Float, val kind: String, val colour: Int, val label: Boolean)

    /**
     * [segments] over [lowHz]..[highHz] drawn [widthPx] wide, each at least a
     * pixel so a narrow beacon segment does not vanish at a wide span; a
     * label only where [labelPx] fits (FlexPlanStrip's layout, copied).
     */
    fun layout(segments: List<PlanStripSegment>, lowHz: Double, highHz: Double, widthPx: Float, labelPx: Float): List<Piece> {
        val span = highHz - lowHz
        if (span <= 0 || widthPx <= 0) return emptyList()
        return segments.filter { it.hiHz >= lowHz && it.loHz <= highHz }.map { s ->
            val lo = maxOf(s.loHz.toDouble(), lowHz)
            val hi = minOf(s.hiHz.toDouble(), highHz)
            val x = ((lo - lowHz) / span * widthPx).toFloat()
            val w = maxOf(((hi - lo) / span * widthPx).toFloat(), 1f)
            Piece(x, w, s.kind, s.colour, w >= labelPx)
        }
    }
}
