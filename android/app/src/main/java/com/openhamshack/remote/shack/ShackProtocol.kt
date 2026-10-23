package com.openhamshack.remote.shack

import org.json.JSONObject
import java.util.Base64

// The wire side of an OpenHamShack controller and its store apps, pure so
// it is tested on the JVM: openhamshack docs/contract/CONTROLLER-CONTRACT.md
// sections 3-4 (the well-known document, pairing) and openhamshack-nb
// docs/SERVER-API.md (QO-100 NB and Radio: status, the events socket with
// int16 centi-dB spectra, PCM receive audio announced up front).

/** Which screen an app gets. Chosen by the app's `kind` and nothing else (CONTROLLER-CONTRACT 1). */
enum class Kind(val wire: String) {
    QO100WB("qo100wb"), QO100NB("qo100nb"), RADIO("radio"), DATV("datv"), ICOM("icom"), FLEX("flex"), UNSUPPORTED("");

    /** The NB/Radio engine screen: one server API, two apps (SERVER-API.md). */
    val isEngine: Boolean get() = this == QO100NB || this == RADIO

    /** Every kind this build knows opens (Icom since its kind moved in from stationflow-tv, Flex since M4). */
    val opens: Boolean get() = this != UNSUPPORTED

    companion object {
        /**
         * Exact match, lower case as the contract writes kinds. A kind this
         * build does not know is UNSUPPORTED rather than a guess: opening
         * an Icom app with the NB screen would send it NB's settings.
         */
        fun of(kind: String?): Kind = entries.firstOrNull { it != UNSUPPORTED && it.wire == kind } ?: UNSUPPORTED
    }
}

/** One app as `/.well-known/openhamshack` lists it. [base] is relative to the controller's base URL. */
data class ShackApp(
    val id: String,
    val kind: Kind,
    val wireKind: String,
    val name: String,
    val base: String,
    val running: Boolean,
    val txCapable: Boolean,
    /** The controller lets anyone on its network watch this app (D3). Remote signs in regardless (StreamRelay). */
    val anonymousView: Boolean = false,
)

/** The open, credential-free description of a controller (CONTROLLER-CONTRACT 3). */
data class WellKnown(
    val controllerId: String,
    val name: String,
    val version: String,
    val apps: List<ShackApp>,
) {
    /** The apps this build can open, in the controller's order (sorted by name). */
    val openable: List<ShackApp> get() = apps.filter { it.kind.opens }

    companion object {
        /** Contract majors this build speaks. */
        const val MAJOR = 1

        /**
         * Null when the document is not a controller's (a Classic box, a web
         * server, anything else at that address). Throws
         * [NewerControllerException] for a contract major this build does
         * not know, which the client must refuse and say so (section 3).
         */
        fun parse(text: String): WellKnown? {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
            if (!o.has("openhamshack")) return null
            val major = o.optInt("openhamshack", -1)
            if (major != MAJOR) throw NewerControllerException(major)
            val apps = o.optJSONArray("apps")
            return WellKnown(
                controllerId = o.optString("controllerId"),
                name = o.optString("name"),
                version = o.optString("version"),
                apps = (0 until (apps?.length() ?: 0)).mapNotNull { i ->
                    val a = apps!!.optJSONObject(i) ?: return@mapNotNull null
                    val base = a.optString("base")
                    // A base is a relative reference; anything absolute or
                    // climbing out is not followed (never build a URL from
                    // what a server says beyond its own relative paths).
                    if (base.isEmpty() || base.contains("://") || base.startsWith("/") || base.contains("..")) return@mapNotNull null
                    ShackApp(
                        id = a.optString("id"),
                        kind = Kind.of(a.optString("kind")),
                        wireKind = a.optString("kind"),
                        name = a.optString("name"),
                        base = if (base.endsWith("/")) base else "$base/",
                        running = a.optBoolean("running", false),
                        txCapable = a.optBoolean("txCapable", false),
                        anonymousView = a.optBoolean("anonymousView", false),
                    )
                },
            )
        }
    }
}

class NewerControllerException(val major: Int) : Exception("contract $major")

/** `POST api/shack/pair/start`'s answer (CONTROLLER-CONTRACT 4.3). */
data class PairStart(val pairId: String, val pairSecret: String, val code: String, val expiresIn: Int, val pollEvery: Int) {
    companion object {
        fun parse(o: JSONObject) = PairStart(
            o.getString("pairId"), o.getString("pairSecret"), o.getString("code"),
            o.optInt("expiresIn", 300), o.optInt("pollEvery", 2).coerceIn(1, 10),
        )
    }
}

