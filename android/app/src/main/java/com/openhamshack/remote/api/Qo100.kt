package com.openhamshack.remote.api

import org.json.JSONObject

// GET /api/qo100: the QO-100 wideband transponder as BATC's monitor at
// Goonhilly sees it (https://eshail.batc.org.uk/wb/), read and analysed by
// the box. The box holds the one connection to BATC and finds the signals
// itself, exactly as BATC's page does; an app only reads this answer and
// never connects to BATC. Polling it is what keeps the box's feed open
// (it closes 20 s after the last poll), so the app polls only while its
// QO-100 page is on screen. See docs/REMOTE-CONTRACT.md, "QO-100".

/** One signal the box found on the transponder (openhamshacklib/qo100.py qo100_detect). */
data class Qo100Signal(
    /** Downlink centre, MHz: rounded as BATC's page does (12.5 kHz below 0.7 MS, else 25 kHz). */
    val freqMHz: Double,
    val lowMHz: Double,
    val highMHz: Double,
    /** Measured width in BATC's symbol-rate classes, kS. */
    val symbolRateKs: Int,
    /** The standard rate nearest to it: what a receiver is tuned to. */
    val tuneSymbolRateKs: Int,
    /** Peak above the noise floor, dB (real dB; BATC's own page reads ~9% high). */
    val levelDb: Double,
    val beacon: Boolean,
    /** Stronger than BATC allows against the beacon. */
    val overPower: Boolean,
)

data class Qo100(
    /** dB above the floor, 461 points max-pooled in pairs, -3 at the bottom; empty before the first frame. */
    val spectrum: List<Float>,
    val startMHz: Double,
    val stopMHz: Double,
    val uplinkOffsetMHz: Double,
    val signals: List<Qo100Signal>,
    /** Something above the top of the band. */
    val outOfBand: Boolean,
    /** Seconds since the last frame from BATC; null before the first. */
    val age: Double?,
    val live: Boolean,
    /** Why there is no spectrum, in the box's words; "" when there is none. */
    val error: String,
    val source: String,
    val sourceUrl: String,
    /** symbol rate (kS) -> the band plan's channels. */
    val channels: Map<Int, List<Qo100Channel>>,
    val waterfall: Qo100WaterfallRows?,
) {
    companion object {
        fun parse(o: JSONObject): Qo100 {
            val spec = o.optJSONArray("spectrum")
            val channels = mutableMapOf<Int, List<Qo100Channel>>()
            o.optJSONObject("channels")?.let { ch ->
                ch.keys().forEach { k ->
                    val rate = k.toIntOrNull() ?: return@forEach
                    channels[rate] = ch.optJSONArray(k).objects().map {
                        Qo100Channel(it.optDouble("downlinkMHz"), it.optDouble("uplinkMHz"), it.optBoolean("preferred"))
                    }
                }
            }
            return Qo100(
                spectrum = if (spec == null) emptyList() else (0 until spec.length()).map { spec.optDouble(it, -3.0).toFloat() },
                startMHz = o.dbl("startMHz") ?: 10490.5,
                stopMHz = o.dbl("stopMHz") ?: 10499.5,
                uplinkOffsetMHz = o.dbl("uplinkOffsetMHz") ?: 8089.5,
                signals = o.optJSONArray("signals").objects().map {
                    Qo100Signal(
                        freqMHz = it.optDouble("freqMHz"),
                        lowMHz = it.optDouble("lowMHz"),
                        highMHz = it.optDouble("highMHz"),
                        symbolRateKs = it.optInt("symbolRateKs"),
                        tuneSymbolRateKs = it.optInt("tuneSymbolRateKs"),
                        levelDb = it.optDouble("levelDb", 0.0),
                        beacon = it.optBoolean("beacon"),
                        overPower = it.optBoolean("overPower"),
                    )
                },
                outOfBand = o.optBoolean("outOfBand"),
                age = o.dbl("age"),
                live = o.optBoolean("live"),
                error = o.str("error"),
                source = o.str("source").ifEmpty { "BATC QO-100 wideband spectrum monitor" },
                sourceUrl = o.str("sourceUrl"),
                channels = channels,
                waterfall = o.optJSONObject("waterfall")?.let { Qo100WaterfallRows.parse(it) },
            )
        }
    }
}

/**
 * ?wf=<seq>: the waterfall rows after seq, oldest first. One row a second,
 * one byte a point: dB = dbLow + byte * dbStep. [full] says the box no
 * longer has the rows after the seq asked for (or never had them): start
 * the picture again.
 */
data class Qo100WaterfallRows(
    val seq: Int,
    val full: Boolean,
    val maxRows: Int,
    val dbLow: Double,
    val dbStep: Double,
    val rows: List<ByteArray>,
) {
    companion object {
        fun parse(o: JSONObject): Qo100WaterfallRows {
            val rows = o.optJSONArray("rows")
            return Qo100WaterfallRows(
                seq = o.optInt("seq"),
                full = o.optBoolean("full"),
                maxRows = o.optInt("maxRows", 180).coerceIn(1, 600),
                dbLow = o.dbl("dbLow") ?: -3.0,
                dbStep = o.dbl("dbStep") ?: 0.125,
                rows = if (rows == null) emptyList()
                else (0 until rows.length()).mapNotNull { i -> decode(rows.optString(i)) },
            )
        }

        // java.util's, not android.util's: the unit tests run on a plain
        // JVM where android.util is a stub, and minSdk 26 has java.util.Base64.
        private fun decode(s: String): ByteArray? = runCatching { java.util.Base64.getDecoder().decode(s) }.getOrNull()
    }
}
