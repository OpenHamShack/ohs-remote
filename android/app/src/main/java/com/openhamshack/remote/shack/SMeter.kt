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
            // Final shape (openhamshack-nb, 23/10/2026): `why` is a code (agc, between_bands,
            // no_calibration, extra_changed) and `whyText` the server's sentence for it, "" while
            // calibrated. The sentence is what is shown; a server from before that sent the
            // sentence in `why` itself, so `why` is the fallback only when `whyText` is absent.
            val why = if (o.has("whyText")) o.optString("whyText").trim().takeIf { !o.isNull("whyText") && it.isNotEmpty() }
                else o.optString("why").trim().takeIf { !o.isNull("why") && it.isNotEmpty() }
            return SMeter(s, dbm, why)
        }
    }
}

/**
 * Calibrating Radio's S-meter from Remote (SERVER-API `api/smeter/calibrate`):
 * "the signal now is this level". The one-press default is S9, which by the
 * IARU rule is -93 dBm above 30 MHz and -73 dBm below; any other level is
 * typed as dBm ("-85", "-85 dBm") or as an S reading ("S7", "S9+10", 6 dB
 * an S-unit). The server's own range check (-140..+30 dBm) is repeated here
 * so a typo is caught before it is sent.
 */
object SMeterCal {
    const val LO_DBM = -140.0
    const val HI_DBM = 30.0

    fun s9Dbm(dialHz: Double?): Double = if ((dialHz ?: 0.0) >= 30e6) -93.0 else -73.0

    /** The level [text] means at [dialHz], in dBm; null when it is not one, or out of range. */
    fun parseLevel(text: String, dialHz: Double?): Double? {
        val t = text.trim().uppercase().replace(" ", "").removeSuffix("DBM")
        val s = Regex("""^S(\d)(?:\+(\d+))?$""").find(t)
        val dbm = if (s != null) {
            val units = s.groupValues[1].toInt()
            val over = s.groupValues[2].toIntOrNull() ?: 0
            if (units < 9 && over > 0) return null
            s9Dbm(dialHz) - (9 - units) * 6.0 + over
        } else t.replace(',', '.').replace('\u2212', '-').toDoubleOrNull()
        return dbm?.takeIf { it.isFinite() && it in LO_DBM..HI_DBM }
    }
}