/** `POST api/shack/pair/poll`: pending, approved with a token, or denied. */
sealed interface PairPoll {
    data object Pending : PairPoll
    data object Denied : PairPoll
    data class Approved(val token: String, val maxLevel: String) : PairPoll

    companion object {
        fun parse(o: JSONObject): PairPoll = when (o.optString("state")) {
            "approved" -> o.optString("token").takeIf { it.startsWith("shackdev_") }
                ?.let { Approved(it, o.optString("maxLevel", "view")) } ?: Denied
            "denied" -> Denied
            else -> Pending
        }
    }
}

/**
 * `GET api/status` of a NB or Radio server, the fields a screen needs.
 * [leaseSupported] is the presence of `tx.remote`: a server without it is
 * too old to hold a remote's transmit lease, and nothing keys through it
 * (GUIDE 2, "Transmit lease").
 */
data class EngineStatus(
    val kind: Kind,
    val name: String,
    val version: String,
    val running: Boolean,
    val onAir: Boolean,
    val txError: String,
    val txBlocked: String,
    val leaseSupported: Boolean,
    val leaseHolder: String?,
    val radioError: String,
    val simulator: Boolean,
    /** Automatic transmit (Enable TX) as the server has it; null from a server without it. */
    val autoTx: AutoTxStatus? = null,
    /**
     * What the receiver tunes, Hz (`rxRange: [lo, hi]`), when the server
     * says: Radio's band buttons leave out bands it cannot reach. No server
     * sends it yet (openhamshack-nb has the device's rx_range but does not
     * publish it); fakeshack can (FAKESHACK_RADIO_RXRANGE). Null: unknown.
     */
    val rxRange: Pair<Double, Double>? = null,
    /** `audio`: the receive audio as the server describes it; null from a server that does not. */
    val audio: EngineAudio? = null,
    /** `hostLibs`: the controller's host libraries (faad2, the SDRplay API); null outside the store form or from an older server. */
    val hostLibs: HostLibs? = null,
) {
    companion object {
        fun parse(o: JSONObject): EngineStatus {
            val tx = o.optJSONObject("tx")
            return EngineStatus(
                kind = Kind.of(o.optString("kind")),
                name = o.optString("name"),
                version = o.optString("version"),
                running = o.optBoolean("running"),
                onAir = o.optBoolean("onAir"),
                txError = o.optString("txError"),
                txBlocked = o.optString("txBlocked"),
                leaseSupported = tx != null && tx.has("remote"),
                leaseHolder = tx?.takeIf { !it.isNull("remote") }?.optString("remote"),
                radioError = o.optString("radioError"),
                simulator = o.optBoolean("simulator"),
                autoTx = o.optJSONObject("autoTx")?.let { AutoTxStatus.parse(it) },
                rxRange = o.optJSONArray("rxRange")?.takeIf { it.length() == 2 }?.let { a ->
                    val lo = a.optDouble(0, Double.NaN)
                    val hi = a.optDouble(1, Double.NaN)
                    if (lo.isFinite() && hi.isFinite() && hi > lo && lo >= 0) lo to hi else null
                },
                audio = o.optJSONObject("audio")?.let { EngineAudio.parse(it) },
                hostLibs = o.optJSONObject("hostLibs")?.takeIf { it.has("supported") }?.let { HostLibs.parse(it) },
            )
        }
    }
}

/** One bandscope frame: [db] per bin from [startHz], [binHz] apart. */
class SpectrumFrame(val kind: String, val startHz: Double, val binHz: Double, val db: FloatArray) {
    val endHz: Double get() = startHz + binHz * db.size

    companion object {
        /**
         * `{"kind", "t", "startHz", "binHz", "n", "cdb"}`, cdb little-endian
         * int16 centi-dB in base64. Null when the bins and `n` disagree: a
         * frame drawn at the wrong width would put every signal at the
         * wrong frequency.
         */
        fun parse(o: JSONObject): SpectrumFrame? {
            val raw = runCatching { Base64.getDecoder().decode(o.optString("cdb")) }.getOrNull() ?: return null
            val n = o.optInt("n", -1)
            if (raw.size % 2 != 0 || raw.size / 2 != n || n <= 0) return null
            val binHz = o.optDouble("binHz", Double.NaN)
            val start = o.optDouble("startHz", Double.NaN)
            if (!binHz.isFinite() || binHz <= 0 || !start.isFinite()) return null
            val db = FloatArray(n) { i ->
                val v = (raw[2 * i].toInt() and 0xFF) or (raw[2 * i + 1].toInt() shl 8)
                v.toShort() / 100f
            }
            return SpectrumFrame(o.optString("kind"), start, binHz, db)
        }
    }
}

