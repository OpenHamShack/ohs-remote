package com.openhamshack.remote.api

import org.json.JSONArray
import org.json.JSONObject

// What the app reads from GET /api/status, and nothing more. The daemon
// sends a great deal (every setting, the network, updates, diagnostics); a
// remote reads the handful of fields below, by the names openhamshack.py uses,
// and ignores the rest - so a field added to the box never breaks the app,
// and a field renamed there breaks exactly one parser here, which the
// fixture test (StatusParseTest) catches. docs/REMOTE-CONTRACT.md lists the
// same fields for the iPad build.

/** status.auth: what this client may do. Mirrors the console's view-only rule. */
data class Auth(
    /** The box's own screen (127.0.0.1): always allowed. */
    val local: Boolean,
    /** Signed in with the remote PIN (or local). */
    val authorised: Boolean,
    /** A remote PIN is set at the box; without one nobody remote can control it. */
    val pinSet: Boolean,
    /**
     * The box is the store app on an OpenHamShack controller (REMOTE-API 2,
     * "On the OpenHamShack platform"): no PIN, and [level] - the person's
     * level for the app, capped by this device - decides. [authorised] is
     * then control or above.
     */
    val platform: Boolean = false,
    /** `view`, `control` or `transmit` on a controller; '' on a Classic box. */
    val level: String = "",
    /** On a controller: the level is transmit. Classic boxes leave it to the PIN. */
    val canTransmit: Boolean = false,
    /** On a controller: the platform user the proxy vouched for ("-" when nobody signed in). */
    val user: String = "",
) {
    /** The console's `viewOnly()`: anything that changes the box needs this false. */
    val viewOnly: Boolean get() = !authorised

    /**
     * Transmitting and the transmit settings are refused. On a controller
     * that is anything below the Transmit level (the app's own rule:
     * openhamshacklib/shack.py required_level); on a Classic box, view only.
     */
    val txLocked: Boolean get() = if (platform) !canTransmit else viewOnly

    /** What the console says to a view-only browser, word for word. */
    val viewOnlyReason: String
        get() = if (pinSet) "View only: enter the PIN to change this."
        else "View only: set a remote PIN on the box (Settings › Security) to control it from here."
}

/** The settings a remote shows or changes; from status.config. */
data class BoxConfig(
    val callsign: String,
    val frequencyMHz: Double?,
    val symbolRateKs: Double?,
    val mode: String,
    val constellation: String,
    val fec: String,
    val gainDb: Double?,
    val source: String,
    val streamProtocol: String,
    val rxFrequencyMHz: Double?,
    val rxSymbolRateKs: Double?,
    val rxOffsetMHz: Double?,
    /** The QO-100 LNB's local oscillator (console: `qoLo()`, 9750 unless set). */
    val qo100LnbMHz: Double,
    val txTimeoutMin: Int?,
)

data class RxVideo(val width: Int?, val height: Int?, val codec: String?, val fps: Double?)

/** One receiver as the box reports it: status.rx, or an entry of status.receivers. */
data class Receiver(
    val id: Int,
    val tuner: String,
    val kind: String,
    val enabled: Boolean,
    /** true locked, false searching, null "no status" (a network PicoTuner). */
    val locked: Boolean?,
    val label: String,
    val mer: Double?,
    val marginDb: Double?,
    val name: String,
    val provider: String,
    val modcod: String?,
    val video: RxVideo?,
    val frequencyMHz: Double?,
    val symbolRateKs: Double?,
    val offsetMHz: Double?,
    val heard: Boolean,
)

/** status.txTimeout while keyed with a time-out set. */
data class TxTimeout(val minutes: Int, val secondsLeft: Int, val warning: Boolean)

/** status.tx.remote: the remote lease while keyed under one. */
data class RemoteLease(val id: String, val secondsLeft: Double?, val leaseSeconds: Double?)

/** status.txCheck: a key-up refused because QO-100 looked busy (or unchecked); a forced key may go anyway. */
data class TxCheck(val kind: String, val message: String)

/** status.stream: the Stream (OBS / phone) source's listener. */
data class StreamState(
    val protocol: String,
    val port: Int,
    val latencyMs: Int,
    val secretSet: Boolean,
    /** off, listening, receiving, error. */
    val state: String,
    val message: String,
    val note: String,
    /** What arrives, in words: "H264 1280×720", "AAC stereo" (status.stream.video/audio are objects). */
    val video: String?,
    val audio: String?,
    val kbps: Double?,
    /** null, "card" (the stream-lost card is on air) or "stream". */
    val onAir: String?,
    val problem: String,
)

