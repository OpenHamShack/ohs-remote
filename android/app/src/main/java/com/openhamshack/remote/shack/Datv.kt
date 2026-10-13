package com.openhamshack.remote.shack

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlin.math.abs

// OpenHamShack DATV's store app (kind `datv`), receive only:
// openhamshack-datv docs/SERVER-API.md. The wire side is pure, so it is tested
// on the JVM against answers recorded from `python3 -m ohsdatv --server`
// (openhamshack-datv main 2d33c18, simulator) behind tools/fakeshack.py.

/**
 * `GET api/status`, the fields a screen needs. [lock] is the engine's
 * `rxState` word as it sends it ("searching", "header", "locked DVB-S",
 * "locked DVB-S2"); [locked] reads it. Null numbers are unknown, not zero.
 */
data class DatvStatus(
    val running: Boolean,
    val band: String,
    val rxFreqHz: Double?,
    val rxSr: Int?,
    val lock: String,
    val mer: Double?,
    val service: String,
    val rxDevice: String,
    val radioError: String,
    val receiveOnly: Boolean,
) {
    val locked: Boolean get() = lock.startsWith("locked")
    /** The simulator (a fresh install's receiver) says so, so a test picture is never taken for a station. */
    val simulator: Boolean get() = rxDevice == "sim"

    companion object {
        fun parse(o: JSONObject) = DatvStatus(
            running = o.optBoolean("running"),
            band = o.optString("band"),
            rxFreqHz = o.num("rxFreq"),
            rxSr = o.num("rxSr")?.toInt(),
            lock = if (o.isNull("locked")) "" else o.optString("locked"),
            mer = o.num("mer"),
            service = if (o.isNull("service")) "" else o.optString("service"),
            rxDevice = o.optString("rxDevice"),
            radioError = o.optString("radioError"),
            // Absent means a server that might transmit: not this build's to open.
            receiveOnly = o.optBoolean("receiveOnly", false),
        )
    }
}

/** One band of `GET api/bands`, with its channels (the engine's ohsdatv/bands.py). */
data class DatvBand(val name: String, val loHz: Double, val hiHz: Double, val defaultHz: Double, val channels: List<DatvChannel>) {
    operator fun contains(hz: Double) = hz in loHz..hiHz
}

data class DatvChannel(val freqHz: Double, val label: String)

object DatvBands {
    fun parse(o: JSONObject): List<DatvBand> {
        val a = o.optJSONArray("bands") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val b = a.optJSONObject(i) ?: return@mapNotNull null
            val ch = b.optJSONArray("channels")
            DatvBand(
                b.optString("name"), b.optDouble("loHz"), b.optDouble("hiHz"), b.optDouble("default"),
                (0 until (ch?.length() ?: 0)).mapNotNull { j ->
                    ch!!.optJSONObject(j)?.let { c -> DatvChannel(c.optDouble("freqHz"), c.optString("label")) }
                },
            ).takeIf { it.name.isNotEmpty() && it.loHz.isFinite() && it.hiHz > it.loHz }
        }
    }
}

/** Symbol rates offered for terrestrial DATV, in S/s: the Classic box's terrestrial set (REMOTE-CONTRACT 7). */
val DATV_RATES = listOf(125_000, 250_000, 333_000, 500_000, 1_000_000, 1_500_000, 2_000_000, 3_000_000, 4_000_000)

/**
 * The `api/set` changes for a tune to [freqHz] at [sr] (null keeps the
 * rate), or null when no band holds it.
 *
 * The band always goes with the frequency: the engine refuses an `rxFreq`
 * outside the current band ("1249.000 MHz is outside 70cm"), and changing
 * the band alone moves the dial to the band's default channel. So a tune
 * names the band that holds the frequency and the frequency together, in
 * one all-or-nothing set, as a QO-100 tune sends frequency and rate
 * together (REMOTE-CONTRACT 7a).
 */
fun datvTune(bands: List<DatvBand>, freqHz: Double, sr: Int? = null): JSONObject? {
    val band = bands.firstOrNull { freqHz in it } ?: return null
    return JSONObject().put("band", band.name).put("rxFreq", freqHz).apply { sr?.let { put("rxSr", it) } }
}

/** The channel the dial is on, within 1 kHz: what a list marks as current. */
fun datvChannelAt(bands: List<DatvBand>, freqHz: Double?): DatvChannel? =
    freqHz?.let { f -> bands.flatMap { it.channels }.firstOrNull { abs(it.freqHz - f) < 1000.0 } }

/**
 * The DATV app behind the controller, [url] its base ending in '/'. The
 * device token goes on every request as a bearer header and never in a URL
 * (CONTROLLER-CONTRACT 4.1); the stream goes through a [StreamRelay].
 * Nothing here can transmit: the server has no route that keys.
 */
class DatvClient(val url: String, private val token: () -> String?) {
    private val json = "application/json".toMediaType()

    private fun req(path: String) = Request.Builder().url(url + path).apply {
        token()?.let { header("Authorization", "Bearer $it") }
    }

    suspend fun status(): DatvStatus = DatvStatus.parse(get("api/status"))
    suspend fun bands(): List<DatvBand> = DatvBands.parse(get("api/bands"))

    /** All or nothing; a refusal is a [ShackException] with the engine's sentence. */
    suspend fun set(changes: JSONObject): JSONObject = post("api/set", JSONObject().put("changes", changes))
    suspend fun start(): JSONObject = post("api/start", JSONObject())
    suspend fun stop(): JSONObject = post("api/stop", JSONObject())

    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        shackHttp.newCall(req(path).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw failure(r.code, text)
            runCatching { JSONObject(text) }.getOrElse { JSONObject() }
        }
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        shackHttp.newCall(req(path).post(body.toString().toRequestBody(json)).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw failure(r.code, text)
            runCatching { JSONObject(text) }.getOrElse { JSONObject() }
        }
    }
}
