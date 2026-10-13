package com.openhamshack.remote.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The box answered, and said no: 403 without the PIN, 409 not keyed, 400 a bad value. */
class ApiException(
    val code: Int,
    message: String,
    /** The refusal's snake_case `code` where the server gives one (a controller always does: `token_revoked`, `level_too_low`). */
    val reason: String = "",
) : IOException(message)

/**
 * The console API of one OpenHamShack, as the app uses it. Platform-neutral in
 * shape (the iPad build mirrors these calls one for one; see
 * docs/REMOTE-CONTRACT.md): every call is a relative path on the operator's
 * [BoxAddress], JSON in and out, the PIN session carried as the `ohssession`
 * cookie the daemon sets on /api/login.
 *
 * HttpURLConnection rather than an HTTP library: a dozen small JSON calls
 * need nothing more, and the stream itself is opened by libVLC. Cookies are
 * handled by hand, per box, so one box's session can never be sent to
 * another (a process-wide CookieManager would key them by host, and two
 * boxes reached through one tailnet name or NAT would share one).
 *
 * The same API is the QO-100 WB store app's on an OpenHamShack controller
 * (`~/openhamshack` REMOTE-API 2, "On the OpenHamShack platform"). There
 * [baseUrl] is the controller's front door plus the app's relative base
 * (`http://host/shack-qo100wb`, from /.well-known/openhamshack, never an
 * address the app reports), there is no PIN session, and [bearer] gives the
 * device token, sent as `Authorization: Bearer` on every call
 * (CONTROLLER-CONTRACT 4.1).
 */