data class Status(
    val onAir: Boolean,
    val onAirSeconds: Int?,
    val txError: String,
    val txBlocked: String,
    val txCheck: TxCheck?,
    val txTimeout: TxTimeout?,
    val auth: Auth,
    /** false: [auth] was not in the answer (a POST's), and is a placeholder. */
    val authReported: Boolean,
    val config: BoxConfig,
    val rx: Receiver,
    val receivers: List<Receiver>,
    val stream: StreamState?,
    /** status.tx.remote while a remote lease holds the transmission. */
    val lease: RemoteLease?,
    /**
     * The box knows the remote lease (status.tx has a "remote" key, null or
     * not). A box without it would accept {"remote"} and ignore it - no
     * dead-man - so the app does not key such a box at all.
     */
    val leaseSupported: Boolean,
    /** Only in the answer to POST /api/tx/tune: what was changed, and what waits for the next key-up. */
    val txTune: TxTune?,
    /** status.rxView.enlarged: the heard receiver fills the box's TV. */
    val rxEnlarged: Boolean,
    val version: String,
) {
    companion object {
        fun parse(o: JSONObject): Status {
            val cfg = o.optJSONObject("config") ?: JSONObject()
            val rxObj = o.optJSONObject("rx") ?: JSONObject()
            val receivers = o.optJSONArray("receivers").objects().map { parseReceiver(it, cfg) }
            // status.rx is receiver 1 without the id/tuning fields; fill them
            // from receivers[0] when it is there, else from the rx* settings.
            val first = receivers.firstOrNull { it.id == 1 }
            val rx = parseReceiver(rxObj, cfg).copy(
                id = 1,
                tuner = first?.tuner ?: rxObj.str("usbTuner"),
                kind = first?.kind ?: cfg.str("rxTuner"),
                frequencyMHz = first?.frequencyMHz ?: cfg.dbl("rxFrequencyMHz"),
                symbolRateKs = first?.symbolRateKs ?: cfg.dbl("rxSymbolRateKs"),
                offsetMHz = first?.offsetMHz ?: cfg.dbl("rxOffsetMHz"),
                heard = first?.heard ?: true,
            )
            return Status(
                onAir = o.optBoolean("onAir"),
                onAirSeconds = o.int("onAirSeconds"),
                txError = o.str("txError"),
                txBlocked = o.str("txBlocked"),
                txCheck = o.optJSONObject("txCheck")?.let { TxCheck(it.str("kind"), it.str("message")) },
                txTimeout = o.optJSONObject("txTimeout")?.let {
                    TxTimeout(it.optInt("minutes"), it.optInt("secondsLeft"), it.optBoolean("warning"))
                },
                // Only GET /api/status carries auth: the POSTs that answer with
                // the status (ptt, config, rx/tune, ptt/keep) send status()
                // without it. Such an answer means the change was allowed, so
                // "authorised" is true, but whether this is the box's screen or
                // a PIN session is not said: the caller keeps what it knew
                // (authReported false).
                auth = o.optJSONObject("auth").let {
                    if (it == null) Auth(local = false, authorised = true, pinSet = true)
                    else Auth(it.optBoolean("local"), it.optBoolean("authorised"), it.optBoolean("pinSet"),
                        platform = it.optBoolean("platform"), level = it.str("level"), canTransmit = it.optBoolean("canTransmit"),
                        user = it.str("user"))
                },
                authReported = o.optJSONObject("auth") != null,
                config = BoxConfig(
                    callsign = cfg.str("callsign"),
                    frequencyMHz = cfg.dbl("frequencyMHz"),
                    symbolRateKs = cfg.dbl("symbolRateKs"),
                    mode = cfg.str("mode"),
                    constellation = cfg.str("constellation"),
                    fec = cfg.str("fec"),
                    gainDb = cfg.dbl("gainDb"),
                    source = cfg.str("source"),
                    streamProtocol = cfg.str("streamProtocol").ifEmpty { "srt" },
                    rxFrequencyMHz = cfg.dbl("rxFrequencyMHz"),
                    rxSymbolRateKs = cfg.dbl("rxSymbolRateKs"),
                    rxOffsetMHz = cfg.dbl("rxOffsetMHz"),
                    qo100LnbMHz = cfg.dbl("qo100LnbMHz")?.takeIf { it > 0 } ?: 9750.0,
                    txTimeoutMin = cfg.int("txTimeoutMin"),
                ),
                rx = rx,
                receivers = receivers.ifEmpty { listOf(rx) },
                stream = o.optJSONObject("stream")?.let { s ->
                    StreamState(
                        protocol = s.str("protocol"),
                        port = s.optInt("port"),
                        latencyMs = s.optInt("latencyMs", 200),
                        secretSet = s.optBoolean("secretSet"),
                        state = s.str("state"),
                        message = s.str("message"),
                        note = s.str("note"),
                        video = s.optJSONObject("video")?.let { v ->
                            listOfNotNull(v.strOrNull("codec")?.uppercase(),
                                if (v.int("width") != null) "${v.int("width")}×${v.int("height")}" else null,
                                v.int("fps")?.let { "$it fps" }).joinToString(" ").ifEmpty { null }
                        },
                        audio = s.optJSONObject("audio")?.let { a ->
                            listOfNotNull(a.strOrNull("codec")?.uppercase(), a.strOrNull("channels"))
                                .joinToString(" ").ifEmpty { null }
                        },
                        kbps = s.dbl("kbps"),
                        onAir = s.strOrNull("onAir"),
                        problem = s.str("problem"),
                    )
                },
                lease = o.optJSONObject("tx")?.optJSONObject("remote")?.let {
                    RemoteLease(it.str("id"), it.dbl("secondsLeft"), it.dbl("leaseSeconds"))
                },
                leaseSupported = o.optJSONObject("tx")?.has("remote") == true,
                txTune = o.optJSONObject("txTune")?.let { t ->
                    val ch = t.optJSONObject("changed") ?: JSONObject()
                    val nk = t.optJSONArray("nextKeyUp")
                    TxTune(ch.keys().asSequence().associateWith { ch.get(it).toString() },
                        if (nk == null) emptyList() else (0 until nk.length()).map { nk.optString(it) })
                },
                rxEnlarged = o.optJSONObject("rxView")?.optBoolean("enlarged") ?: false,
                version = o.str("version"),
            )
        }

        private fun parseReceiver(o: JSONObject, cfg: JSONObject) = Receiver(
            id = o.optInt("id", 1),
            tuner = o.str("tuner"),
            kind = o.str("kind"),
            enabled = o.optBoolean("enabled", true),
            locked = if (o.isNull("locked")) null else o.optBoolean("locked"),
            label = o.str("label"),
            mer = o.dbl("mer"),
            marginDb = o.dbl("marginDb"),
            name = o.str("name"),
            provider = o.str("provider"),
            modcod = o.strOrNull("modcod"),
            video = o.optJSONObject("video")?.let {
                RxVideo(it.int("width"), it.int("height"), it.strOrNull("codec"), it.dbl("fps"))
            },
            frequencyMHz = o.dbl("frequencyMHz"),
            symbolRateKs = o.dbl("symbolRateKs"),
            offsetMHz = o.dbl("offsetMHz"),
            heard = o.optBoolean("heard", true),
        )
    }
}