/**
 * `api/status` `audio` (openhamshack-nb SERVER-API, from 78ec61f): what
 * `api/audio/rx` sends a listener that asks for nothing, and whether the
 * mode is making stereo now. Only a description: the stream's own headers
 * say what it actually carries ([PcmFormat]), and they win.
 */
data class EngineAudio(
    val rate: Int,
    val channels: Int,
    /** The wide-FM stereo pilot is locked: the audio has two different sides now. For a stereo badge. */
    val stereo: Boolean,
    /**
     * The status has the 48 kHz-era shape, which is how a client knows
     * `?channels=2` is understood. `stereo` is the tell, not `channels`:
     * servers before 78ec61f already sent `{"encoding", "rate": 12000,
     * "channels": 1, "listeners"}` here and ignored any query.
     */
    val channelsKnown: Boolean,
) {
    companion object {
        fun parse(o: JSONObject) = EngineAudio(
            rate = o.optInt("rate", 0),
            channels = o.optInt("channels", 1),
            stereo = o.optBoolean("stereo", false),
            channelsKnown = o.has("stereo"),
        )
    }
}

/**
 * How many channels to ask `api/audio/rx` for: 2, or null to ask nothing
 * (the request every server has always taken). Pure, for the tests.
 *
 * - **Only Radio.** Radio is the one with wide FM; NB runs the same server
 *   code and would honour `?channels=2`, but its audio is SSB and CW, and a
 *   stereo copy of it is twice the bytes over a TV's Wi-Fi for nothing.
 * - **Only a server that says it understands.** An older Radio, or anything
 *   else at that address, gets today's request exactly: a server that
 *   ignored the query would be fine, one that refused it would be silent.
 *   Always asked for, not only while [EngineAudio.stereo] is true: the
 *   format is fixed for the life of a response, so asking only in stereo
 *   would mean a reconnect - a gap - every time the pilot came and went.
 * - **Not after a refusal.** A server that answered 400 bad_channels is
 *   asked mono for the rest of the session ([refused]), so a server that
 *   advertises what it then refuses costs one round trip, not a loop.
 */
fun audioChannelsToRequest(kind: Kind, audio: EngineAudio?, refused: Boolean): Int? =
    if (kind == Kind.RADIO && audio?.channelsKnown == true && !refused) 2 else null

/** Receive audio's format, from the headers it is announced in before the first sample. */
data class PcmFormat(val rate: Int, val channels: Int) {
    companion object {
        /**
         * `X-Audio-Format: {"encoding": "s16le", "rate", "channels"}` first,
         * `Content-Type: audio/L16; rate=…; channels=…` otherwise. Null for
         * anything that is not 16-bit PCM this player can take: playing an
         * unknown format as s16le is noise at full volume.
         */
        fun parse(contentType: String?, xAudioFormat: String?): PcmFormat? {
            xAudioFormat?.let { h ->
                val o = runCatching { JSONObject(h) }.getOrNull()
                if (o != null) {
                    if (o.optString("encoding") != "s16le") return null
                    return valid(o.optInt("rate"), o.optInt("channels", 1))
                }
            }
            val ct = contentType ?: return null
            val parts = ct.split(';').map { it.trim() }
            if (!parts.first().equals("audio/L16", ignoreCase = true)) return null
            val params = parts.drop(1).mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].lowercase() to it[1] } }.toMap()
            return valid(params["rate"]?.toIntOrNull() ?: 0, params["channels"]?.toIntOrNull() ?: 1)
        }

        private fun valid(rate: Int, channels: Int) = if (rate in 4000..96000 && channels in 1..2) PcmFormat(rate, channels) else null
    }
}

