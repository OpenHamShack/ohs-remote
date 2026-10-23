package com.openhamshack.remote.shack

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
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The controller or an app said no: [http] status, the contract's snake_case [code], its sentence as the message. */
class ShackException(val http: Int, val code: String, message: String) : IOException(message)

private val JSON = "application/json".toMediaType()

/**
 * One shared OkHttp: connection pool and threads for every controller. The
 * read timeout is per call; the long-lived audio response and the websocket
 * get none (they are kept alive by their own traffic: PCM every few ms, a
 * state change or spectrum frame many times a second).
 */
internal val shackHttp: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
}

/**
 * An OpenHamShack controller's front door (CONTROLLER-CONTRACT 3-4). [baseUrl]
 * is what the operator typed, as http://host:port/, and every other URL is a
 * relative reference resolved against it - never an address the controller
 * reports, which may be one this device cannot reach (REMOTE-CONTRACT).
 */
class ShackController(val baseUrl: String, @Volatile var token: String? = null) {

    /** Null when nothing at this address is a controller. */
    suspend fun wellKnown(): WellKnown? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(baseUrl + ".well-known/openhamshack").build()
        shackHttp.newBuilder().readTimeout(4, TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@use null
            WellKnown.parse(r.body?.string().orEmpty())
        }
    }

    /** A TV asks for a code to show (4.3); a person approves it on the controller or a signed-in phone. */
    suspend fun pairStart(deviceName: String, clientId: String): PairStart = PairStart.parse(
        post(baseUrl + "api/shack/pair/start", JSONObject().put("deviceName", deviceName).put("deviceKind", "tv")
            .put("clientId", clientId).put("app", "remote"), null))

    suspend fun pairPoll(p: PairStart): PairPoll =
        PairPoll.parse(post(baseUrl + "api/shack/pair/poll", JSONObject().put("pairId", p.pairId).put("pairSecret", p.pairSecret), null))

    /** A phone or tablet signs in with the controller's user name and password (4.2). Returns the device token. */
    suspend fun signIn(user: String, password: String, totp: String?, deviceName: String, deviceKind: String, clientId: String): String {
        val body = JSONObject().put("username", user).put("password", password).put("deviceName", deviceName)
            .put("deviceKind", deviceKind).put("clientId", clientId).put("app", "remote")
        if (!totp.isNullOrBlank()) body.put("totp", totp)
        val t = post(baseUrl + "api/shack/token", body, null).optString("token")
        if (!t.startsWith("shackdev_")) throw ShackException(500, "no_token", "")
        token = t
        return t
    }

    fun app(app: ShackApp) = EngineClient(baseUrl + app.base, this)

    /**
     * Whether the controller itself still honours [token], asked of its own
     * device list (CONTROLLER-CONTRACT 4.4, `GET api/shack/v1/devices`),
     * which only the platform answers. An app's 401 is not that answer: a
     * module answers 401 proxy_required while it restarts or Caddy reloads,
     * with the token perfectly good, and believing it unpaired Brian's Fire
     * TV mid-listen (10/10/2026). Only the platform's own "revoked" or "not
     * one of mine" counts as [TokenState.REVOKED]; anything else - no
     * answer, a 5xx, a controller too old for the route - is
     * [TokenState.UNKNOWN], and a token is never thrown away on a guess.
     */
    suspend fun tokenState(): TokenState = withContext(Dispatchers.IO) {
        val t = token ?: return@withContext TokenState.REVOKED
        try {
            val req = Request.Builder().url(baseUrl + "api/shack/v1/devices").header("Authorization", "Bearer $t").build()
            shackHttp.newBuilder().readTimeout(5, TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
                when {
                    r.isSuccessful -> TokenState.VALID
                    r.code == 401 && failure(r.code, r.body?.string().orEmpty()).code in REVOKED_CODES -> TokenState.REVOKED
                    else -> TokenState.UNKNOWN
                }
            }
        } catch (e: IOException) {
            TokenState.UNKNOWN
        }
    }

    companion object {
        /** The platform's words for a token it will not take back (shack_api.py bearer_device). */
        val REVOKED_CODES = setOf("token_revoked", "bad_credentials")
    }
}