/**
 * GET /api/stream/larix: where the camera sends. Boxes after 0.9.16 give
 * port, latencyMs and passphrase as plain fields (passphrase null without
 * a session); older ones only inside the Larix links, read as a fallback.
 */
data class LarixInfo(
    val protocol: String,
    val secretSet: Boolean,
    /** The passphrase was given: only for the box's screen or a PIN session. */
    val secretIncluded: Boolean,
    val port: Int?,
    val latencyMs: Int?,
    /** The SRT passphrase, when the box gave it. */
    val srtPassphrase: String?,
) {
    companion object {
        fun parse(o: JSONObject): LarixInfo {
            val links = o.optJSONArray("links").objects()
            val pass = o.strOrNull("passphrase")
                ?: links.firstNotNullOfOrNull { larixParam(it.str("larix"), "conn[][srtpass]") }
            return LarixInfo(
                protocol = o.str("protocol"),
                secretSet = o.optBoolean("secretSet"),
                secretIncluded = o.optBoolean("secretIncluded") || o.strOrNull("passphrase") != null,
                port = o.int("port"),
                latencyMs = o.int("latencyMs"),
                srtPassphrase = pass,
            )
        }

        /** One parameter of a larix://set/v1?... link, percent-decoded. */
        fun larixParam(link: String, key: String): String? {
            val q = link.substringAfter('?', "")
            for (part in q.split('&')) {
                val eq = part.indexOf('=')
                if (eq < 0) continue
                if (decode(part.substring(0, eq)) == key) return decode(part.substring(eq + 1))
            }
            return null
        }

        private fun decode(s: String): String = java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    }
}

// org.json's opt* return "" / NaN / 0 for JSON null; the box sends null for
// "not known", which must not read as zero (a 0.0 dB MER is a reading).
internal fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")
internal fun JSONObject.strOrNull(k: String): String? = if (isNull(k)) null else optString(k, "").ifEmpty { null }
internal fun JSONObject.dbl(k: String): Double? =
    if (isNull(k)) null else optDouble(k, Double.NaN).takeUnless { it.isNaN() }
internal fun JSONObject.int(k: String): Int? = dbl(k)?.toInt()
internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** status.txTune, after POST /api/tx/tune: the keys saved (as the box has them) and those that wait for a key-up. */
data class TxTune(val changed: Map<String, String>, val nextKeyUp: List<String>)

/** A frequency to offer: a band preset, or a QO-100 band-plan channel. */
data class TxPreset(val label: String, val frequencyMHz: Double)