class OpenHamShackClient(
    /** No trailing slash: every path below starts with "/api/". */
    val baseUrl: String,
    @Volatile var session: String? = null,
    private val bearer: (() -> String?)? = null,
) {
    constructor(address: BoxAddress, session: String? = null) : this(address.baseUrl, session)

    /** Receiver [rx]'s raw MPEG-TS, for the player (open to anyone on a Classic box, like status). */
    fun streamUrl(rx: Int = 1): String = "$baseUrl$PATH_STREAM" + if (rx == 1) "" else "?rx=$rx"

    suspend fun status(): Status = Status.parse(get("/api/status"))

    /**
     * POST /api/login {"pin"}: returns the session token from Set-Cookie and
     * keeps it. 403 "wrong PIN", or "too many tries; wait a minute".
     */
    suspend fun login(pin: String): String = withContext(Dispatchers.IO) {
        val conn = open("/api/login", "POST", NORMAL_TIMEOUT_MS)
        try {
            send(conn, JSONObject().put("pin", pin))
            val code = conn.responseCode
            val body = readBody(conn)
            if (code !in 200..299) throw ApiException(code, errorText(body, code))
            val token = conn.headerFields.entries
                .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                .flatMap { it.value }
                .firstNotNullOfOrNull { sessionFromCookie(it) }
                ?: throw IOException("OpenHamShack accepted the PIN but sent no session")
            session = token
            token
        } finally {
            conn.disconnect()
        }
    }

    /**
     * POST /api/logout: ends this session at the box (and forgets it here).
     * Answered 200 whatever state the session is in, so it is always safe.
     */
    suspend fun logout() {
        try {
            if (session != null) post("/api/logout", JSONObject())
        } finally {
            session = null
        }
    }

    /** GET /api/tx/options: the transmit choices and the licence's bands. */
    suspend fun txOptions(): TxOptions = TxOptions.parse(get("/api/tx/options"))

    /**
     * POST /api/tx/tune with any of frequencyMHz, symbolRateKs, mode,
     * constellation, fec, gainDb. 400 (ApiException) is a refusal with
     * nothing saved; its message is the box's sentence for the operator.
     * The status answered carries txTune.
     */
    suspend fun txTune(patch: JSONObject): Status = Status.parse(post("/api/tx/tune", patch))

    /** POST /api/config: change settings; returns the new status. */
    suspend fun config(patch: JSONObject): Status = Status.parse(post("/api/config", patch))

    /**
     * POST /api/rx/tune {id, frequencyMHz?, symbolRateKs?, offsetMHz?}. The
     * console's receiver window does exactly this for every receiver
     * (receiver 1 goes through apply_config on the box).
     */
    suspend fun tune(id: Int, frequencyMHz: Double? = null, symbolRateKs: Double? = null, offsetMHz: Double? = null): Status {
        val body = JSONObject().put("id", id)
        frequencyMHz?.let { body.put("frequencyMHz", it) }
        symbolRateKs?.let { body.put("symbolRateKs", it) }
        offsetMHz?.let { body.put("offsetMHz", it) }
        return Status.parse(post("/api/rx/tune", body))
    }

    /**
     * POST /api/rx/select {id, enlarge}: the receiver the box plays sound
     * for. [enlarge] is passed back as the box has it, so choosing what to
     * hear on a phone does not rearrange the box's own TV.
     */
    suspend fun selectReceiver(id: Int, enlarge: Boolean) {
        post("/api/rx/select", JSONObject().put("id", id).put("enlarge", enlarge))
    }

    /**
     * POST /api/ptt. Keying names this install's [remote] id, which starts
     * the box's remote lease: it unkeys by itself 5 s after the last
     * heartbeat. [force] is the console's "transmit anyway" after a QO-100
     * busy refusal. Unkeying never names a remote: off is off, whoever keyed.
     */
    suspend fun ptt(on: Boolean, remote: String? = null, force: Boolean = false): Status {
        val body = JSONObject().put("on", on)
        if (on && remote != null) body.put("remote", remote)
        if (on && force) body.put("force", true)
        // A key-up can take seconds (a LibreSDR is set over its web page
        // first; the QO-100 busy check waits for BATC): the box asks for 30 s.
        // A key-down is quick, and is retried by PttController if lost.
        return Status.parse(post("/api/ptt", body, if (on) KEY_UP_TIMEOUT_MS else PTT_TIMEOUT_MS))
    }

    /**
     * POST /api/ptt/heartbeat {"remote"}: true while the box is still keyed
     * for this remote, false when it answers 409 (it has unkeyed: time-out,
     * an error, another client, or the lease ran out). Network trouble throws.
     */
    suspend fun heartbeat(remote: String): Boolean = try {
        post("/api/ptt/heartbeat", JSONObject().put("remote", remote), PTT_TIMEOUT_MS)
        true
    } catch (e: ApiException) {
        if (e.code == 409) false else throw e
    }

    /** POST /api/ptt/keep: "Keep transmitting", a whole new time-out period. */
    suspend fun keepTransmitting(): Status = Status.parse(post("/api/ptt/keep", JSONObject()))

    /**
     * GET /api/qo100 (open, like status): the transponder's spectrum and
     * the signals on it, as the box reads them from BATC. [wfSeq] also asks
     * for the waterfall rows after that one (0 for all the box keeps).
     * Each call keeps the box's BATC feed open for another 20 s.
     */
    suspend fun qo100(wfSeq: Int? = null): Qo100 =
        Qo100.parse(get("/api/qo100" + if (wfSeq != null) "?wf=${wfSeq.coerceAtLeast(0)}" else ""))

    /** GET /api/stream/larix: the Stream source's Larix links (passphrase only with the PIN). */
    suspend fun larix(): LarixInfo = LarixInfo.parse(get("/api/stream/larix"))

    // --- plumbing ---------------------------------------------------------

    private suspend fun get(path: String): JSONObject = request(path, "GET", null, NORMAL_TIMEOUT_MS)

    private suspend fun post(path: String, body: JSONObject, timeoutMs: Int = NORMAL_TIMEOUT_MS): JSONObject =
        request(path, "POST", body, timeoutMs)

    private suspend fun request(path: String, method: String, body: JSONObject?, timeoutMs: Int): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = open(path, method, timeoutMs)
            try {
                if (body != null) send(conn, body)
                val code = conn.responseCode
                val text = readBody(conn)
                if (code !in 200..299) throw ApiException(code, errorText(text, code),
                    runCatching { JSONObject(text).optString("code") }.getOrDefault(""))
                if (text.isBlank()) JSONObject() else JSONObject(text)
            } finally {
                conn.disconnect()
            }
        }

    private fun open(path: String, method: String, timeoutMs: Int): HttpURLConnection {
        require(path.startsWith("/api/")) { "only the console API" }
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.useCaches = false
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Accept", "application/json")
        session?.let { conn.setRequestProperty("Cookie", "ohssession=$it") }
        bearer?.invoke()?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        return conn
    }

    private fun send(conn: HttpURLConnection, body: JSONObject) {
        val bytes = body.toString().toByteArray()
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { it.write(bytes) }
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode >= 400) conn.errorStream else conn.inputStream
        return stream?.bufferedReader()?.use { it.readText() } ?: ""
    }

    private fun errorText(body: String, code: Int): String =
        runCatching { JSONObject(body).optString("error") }.getOrNull()?.ifBlank { null }
            ?: "OpenHamShack answered HTTP $code"

    companion object {
        const val PATH_STREAM = "/api/rx/stream.ts"
        const val NORMAL_TIMEOUT_MS = 5000
        const val KEY_UP_TIMEOUT_MS = 30_000

        /**
         * Short, so one hung heartbeat cannot eat the box's 5 s lease: the
         * heartbeat loop gives up after 3 s without an answer (PttController).
         */
        const val PTT_TIMEOUT_MS = 2000

        /** "ohssession=TOKEN; Path=/; ..." -> TOKEN. */
        fun sessionFromCookie(header: String): String? {
            val first = header.split(';').first().trim()
            val eq = first.indexOf('=')
            if (eq < 0 || first.substring(0, eq).trim() != "ohssession") return null
            return first.substring(eq + 1).trim().ifEmpty { null }
        }
    }
}