enum class TokenState { VALID, REVOKED, UNKNOWN }

/**
 * One NB or Radio app behind the controller (openhamshack-nb docs/SERVER-API.md):
 * [url] is its base, ending in '/'. The device token goes on every request as
 * a bearer header and never in a URL (CONTROLLER-CONTRACT 4.1); this client
 * sets headers for the audio too, so it needs no stream ticket (4.6).
 */
class EngineClient(val url: String, private val controller: ShackController) {

    private fun req(path: String) = Request.Builder().url(url + path).apply {
        controller.token?.let { header("Authorization", "Bearer $it") }
    }

    suspend fun status(): EngineStatus = EngineStatus.parse(get("api/status"))
    suspend fun state(): JSONObject = get("api/state")
    suspend fun spec(): JSONObject = get("api/spec")
    suspend fun log(): List<Qso> = Qso.parseLog(get("api/log"))
    /** The engine's memories (its band plan's presets, then the operator's): the scope's bookmarks. */
    suspend fun memories(): List<Bookmark> = Bookmark.parseList(get("api/memories"))

    /** All or nothing (SERVER-API "api/set"); a refusal is a [ShackException] with the engine's sentence. */
    suspend fun set(changes: JSONObject): JSONObject = postJson("api/set", JSONObject().put("changes", changes))

    /** `{"on": true, "remote": id}` keys under this client's lease; a refused key-up answers 200 with onAir false and txError. */
    suspend fun ptt(on: Boolean, remote: String): EngineStatus =
        EngineStatus.parse(postJson("api/ptt", JSONObject().put("on", on).apply { if (on) put("remote", remote) }))

    /**
     * Automatic transmit (SERVER-API "Automatic transmit"): arm, and choose
     * what goes, in one request; `{"on": false}` disarms. A refusal is a
     * [ShackException] with the server's code and sentence (409 armed
     * elsewhere, 400 no call to answer, 403 level_too_low for a TV).
     */
    suspend fun autotx(body: JSONObject): EngineStatus = EngineStatus.parse(postJson("api/autotx", body))

    /** Every second while keyed. 409 not_lease_holder means the lease is gone: stop at once. */
    suspend fun heartbeat(remote: String) {
        postJson("api/ptt/heartbeat", JSONObject().put("remote", remote))
    }

    /**
     * A pad action (SERVER-API "api/shack/pad/action"): the one way the store
     * API steps a band, which it does not offer as a setting. `ptt` is
     * refused there by the server and never sent from here.
     */
    suspend fun padAction(action: String, kind: String, step: Int): JSONObject {
        require(action != "ptt")
        return postJson("api/shack/pad/action", JSONObject().put("action", action).put("kind", kind).put("step", step))
    }

    /**
     * Radio's decoder sessions (SERVER-API "Decoders"; DAB's station list,
     * RADIO-LISTEN-CONTRACT §3): start one, poll its feed after [since],
     * change its params, stop it. A refusal is a [ShackException] with the
     * server's code and sentence (409 decoder_unavailable without faad2).
     */
    suspend fun decoderStart(body: JSONObject): JSONObject = postJson("api/decoders/sessions", body)
    suspend fun decoderPoll(id: String, since: Int): JSONObject = get("api/decoders/sessions/$id?since=$since")
    suspend fun decoderParams(id: String, params: JSONObject): JSONObject =
        postJson("api/decoders/sessions/$id", JSONObject().put("params", params))
    suspend fun decoderStop(id: String) {
        withContext(Dispatchers.IO) { shackHttp.newCall(req("api/decoders/sessions/$id").delete().build()).execute().close() }
    }

