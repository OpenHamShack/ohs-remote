package com.openhamshack.kind.stationflow.api

import org.json.JSONObject

/*
 * D-STAR, as the Icom module reports what the radio decodes in DV mode
 * (TV-API.md 9 "D-STAR": `radios[<id>].dv` and `dv_heard`; Brian, 10
 * October 2026: "when in DV mode can you pass through the call sign to the
 * remote app"). The radio's own RX CALL SIGN display shows the caller and
 * suffix, the RX message and the repeater it came through; the face does
 * the same. A module without D-STAR sends neither key, and the face then
 * shows nothing extra.
 */

/** One D-STAR over: who, through what, saying what, when. */
data class DvCall(
    val caller: String,
    val suffix: String? = null,
    val ur: String? = null,
    val rpt1: String? = null,
    val rpt2: String? = null,
    val message: String? = null,
    /** Unix seconds, the start of the over. */
    val heardAt: Double? = null,
    /** A voice call is coming in now (only on the latest). */
    val receiving: Boolean = false,
) {
    /** "M0XYZ /M" as the radio prints caller and suffix. */
    val callLine: String get() = caller + (suffix?.let { " " + if (it.startsWith("/")) it else "/$it" } ?: "")

    /**
     * The repeater it came through, when it came through one: RPT1, an
     * 8-character D-STAR field whose inner spaces separate the repeater
     * from its module ("GB7DV  B" is GB7DV, module B), shown with one space.
     * Blank or "DIRECT" is simplex, and then there is nothing to say.
     */
    val via: String? get() = rpt1?.trim()?.replace(Regex("\\s+"), " ")
        ?.takeIf { it.isNotBlank() && !it.equals("DIRECT", ignoreCase = true) }

    companion object {
        fun parse(o: JSONObject?, timeKey: String = "heard_at"): DvCall? {
            o ?: return null
            val caller = o.optStringOrNull("caller")?.trim()?.ifBlank { null } ?: return null
            return DvCall(
                caller = caller,
                suffix = o.optStringOrNull("suffix")?.trim()?.ifBlank { null },
                ur = o.optStringOrNull("ur")?.trim()?.ifBlank { null },
                rpt1 = o.optStringOrNull("rpt1")?.ifBlank { null },
                rpt2 = o.optStringOrNull("rpt2")?.ifBlank { null },
                message = o.optStringOrNull("message")?.trim()?.ifBlank { null },
                heardAt = o.optDoubleOrNull(timeKey) ?: o.optDoubleOrNull("heard_at"),
                receiving = o.optBoolean("receiving"),
            )
        }
    }
}

/** The radio's D-STAR side: the latest caller (null until one), and the last overs, newest first. */
data class DvState(val last: DvCall?, val heard: List<DvCall>) {
    companion object {
        /** From a radio's entry: null when the module sends neither `dv` nor `dv_heard` (no D-STAR). */
        fun parse(r: JSONObject): DvState? {
            if (!r.has("dv") && !r.has("dv_heard")) return null
            val last = DvCall.parse(r.optJSONObject("dv"))
            val heard = r.optJSONArray("dv_heard").objects().mapNotNull { DvCall.parse(it, timeKey = "at") }
            return DvState(last, heard)
        }
    }
}