/** A message on `api/events`. */
sealed interface EngineEvent {
    data class Hello(val status: EngineStatus) : EngineEvent
    /** Changed state keys only; the first full state comes from `GET api/state`. */
    data class State(val changes: JSONObject) : EngineEvent
    data class Spectrum(val frame: SpectrumFrame) : EngineEvent
    /** The meter: uncalibrated dBFS ([rxDb]) and, from a server that calibrates, an S reading ([s]). */
    data class Meter(val rxDb: Double?, val beaconDb: Double?, val s: SMeter = SMeter(),
                     /** Radio's stereo flag in wfm (the pilot held), null outside it or from a server without it. */
                     val stereo: Boolean? = null) : EngineEvent
    /** A band's station list made anew (`stations`, Radio): [band] "fm" or "dab". */
    data class StationsMade(val band: String) : EngineEvent
    data class Toast(val text: String) : EngineEvent
    /** A character decoder's text as it comes (CW, RTTY, PSK): a letter, a space, or an undecodable code shown raw. */
    data class Decoded(val mode: String, val text: String, val freq: Double?, val wpm: Double?) : EngineEvent
    /** One whole message from a slotted decoder (FT8, FT4, Q65, JS8). */
    data class Slot(val decode: SlotDecode) : EngineEvent
    /** An SSTV picture starting, a line of it, or its end (ohscore/modes/sstv.py). */
    data class Sstv(val event: SstvEvent) : EngineEvent
    data object Other : EngineEvent

    companion object {
        fun parse(text: String): EngineEvent {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return Other
            return when (o.optString("kind")) {
                "hello" -> o.optJSONObject("status")?.let { Hello(EngineStatus.parse(it)) } ?: Other
                "state" -> State(o.optJSONObject("changes") ?: JSONObject())
                "wide", "zoom" -> SpectrumFrame.parse(o)?.let { Spectrum(it) } ?: Other
                "meter" -> Meter(o.num("rx"), o.num("beacon"), SMeter.parse(o), if (o.has("stereo") && !o.isNull("stereo")) o.optBoolean("stereo") else null)
                "stations" -> Stations.stationsEvent(o)?.let { StationsMade(it) } ?: Other
                "toast" -> o.optString("text").ifEmpty { o.optString("message") }.takeIf { it.isNotEmpty() }?.let { Toast(it) } ?: Other
                // Every decoder's output (ohscore engine._emit_event). A slot
                // means a whole message; none, a stream of characters. A
                // decoder's failure for a slot ({"error"}) is not a decode.
                "events" -> when {
                    // SSTV sends lines of a picture, not text.
                    o.optString("mode") == "sstv" -> SstvEvent.parse(o)?.let { Sstv(it) } ?: Other
                    // Our own over, sent by Enable TX: listed, never a decode.
                    o.has("tx") -> SlotDecode.parseTx(o)?.let { Slot(it) } ?: Other
                    o.has("error") || !o.has("text") || o.optString("mode").isEmpty() -> Other
                    o.has("slot") -> SlotDecode.parse(o)?.let { Slot(it) } ?: Other
                    else -> Decoded(o.optString("mode"), o.optString("text"), o.num("freq") ?: o.num("center"), o.num("wpm"))
                }
                else -> Other
            }
        }
    }
}

internal fun JSONObject.num(key: String): Double? = if (isNull(key) || !has(key)) null else optDouble(key).takeIf { it.isFinite() }

/**
 * Beacon lock as NB's engine reports it (`rxLock`, ohsnb/radio.py): locked,
 * searching, or a correction the operator holds (`hold`, `manual`), which
 * is trusted but not followed. Anything else is shown as searching: the
 * dial is then not to be trusted, which is the safe thing to say.
 */
enum class BeaconLock { LOCKED, SEARCHING, HELD, UNKNOWN;
    companion object {
        fun of(rxLock: String?): BeaconLock = when (rxLock) {
            null, "" -> UNKNOWN
            "locked" -> LOCKED
            "hold", "manual" -> HELD
            else -> SEARCHING
        }
    }
}

/**
 * The engine's state as one screen reads it: `GET api/state`, then each
 * `state` event's changes merged over it. Kept as JSON because the two apps'
 * keys differ and the screen reads a handful of them.
 */
class EngineState(initial: JSONObject = JSONObject()) {
    companion object {
        /** openhamshack-nb's devices that have no transmitter (ohscore/ui/chrome.py names them "receive only"). */
        val RECEIVE_ONLY_DEVICES = setOf("rtlsdr", "sdrplay", "soapy")
    }

    private val o = JSONObject(initial.toString())

    fun merge(changes: JSONObject): EngineState {
        val n = EngineState(o)
        for (k in changes.keys()) n.o.put(k, changes.opt(k))
        return n
    }

