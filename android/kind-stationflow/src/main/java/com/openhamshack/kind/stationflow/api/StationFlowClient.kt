package com.openhamshack.kind.stationflow.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import com.openhamshack.core.net.BoxAddress
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The module answered, and said no - in its own words (TV-API.md section 4). */
class Refused(val status: Int, message: String) : IOException(message)

/** No session, or an ended one: 401. The host signs in again; this module never does. */
class NotSignedIn(message: String = "not signed in") : IOException(message)

/**
 * StationFlow's API as docs/TV-API.md (in the icom module) describes it, the
 * receive side only.
 *
 * **The host owns the connection.** [base] is where the app is reached - in
 * the OpenHamShack edition of Dock360 that is the proxy's path for the app, e.g.
 * `http://host:port/app/icom/` - and [http] is the host's client, already
 * carrying whatever sign-in, cookies and certificate trust the host
 * arranged. This class never logs in and never discovers anything; a 401
 * is reported ([NotSignedIn]) for the host to deal with.
 *
 * **Nothing here can transmit.** Every write goes through [post], and [post]
 * takes only the paths in [CONTROLS]: tuning, the preamp and attenuator, and
 * starting the receive-only decoders. Keying, the digital-modes transmit queue, power, settings and
 * transmit audio are not in it and there is no other way to send a write from
 * this class - so "the TV never keys the radio" is a property of one list
 * that a unit test exercises, not of every screen remembering not to. The
 * module enforces the same from its side for a `control`-level session
 * (TV-API.md section 3).
 */
class StationFlowClient(
    val base: BoxAddress,
    http: OkHttpClient,
) {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val sockets = http.newBuilder()
        // A quiet band sends nothing for minutes; a ping every 15 s instead,
        // so a TV whose Wi-Fi went away notices in seconds.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private fun request(path: String) = Request.Builder().url(base.url(path))

    /** The whole state. Also the "is this StationFlow at all" probe. */
    suspend fun state(): RadioState = withContext(Dispatchers.IO) {
        http.newCall(request("api/state").build()).execute().use { r ->
            if (!r.isSuccessful) throw failure(r)
            val o = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull()
            if (o == null || (!o.has("radios") && !o.has("freq"))) {
                throw IOException("${base.display} answered, but not as StationFlow")
            }
            RadioState.parse(o)
        }
    }

    suspend fun setFrequency(hz: Long) = post("api/freq", JSONObject().put("hz", hz))

    /** "USB", "FM", or with the DATA flag, "USB-D". Without -D the module clears DATA. */
    suspend fun setMode(label: String) = post("api/mode", JSONObject().put("mode", label))

    suspend fun setFilter(filter: String) = post("api/filter", JSONObject().put("filter", filter))

    suspend fun setVfo(vfo: String) = post("api/vfo", JSONObject().put("vfo", vfo))

    suspend fun setSplit(on: Boolean) = post("api/split", JSONObject().put("on", on))

    /** The receive front end (TV-API.md section 6, Control): 0 off, n = model.preamps[n-1]. */
    suspend fun setPreamp(level: Int) = post("api/preamp", JSONObject().put("level", level))

    /**
     * The whole preamp setting, an index into the state's preamp_modes: on an
     * IC-9700 0 OFF, 1 P.AMP, 2 EXT, 3 P.AMP+EXT - the only way to the
     * external preamp. Same path and level (Control) as [setPreamp]; the
     * module answers 409 for a setting it lists in preamp_modes_refused.
     */
    suspend fun setPreampMode(mode: Int) = post("api/preamp", JSONObject().put("mode", mode))

    /** 0 off, or one of model.attenuators in dB. */
    suspend fun setAtt(db: Int) = post("api/att", JSONObject().put("db", db))

    /**
     * The scope's settings, any subset (TV-API 8a): span_hz, mode, edge,
     * ref_db, speed, vbw, averaging. What the radio's own scope shows and
     * sends; nothing about what it transmits.
     */
    suspend fun setScope(settings: JSONObject) = post("api/scope", settings)

    /** The module's NR2 on the receive audio (TV-API 7a): {"on"}, {"level"} or both. */
    suspend fun setNr2(on: Boolean? = null, level: Int? = null) = post("api/nr2", JSONObject().apply {
        on?.let { put("on", it) }
        level?.let { put("level", it) }
    })

    /**
     * Start a decoder. Never with "force": a TV taking the decoder from a
     * mode somebody else started is a surprise; a 409 says who has it.
     */
    suspend fun startDecoder(mode: String) = post("api/digi/mode", JSONObject().put("mode", mode))

    suspend fun startSstv() = post("api/sstv/start", JSONObject())

    suspend fun startAprs() = post("api/aprs/start", JSONObject())

    suspend fun getJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(request(path).build()).execute().use { r ->
            if (!r.isSuccessful) throw failure(r)
            JSONObject(r.body?.string().orEmpty())
        }
    }

    /** Raw bytes - an SSTV picture. Null for 404 (live.png with nothing in flight). */
    suspend fun getBytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        http.newCall(request(path).build()).execute().use { r ->
            if (r.code == 404) return@use null
            if (!r.isSuccessful) throw failure(r)
            r.body?.bytes()
        }
    }

    /** The one way this class writes anything. See the class comment. */
    internal suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        require(path in CONTROLS) { "not a receive control: $path" }
        val req = request(path).post(body.toString().toRequestBody(JSON)).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw failure(r)
            runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrDefault(JSONObject())
        }
    }

    /** A websocket on [path], through the host's client (its interceptors add the session). */
    fun socket(path: String, listener: WebSocketListener): WebSocket =
        sockets.newWebSocket(Request.Builder().url(base.wsUrl(path)).build(), listener)

    private fun failure(r: Response): IOException {
        val text = runCatching { r.body?.string() }.getOrNull().orEmpty()
        val said = runCatching { JSONObject(text).optString("error") }.getOrNull()?.ifBlank { null }
        return when {
            r.code == 401 -> NotSignedIn(said ?: "not signed in")
            // 1.23.0 and earlier redirected to the login page instead of 401.
            r.isRedirect && r.header("Location").orEmpty().contains("login") -> NotSignedIn()
            else -> Refused(r.code, said ?: "HTTP ${r.code} from ${base.display}")
        }
    }

    companion object {
        /**
         * Audio, acknowledged (TV-API.md section 7): the family name first,
         * then 1.23.0's, which ignores the query. 500 ms in flight is the
         * contract's default; the jitter buffer here adds 150 ms on top.
         */
        const val AUDIO = "api/audio/rx?ack=1&window_ms=500"
        const val AUDIO_OLD = "api/audio"

        /** The scope, acknowledged per frame (TV-API.md section 8). */
        const val SCOPE = "api/scope?ack=1"

        /**
         * Everything this app may post. Read the class comment before adding
         * to it: nothing that keys, arms, queues a transmission, sets power or
         * carries transmit audio belongs here, not even behind a setting.
         */
        val CONTROLS = setOf(
            "api/freq", "api/mode", "api/filter", "api/vfo", "api/split",
            // The receive front end, P.AMP and ATT: what the receiver hears, Control level (TV-API.md 3).
            "api/preamp", "api/att",
            // The scope's span, reference level and speed, and the module's NR2 on the receive audio:
            // what is seen and heard, Control level (TV-API.md 7a, 8a).
            "api/scope", "api/nr2",
            "api/digi/mode", "api/sstv/start", "api/aprs/start",
        )

        private val JSON = "application/json".toMediaType()
    }
}