    /**
     * Radio's local frequencies (RADIO-LISTEN-CONTRACT §5, the shape Remote
     * expects until Radio's API is out): the list, and the operator's own
     * added, changed and removed (Control level). A refusal is a
     * [ShackException] with the server's sentence.
     */
    suspend fun local(): JSONObject = get("api/local")
    suspend fun localAdd(body: JSONObject): JSONObject = postJson("api/local/mine", body)
    suspend fun localEdit(id: String, body: JSONObject): JSONObject = postJson("api/local/mine/$id", body)
    suspend fun localDelete(id: String) {
        withContext(Dispatchers.IO) { shackHttp.newCall(req("api/local/mine/$id").delete().build()).execute().use { r -> answer(r) } }
    }

    /**
     * Radio's S-meter calibration (SERVER-API `api/smeter/calibrate`, Radio
     * only: NB answers 404): "the signal now is [dbm]", and clearing the
     * dial's band and antenna. A refusal is a [ShackException] with the
     * server's sentence (409 cannot_calibrate under AGC, between bands...).
     */
    suspend fun smeterCalibrate(dbm: Double): JSONObject = postJson("api/smeter/calibrate", JSONObject().put("dbm", dbm))
    suspend fun smeterClear() {
        withContext(Dispatchers.IO) { shackHttp.newCall(req("api/smeter/calibrate").delete().build()).execute().use { r -> answer(r) } }
    }

    /** The events socket; spectrum kinds are sent only when asked for. */
    fun events(kinds: List<String>, listener: WebSocketListener): WebSocket {
        val ws = url.replaceFirst("http", "ws") + "api/events?kinds=" + kinds.joinToString(",")
        val r = Request.Builder().url(ws).apply { controller.token?.let { header("Authorization", "Bearer $it") } }.build()
        return shackHttp.newWebSocket(r, listener)
    }

    /**
     * The receive PCM, open: the caller reads the body and closes it.
     * Blocking; call on IO. [channels] null asks nothing, as every server
     * has always been asked; 2 asks `?channels=2` ([audioChannelsToRequest]
     * decides). Either way the response's headers, not this argument, say
     * what came back. A server that will not give it answers 400
     * bad_channels, thrown here as a [ShackException].
     */
    fun openAudio(channels: Int? = null): Response {
        val path = if (channels == null) "api/audio/rx" else "api/audio/rx?channels=$channels"
        val r = shackHttp.newBuilder().readTimeout(5, TimeUnit.SECONDS).build()
            .newCall(req(path).build()).execute()
        if (!r.isSuccessful) {
            val body = r.body?.string().orEmpty()
            r.close()
            throw failure(r.code, body)
        }
        return r
    }

    /**
     * [openAudio] with [channels], and if the server refuses that with 400
     * bad_channels, once more asking nothing, telling [refused] so the
     * caller stops asking. Any other failure is thrown as it came.
     */
    fun openAudioOrMono(channels: Int?, refused: () -> Unit): Response = try {
        openAudio(channels)
    } catch (e: ShackException) {
        if (channels == null || e.http != 400 || e.code != "bad_channels") throw e
        refused()
        openAudio(null)
    }

    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        shackHttp.newCall(req(path).build()).execute().use { r -> answer(r) }
    }

    private suspend fun postJson(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        shackHttp.newCall(req(path).post(body.toString().toRequestBody(JSON)).build()).execute().use { r -> answer(r) }
    }
}

private suspend fun post(url: String, body: JSONObject, token: String?): JSONObject = withContext(Dispatchers.IO) {
    val r = Request.Builder().url(url).post(body.toString().toRequestBody(JSON))
        .apply { token?.let { header("Authorization", "Bearer $it") } }.build()
    shackHttp.newCall(r).execute().use { answer(it) }
}

private fun answer(r: Response): JSONObject {
    val text = r.body?.string().orEmpty()
    if (!r.isSuccessful) throw failure(r.code, text)
    return runCatching { JSONObject(text) }.getOrElse { JSONObject() }
}

/** `{"error": sentence, "code": snake_case}`, as every OpenHamShack server answers a refusal. */
internal fun failure(http: Int, body: String): ShackException {
    val o = runCatching { JSONObject(body) }.getOrNull()
    return ShackException(http, o?.optString("code").orEmpty(), o?.optString("error").orEmpty())
}