    val dialHz: Double? get() = o.num("vfo")
    val mode: String get() = o.optString("mode")
    /** Radio's band name ("2m"), '' between bands; NB has none. */
    val band: String get() = o.optString("band")
    val segment: String get() = o.optString("segment")
    /** Radio's band plan in force ("rsgb-2026", "iaru-r1"): the strip and the band buttons read that plan. '' from NB. */
    val planInUse: String get() = o.optString("planInUse")
    val beaconLock: BeaconLock get() = BeaconLock.of(if (o.has("rxLock")) o.optString("rxLock") else null)
    val rxSnr: Double? get() = o.num("rxSnr")
    val beaconUsed: String get() = o.optString("beaconUsed")
    val muted: Boolean get() = o.optBoolean("mute")
    val running: Boolean get() = o.optBoolean("running")
    val keyed: Boolean get() = o.optBoolean("keyed")
    val zoomSpanHz: Double? get() = o.num("zoomSpanHz")
    /** The station's callsign as the engine has it (on a controller, the station profile's). */
    val callsign: String get() = o.optString("callsign")
    /** The station's locator, as the engine has it (the station profile's on a controller): CQ's grid. */
    val locator: String get() = o.optString("locator")
    /** Noise reduction on what is heard (USB, LSB, CW, AM); null when this engine has no such key. */
    val nr: Boolean? get() = if (o.has("nr") && !o.isNull("nr")) o.optBoolean("nr") else null
    val error: String get() = o.optString("error")

    // Radio's SDR antenna input ([AntennaChoice]). The current input and the
    // list are the device's (`deviceStatus`, replaced whole by each state
    // change that carries it); the choice and the labels are settings, which
    // the store API keeps as state keys like any other.
    /** The input in use, as the driver names it; null when the device says none. */
    val antenna: String? get() = o.optJSONObject("deviceStatus")?.optString("antenna")?.takeIf { it.isNotEmpty() }
    /** Every input the open radio reported; empty for a radio that is not an SDR, or a server without them. */
    val antennas: List<String> get() = o.optJSONObject("deviceStatus")?.optJSONArray("antennas")?.let { a ->
        (0 until a.length()).mapNotNull { i -> (a.opt(i) as? String)?.takeIf { it.isNotEmpty() } }
    }.orEmpty()
    /** The `soapyAntenna` setting: "" is the driver's default; null from a server without it. */
    val soapyAntenna: String? get() = if (o.has("soapyAntenna") && !o.isNull("soapyAntenna")) o.optString("soapyAntenna") else null
    /** `soapyAntennaLabels` as stored: one line of JSON, read by [Antennas.parseLabels]. */
    val antennaLabels: String get() = o.optString("soapyAntennaLabels")

    /**
     * Why [mode] cannot be used on this receiver, when the server says it
     * cannot (`modes.<mode>` with `decode` false: wide FM on a Hermes-Lite 2,
     * openhamshack-nb SERVER-API "Wide FM"): its `missing` sentence, "" when
     * it gives none. Null when the mode can be used, or the server says
     * nothing of it (an older one), which is taken as usable as before.
     */
    fun modeMissing(mode: String): String? {
        val m = o.optJSONObject("modes")?.optJSONObject(mode) ?: return null
        if (!m.has("decode") || m.optBoolean("decode", true)) return null
        return m.optString("missing")
    }

    /** Wide FM's de-emphasis, µs (`wfmDeemphasis`: 50 or 75); null from a server without the setting. */
    val wfmDeemphasis: Double? get() = o.num("wfmDeemphasis")

    /** Why transmit is refused now, in the server's words (`txBlocked`: wfm, a receive-only radio, the licence); "" when it is not. */
    val txBlocked: String get() = if (o.isNull("txBlocked")) "" else o.optString("txBlocked")

    /**
     * The radio cannot transmit at all: its device reports `tx: "receive only"`
     * (openhamshack-nb's SoapySDR and SDR sources), or it is one of the
     * receive-only kinds (`device`: rtlsdr, sdrplay, soapy).
     */
    val receiveOnlyRadio: Boolean
        get() = o.optJSONObject("deviceStatus")?.optString("tx") == "receive only" || o.optString("device") in RECEIVE_ONLY_DEVICES

    /** Which radio the engine drives (`device`: sim, pluto, hl2, sdrplay...); "" when it does not say. */
    val device: String get() = o.optString("device")