data class Qo100Channel(val downlinkMHz: Double, val uplinkMHz: Double, val preferred: Boolean)

/**
 * GET /api/tx/options: the transmit choices the box allows, by its rules.
 * Open, like status; fetched when the box is opened and again when the
 * transmit settings are shown.
 */
data class TxOptions(
    val presets: List<TxPreset>,
    val srQo100: List<Int>,
    val srTerrestrial: List<Int>,
    val modes: List<String>,
    /** mode -> modulations. */
    val constellations: Map<String, List<String>>,
    /** "dvbs" -> FECs; "dvbs2/<constellation>" -> FECs. */
    val fec: Map<String, List<String>>,
    val gainMin: Int,
    val gainMax: Int,
    val gainStep: Int,
    val qo100UplinkMHz: ClosedFloatingPointRange<Double>?,
    /** symbol rate (kS) -> the band plan's channels, preferred first. */
    val qo100Channels: Map<Int, List<Qo100Channel>>,
    val licenceRegion: String,
    /** Where a key-up is allowed; null: no band check (region "other"). */
    val licenceBands: List<ClosedFloatingPointRange<Double>>?,
) {
    fun fecsFor(mode: String, constellation: String): List<String> =
        if (mode == "dvbs") fec["dvbs"].orEmpty() else fec["dvbs2/$constellation"].orEmpty()

    fun isQo100Uplink(mhz: Double?): Boolean = mhz != null && qo100UplinkMHz?.contains(mhz) == true

    fun symbolRatesFor(mhz: Double?): List<Int> = if (isQo100Uplink(mhz)) srQo100 else srTerrestrial

    /**
     * A hint before the box is asked, never a decision: the box's own
     * txBlocked after the change is what the app shows as the reason. The
     * box's rule is that each signal edge (1.35 x the symbol rate wide)
     * lies inside one band; QO-100's beacon and narrowband segments are
     * judged at the box only.
     */
    fun outsideLicence(mhz: Double, symbolRateKs: Double): Boolean {
        val bands = licenceBands ?: return false
        val half = 1.35 * symbolRateKs / 1000.0 / 2.0
        return bands.none { (mhz - half) >= it.start && (mhz + half) <= it.endInclusive }
    }

    companion object {
        fun parse(o: JSONObject): TxOptions {
            val fec = mutableMapOf<String, List<String>>()
            o.optJSONObject("fec")?.let { f ->
                f.optJSONArray("dvbs")?.let { fec["dvbs"] = it.strings() }
                f.optJSONObject("dvbs2")?.let { d -> d.keys().forEach { k -> fec["dvbs2/$k"] = d.optJSONArray(k).strings() } }
            }
            val cons = mutableMapOf<String, List<String>>()
            o.optJSONObject("constellations")?.let { c -> c.keys().forEach { k -> cons[k] = c.optJSONArray(k).strings() } }
            val sr = o.optJSONObject("symbolRates") ?: JSONObject()
            val gain = o.optJSONObject("gainDb") ?: JSONObject()
            val qo = o.optJSONObject("qo100")
            val channels = mutableMapOf<Int, List<Qo100Channel>>()
            qo?.optJSONObject("channels")?.let { ch ->
                ch.keys().forEach { k ->
                    val rate = k.toIntOrNull() ?: return@forEach
                    channels[rate] = ch.optJSONArray(k).objects().map {
                        Qo100Channel(it.optDouble("downlinkMHz"), it.optDouble("uplinkMHz"), it.optBoolean("preferred"))
                    }.sortedByDescending { it.preferred }
                }
            }
            val lic = o.optJSONObject("licence")
            return TxOptions(
                presets = o.optJSONArray("presets").objects().map { TxPreset(it.str("label"), it.optDouble("frequencyMHz")) },
                srQo100 = sr.optJSONArray("qo100").ints(),
                srTerrestrial = sr.optJSONArray("terrestrial").ints(),
                modes = o.optJSONArray("modes").strings(),
                constellations = cons,
                fec = fec,
                gainMin = gain.optInt("min", -70),
                gainMax = gain.optInt("max", 0),
                gainStep = gain.optInt("step", 1).coerceAtLeast(1),
                qo100UplinkMHz = qo?.optJSONArray("uplinkMHz")?.range(),
                qo100Channels = channels,
                licenceRegion = lic?.str("region") ?: "",
                licenceBands = lic?.let { l ->
                    if (l.isNull("bands")) null
                    else l.optJSONArray("bands")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONArray(it)?.range() } }
                },
            )
        }

        private fun JSONArray.range(): ClosedFloatingPointRange<Double>? =
            if (length() < 2) null else optDouble(0)..optDouble(1)
    }
}

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifEmpty { null } }
internal fun JSONArray?.ints(): List<Int> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optDouble(it).takeUnless { d -> d.isNaN() }?.toInt() }
