package com.openhamshack.remote.shack

import org.json.JSONObject

// A calibrated S-meter (23/10/2026). Radio is adding a server-side S-meter
// calibration, and the events socket's `meter` item gains, beside today's
// uncalibrated `rx` (dBFS), an optional `dbm` (dBm at the antenna), `s` (the
// reading as a radio shows it, "S7" or "S9+12") and `why` (a sentence saying
// why there is no calibration). The names may still change, so they are
// read defensively here, in one place: a reading is shown only when `s` is a
// short piece of text, and anything odd falls back to today's bar.

/**
 * What the meter says beyond the bar: [s] the server's S reading, [dbm] its
 * level, [why] its sentence for having none. All null from a server that
 * sends none of them (every server before calibration).
 */
data class SMeter(val s: String? = null, val dbm: Double? = null, val why: String? = null) {
    /** The calibrated reading to show beside the bar, or null to show the bar alone. */
    val reading: String? get() = s

    /** Why there is no reading, for where an explanation fits; null while there is one. */
    val explanation: String? get() = if (s == null) why else null

    companion object {
        /** Longest S reading taken ("S9+60" is five characters): anything longer is not one. */
        private const val MAX_S = 8

        fun parse(o: JSONObject): SMeter {
            val s = o.optString("s").trim().takeIf { !o.isNull("s") && it.isNotEmpty() && it.length <= MAX_S && it.none { c -> c == '\n' } }
            val dbm = o.num("dbm")?.takeIf { it in -200.0..50.0 }
            val why = o.optString("why").trim().takeIf { !o.isNull("why") && it.isNotEmpty() }
            return SMeter(s, dbm, why)
        }
    }
}