    /** The wide FM station's RDS (`rds`); null until a PI is heard, outside wfm, while the receiver is held, after a retune. */
    val rds: Rds? get() = if (o.isNull("rds")) null else Rds.parse(o.optJSONObject("rds"))

    /** A band scan under way (`bandScan`), null when none runs. */
    val bandScan: BandScan? get() = BandScan.parse(o.optJSONObject("bandScan"))

    /** When each band's station list was last made (`stationsAt`): "fm" and "dab" to an ISO time or null. */
    val stationsAt: Map<String, String?> get() = Stations.stationsAt(o.optJSONObject("stationsAt"))

    /** Who holds the receiver when the operator does not (`rxOwner`: a DAB session); null while the operator has it. */
    val rxOwner: RxOwner? get() = o.optJSONObject("rxOwner")?.let { RxOwner.parse(it) }

    /** Any other numeric key (filter edges, CW pitch: [ModeAudio]); null when absent. */
    fun raw(key: String): Double? = o.num(key)
}

/**
 * One contact in the station's shared logbook as an app shows it
 * (`GET api/log`, ENGINE-API `log_qso`). The frequency key is the app's
 * own: NB logs the downlink dial (`dl`), Radio the on-air frequency (`rf`).
 */
data class Qso(val call: String, val t: Double, val freqHz: Double?, val mode: String, val rstSent: String, val rstRcvd: String, val grid: String) {
    companion object {
        /** Newest first, as a log is read on a small screen. */
        fun parseLog(o: JSONObject): List<Qso> {
            val a = o.optJSONArray("qsos") ?: return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val q = a.optJSONObject(i) ?: return@mapNotNull null
                Qso(q.optString("call"), q.optDouble("t", 0.0), q.num("dl") ?: q.num("rf") ?: q.num("freq"),
                    q.optString("mode"), q.optString("rstSent"), q.optString("rstRcvd"), q.optString("grid"))
            }.sortedByDescending { it.t }
        }
    }
}

/**
 * What a screen needs from `GET api/spec`: the modes, the dial's range, and
 * whether the engine has noise reduction (`nr`) and its own words for it
 * (the `help`, shown as the toggle's explanation).
 */
data class EngineSpec(val modes: List<String>, val dialMin: Double?, val dialMax: Double?, val hasNr: Boolean, val nrHelp: String) {
    companion object {
        fun parse(spec: JSONObject): EngineSpec {
            val mode = spec.optJSONObject("mode")?.optJSONArray("choices")
            val vfo = spec.optJSONObject("vfo")
            val nr = spec.optJSONObject("nr")
            return EngineSpec(
                modes = (0 until (mode?.length() ?: 0)).map { mode!!.optString(it) },
                dialMin = vfo?.num("min"), dialMax = vfo?.num("max"),
                // Only a boolean nr is a switch this screen can offer.
                hasNr = nr?.optString("type") == "bool",
                nrHelp = nr?.optString("help").orEmpty(),
            )
        }
    }
}

/**
 * A decoder session holding Radio's receiver (`rxOwner`, SERVER-API "DAB"):
 * while it runs the dial and mode are its, `api/set` of them is refused
 * `409 receiver_busy`, and stopping it puts them back. [text] is the
 * server's sentence ("The receiver is playing DAB from block 11D: stop the
 * DAB station to tune or change mode").
 */
data class RxOwner(val by: String, val session: String, val block: String, val freqHz: Double?, val text: String) {
    companion object {
        /** Null for `{}` (the operator has it) or anything without the session that holds it. */
        fun parse(o: JSONObject): RxOwner? {
            val session = o.optString("session").trim().takeIf { !o.isNull("session") && it.isNotEmpty() } ?: return null
            return RxOwner(o.optString("by"), session, o.optString("block"), o.num("freqHz"), o.optString("text").trim())
        }
    }
}

/**
 * The controller's host libraries (`hostLibs`): [supported] false on a
 * controller too old to lend them, with its [why] ("Update the controller
 * to use SDRplay radios and DAB+"), which a DAB view shows.
 */
data class HostLibs(val supported: Boolean, val libraries: Map<String, Boolean>, val why: String) {
    companion object {
        fun parse(o: JSONObject): HostLibs {
            val l = o.optJSONObject("libraries")
            return HostLibs(o.optBoolean("supported", true), l?.keys()?.asSequence()?.associateWith { l.optBoolean(it) }.orEmpty(),
                o.optString("why").trim())
        }
    }
}
