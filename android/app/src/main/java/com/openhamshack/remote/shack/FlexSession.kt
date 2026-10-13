package com.openhamshack.remote.shack

import android.content.Context
import com.openhamshack.i18n.ShackFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import com.openhamshack.remote.R
import java.util.concurrent.TimeUnit

/**
 * OpenHamShack Flex's module through the controller: [url] is the app's
 * base (ending '/'), and the device token goes on every request as a bearer
 * header, the websocket's included, never in a URL.
 *
 * **Receive only, by construction.** The ways this class writes are
 * [command], [band], [decoders], [sstv] (the module decodes what the
 * radio receives; neither sends anything to the radio), [scope] (the
 * panadapter's span, scale and waterfall rate: what is drawn, keys in
 * [SCOPE_KEYS] only) and [nr2] (the module's noise reduction on what is
 * heard), and [command] takes only the actions in [ACTIONS]:
 * tuning a slice, its mode, moving the display, and the radio's receive
 * DSP, AGC, RF gain and receive antenna (slice.antenna only ever with `rx`: its
 * `tx` argument chooses where the transmitter goes, and is refused here). The module's transmit
 * routes (api/ptt, api/transmit) and every keying or transmit-setting action
 * have no method here, so "Remote's Flex never keys the radio" is a property
 * of one list a test exercises, as the Icom kind's is. The controller holds
 * the same line at its door (a TV's token is capped at Control, and the
 * store manifest puts api/ptt* and api/transmit at Transmit) and the module
 * refuses keying actions on api/command whatever the level.
 *
 * It never sends the websocket's `display_size`: that message resizes the
 * radio's panadapter for every client on it, so a TV joining would coarsen
 * the operator's own display. Remote draws whatever size the radio sends.
 */
class FlexClient(val url: String, private val token: () -> String?, private val http: OkHttpClient = shackHttp) {
    private val sockets = http.newBuilder()
        // The spectrum arrives many times a second while the radio is up, but
        // a module with no radio sends only its state; a ping keeps a dead
        // link from going unnoticed.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private fun req(path: String) = Request.Builder().url(url + path).apply {
        token()?.let { header("Authorization", "Bearer $it") }
    }

    /** `{"state": ..., "tables": ...}`. */
    suspend fun state(): Pair<FlexState, FlexTables> = withContext(Dispatchers.IO) {
        http.newCall(req("api/state").build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw failure(r.code, text)
            val o = JSONObject(text)
            FlexState.parse(o.optJSONObject("state") ?: JSONObject()) to FlexTables.parse(o.optJSONObject("tables"))
        }
    }

    /** One of [ACTIONS], with its arguments as the module's command table names them. */
    suspend fun command(action: String, arguments: JSONObject): JSONObject {
        require(action in ACTIONS) { "not a receive control: $action" }
        require(!(action == "slice.antenna" && arguments.has("tx"))) { "the transmit antenna is not a receive control" }
        return post("api/command", JSONObject().put("action", action).put("arguments", arguments))
    }

    /** A band by the module's name ("20"), for one receiver: the module puts it back where it was on that band. */
    suspend fun band(name: String, slice: Int): JSONObject = post("api/band", JSONObject().put("band", name).put("slice_id", slice))

    fun socket(listener: WebSocketListener): WebSocket = sockets.newWebSocket(req("ws").build(), listener)

    /**
     * The panadapter's display (API.md 4b): span, centre, reference level
     * and range, frame rate, averaging, the waterfall's rate and levels.
     * They are the radio's panadapter settings, so the operator's own
     * SmartSDR display follows them - which is what "zoom" and "reference
     * level" on a radio mean. Only [SCOPE_KEYS]; nothing that transmits.
     */
    suspend fun scope(settings: JSONObject): JSONObject {
        require(settings.keys().asSequence().all { it in SCOPE_KEYS }) { "not a display setting: $settings" }
        return post("api/scope", settings)
    }

    /** The module's NR2 on the receive audio (API.md 5a): {"on"}, {"level"} or both. */
    suspend fun nr2(on: Boolean? = null, level: Int? = null): JSONObject = post("api/nr2", JSONObject().apply {
        on?.let { put("on", it) }
        level?.let { put("level", it) }
    })

    /** Choose the decoder (API.md 6): `{"mode": "ft8"}`, or "off". Answers the decoder's status. */
    suspend fun decoders(mode: String): FlexDecoders? = FlexDecoders.parse(post("api/decoders", JSONObject().put("mode", mode)))

    /** SSTV on or off (API.md 7). */
    suspend fun sstv(on: Boolean): FlexSstvState? = FlexSstvState.parse(post("api/sstv", JSONObject().put("on", on)))

    /** The decodes since [since] (epoch seconds), or the module's last 500. */
    suspend fun decodes(since: Double?): List<SlotDecode> =
        FlexDecodes.history(get(if (since == null) "api/decodes" else "api/decodes?since=" + "%.3f".format(java.util.Locale.ROOT, since)))

    /** The pictures the module kept, newest first. */
    suspend fun sstvImages(): List<FlexSstvImage> = FlexSstvImage.list(get("api/sstv/images"))

    /** One kept picture's PNG, by its [FlexSstvImage.url] (relative to the base). */
    suspend fun sstvImage(image: FlexSstvImage): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(req(image.url).build()).execute().use { r ->
            if (!r.isSuccessful) throw failure(r.code, r.body?.string().orEmpty())
            r.body?.bytes() ?: ByteArray(0)
        }
    }

    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(req(path).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw failure(r.code, text)
            JSONObject(text)
        }
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(req(path).post(body.toString().toRequestBody(JSON)).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw failure(r.code, text)
            runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        }
    }

    companion object {
        /**
         * Every action this client may send. Read the class comment before
         * adding to it: nothing that keys, chooses the transmitting slice,
         * sets power or carries transmit audio belongs here.
         */
        val ACTIONS = setOf("slice.tune", "slice.mode", "pan.frequency", "slice.dsp", "slice.agc", "slice.antenna", "slice.rf_gain", "pan.rf_gain")
        /** What [scope] may set: the contract's api/scope fields, every one about the display. */
        val SCOPE_KEYS = setOf("pan", "span_hz", "center_hz", "ref_dbm", "range_db", "min_dbm", "max_dbm", "fps",
            "average", "weighted", "line_rate", "auto_black", "black_level", "color_gain")
        private val JSON = "application/json".toMediaType()
    }
}

enum class FlexLink { CONNECTING, UP, DOWN }

data class FlexUi(
    val state: FlexState? = null,
    val tables: FlexTables = FlexTables(),
    val link: FlexLink = FlexLink.CONNECTING,
    val problem: Problem? = null,
    /** A frequency asked for and not yet reported back: shown at once, in amber. */
    val pendingHz: Long? = null,
    /** The step chosen on this device; null until chosen, then the mode's default. */
    val stepHz: Int? = null,
    val muted: Boolean = false,
    /** The worked slice's S-meter, dBm, from the module's levels frames. */
    val sDbm: Float? = null,
    /** Learnt from the first 403: the controller gave this device View on Flex. */
    val viewOnly: Boolean = false,
    val audioRate: Int = 0,
    /** An antenna chosen here and not yet in the module's state (FlexHeld). */
    val heldAnt: FlexHeld<String>? = null,
    /** The decodes kept for the list, newest last. */
    val decodes: SlotDecodes = SlotDecodes(),
    /** Which decoder panel is running and which is shown (opens by itself, as NB/Radio's). */
    val panel: PanelState = PanelState(),
    val autoOpen: Boolean = true,
    /** Changes on every SSTV line painted; [sstvStarts] on every picture started. */
    val sstvVersion: Int = 0,
    val sstvStarts: Int = 0,
    /** The module's kept pictures, newest first, once asked for. */
    val images: List<FlexSstvImage> = emptyList(),
    /** An RF gain chosen here and not yet in the module's state: a module before 0.3.1-beta.2 does not apply it to its model. */
    val heldRf: FlexHeld<Int>? = null,
) {
    /** The RF gain to show, dB. */
    val rfGain: Int get() = slice?.let { s -> heldRf?.shown(s.index, s.rfGain) ?: s.rfGain } ?: 0
    /** The RF gain steps for this radio (FlexRfGain): the worked panadapter's own when the radio said, with the current value on them. */
    val rfSteps: List<Int> get() = FlexRfGain.steps(state?.radioModel.orEmpty(), slice?.rfGain, state?.pan?.rfSteps)
    val slice: FlexSlice? get() = state?.worked
    /** The receive antenna to show: the one just chosen until the radio says otherwise. */
    val rxAnt: String get() = slice?.let { s -> heldAnt?.shown(s.index, s.rxAnt) ?: s.rxAnt }.orEmpty()
    val decoders: FlexDecoders? get() = state?.decoders
    val sstvOn: Boolean get() = state?.sstv?.on == true
    /** Where the running decoder's signals are, when the dial is not there (FlexCalling). */
    val calling: FlexCalling? get() {
        val mode = decoders?.takeIf { it.on }?.mode ?: if (sstvOn) "sstv" else return null
        return FlexCalling.hint(mode, dialHz, slice?.mode, tables)
    }
    /** Noise reduction is on, for the one-press NR switch and the header. */
    val nrOn: Boolean get() = slice?.dsp?.get("nr")?.on == true
    val dialHz: Long? get() = pendingHz ?: slice?.hz
    val step: Int get() = stepHz ?: tables.mode(slice?.mode)?.defaultStep ?: slice?.step?.takeIf { it > 0 } ?: 100
}

/**
 * OpenHamShack Flex open on this device: the panadapter and waterfall, the
 * worked slice's frequency, mode and S-meter, tuning, and the receive audio
 * - all from the module's one websocket, as its web page gets them.
 *
 * Nothing runs while the app is not in front (Home, another app, a TV in
 * standby): the socket closes, so the module stops sending audio to it.
 */
class FlexSession(
    /** Every sentence this class says, from the app's catalog (a stand-in in tests). */
    private val words: FlexWords,
    val app: ShackApp,
    val controllerName: String,
    private val client: FlexClient,
    /** The controller refused the token: pair or sign in again. */
    private val onSignedOut: () -> Unit,
    /** The device's "decoders open by themselves" setting (PanelPrefs, shared with NB/Radio). */
    autoOpen: Boolean = true,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _ui = MutableStateFlow(FlexUi(autoOpen = autoOpen))

    /** The picture arriving, painted on the socket's thread: lock it to read its pixels. */
    val sstv = SstvReceiver()
    val ui: StateFlow<FlexUi> = _ui.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One line: a refused tune, the radio missing. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val scopeData = FlexScope()
    private val _scopeVersion = MutableStateFlow(0)
    val scopeVersion: StateFlow<Int> = _scopeVersion.asStateFlow()

    private val player = PcmPlayer()
    @Volatile private var playerRate = 0
    private var feed: Job? = null
    private var socket: WebSocket? = null
    private var sending: Job? = null
    @Volatile private var target: Long? = null
    private val writes = Mutex()

    fun foreground(on: Boolean) {
        if (on && feed == null) feed = scope.launch { socketLoop() }
        if (!on) {
            feed?.cancel()
            feed = null
            socket?.cancel()
            socket = null
            player.stop()
            playerRate = 0
            _ui.update { it.copy(link = FlexLink.CONNECTING, audioRate = 0) }
        }
    }

    fun close() {
        foreground(false)
        scope.cancel()
    }

    private suspend fun socketLoop() {
        while (scope.isActive) {
            val closed = CompletableDeferred<Unit>()
            socket = client.socket(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _ui.update { it.copy(link = FlexLink.UP, problem = null) }
                }
                override fun onMessage(webSocket: WebSocket, text: String) = onText(text)
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onFrame(bytes.toByteArray())
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val p = if (response != null) {
                        val e = failure(response.code, runCatching { response.body?.string() }.getOrNull().orEmpty())
                        if (e.http == 401) scope.launch { onSignedOut() }
                        Problem(e.http, e.message.orEmpty())
                    } else Problem(null, t.message.orEmpty())
                    _ui.update { it.copy(link = FlexLink.DOWN, problem = p) }
                    closed.complete(Unit)
                }
            })
            closed.await()
            player.stop()
            playerRate = 0
            _ui.update { it.copy(link = if (it.link == FlexLink.UP) FlexLink.CONNECTING else it.link, audioRate = 0) }
            delay(RETRY_MS)
        }
    }

    private fun onText(text: String) {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (o.optString("type")) {
            "hello" -> {
                val st = o.optJSONObject("state")?.let { FlexState.parse(it) }
                val tb = FlexTables.parse(o.optJSONObject("tables"))
                _ui.update { u -> settle(u.copy(state = st ?: u.state, tables = tb)) }
                // Joining, or back after a drop: what the decoder heard meanwhile.
                if (st?.decoders?.on == true) scope.launch { catchUp() }
            }
            "state" -> o.optJSONObject("state")?.let { FlexState.parse(it) }?.let { st -> _ui.update { u -> settle(u.copy(state = st)) } }
            "tables" -> _ui.update { it.copy(tables = FlexTables.parse(o.optJSONObject("tables"))) }
            "decodes" -> {
                val list = FlexDecodes.parse(o)
                if (list.isNotEmpty()) _ui.update { u -> u.copy(decodes = list.fold(u.decodes) { acc, d -> acc.add(d) }) }
            }
            "sstv" -> {
                val e = SstvEvent.parse(o) ?: return
                val started = synchronized(sstv) { sstv.apply(e) }
                _ui.update { u ->
                    u.copy(sstvVersion = synchronized(sstv) { sstv.version },
                        sstvStarts = if (started) u.sstvStarts + 1 else u.sstvStarts,
                        panel = if (started) u.panel.sstvStarted(u.autoOpen) else u.panel)
                }
                // A finished picture is in the module's list now.
                if (e is SstvEvent.Done && !e.aborted) scope.launch { loadImages() }
            }
        }
    }

    /** The decodes the module has that this list does not (since its newest), merged in. Quiet on failure. */
    private suspend fun catchUp() {
        try {
            val since = _ui.value.decodes.items.lastOrNull()?.slot
            val got = client.decodes(since).filter { since == null || it.slot > since }
            if (got.isNotEmpty()) _ui.update { u -> u.copy(decodes = got.fold(u.decodes) { acc, d -> acc.add(d) }) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /**
     * The radio has caught up with the last tune: show its dial again. An
     * antenna held on screen goes once the state says anything other than
     * what it said when it was chosen.
     */
    private fun settle(u: FlexUi): FlexUi {
        var out = if (target == null && u.pendingHz != null && u.slice?.hz == u.pendingHz) u.copy(pendingHz = null) else u
        val s = out.slice
        if (out.heldAnt?.holds(s?.index, s?.rxAnt.orEmpty()) == false) out = out.copy(heldAnt = null)
        if (out.heldRf?.holds(s?.index, s?.rfGain ?: 0) == false) out = out.copy(heldRf = null)
        // The panel follows the module's decoder, whoever chose it (this
        // device, another, the module's own page), as NB/Radio's follow the
        // engine's mode. SSTV takes the panel while it is on: a picture is
        // the rarer thing, and the decodes are still kept underneath.
        val now = when {
            out.sstvOn -> DecoderPanel.SSTV
            out.decoders?.on == true -> DecoderPanel.SLOTS
            else -> DecoderPanel.NONE
        }
        return out.copy(panel = out.panel.decoder(now, out.autoOpen))
    }

    private fun onFrame(bytes: ByteArray) {
        when (val f = FlexFrame.parse(bytes)) {
            is FlexFrame.Sweep -> if (f.pan == _ui.value.state?.pan?.id || _ui.value.state == null) {
                scopeData.sweep(f)
                _scopeVersion.value = scopeData.version
            }
            is FlexFrame.WaterfallRow -> if (f.pan == _ui.value.state?.pan?.id) {
                scopeData.row(f)
                _scopeVersion.value = scopeData.version
            }
            is FlexFrame.Audio -> {
                if (f.rate != playerRate) {
                    player.stop()
                    player.muted = _ui.value.muted
                    player.start(f.rate)
                    playerRate = f.rate
                    _ui.update { it.copy(audioRate = f.rate) }
                }
                player.write(f.mono())
            }
            is FlexFrame.Levels -> {
                val idx = _ui.value.slice?.index ?: return
                f.dbm[idx]?.let { v -> _ui.update { it.copy(sDbm = v) } }
            }
            null -> Unit
        }
    }

    // --- controls -------------------------------------------------------

    /** [clicks] steps; each press moves the shown dial at once and the module gets the latest. */
    fun tuneBy(clicks: Int) {
        val u = _ui.value
        val from = u.dialHz ?: return
        tuneTo(FlexTuning.tune(from, u.step, clicks))
    }

    /**
     * Tune the worked receiver to [hz], or as far towards it as the radio
     * receives: at the edge the dial stops and says so (FlexTuning.limit),
     * so no step size or tap can ask the radio for a frequency below
     * 30 kHz, let alone below zero.
     */
    fun tuneTo(hz: Long) {
        val u = _ui.value
        if (u.slice == null) {
            say(words.noSlice())
            return
        }
        if (u.viewOnly) {
            say(words.viewOnly())
            return
        }
        val c = u.tables.coverage
        val lim = FlexTuning.limit(u.dialHz, hz, c)
        when (lim.edge) {
            FlexTuning.Edge.FLOOR -> say(words.floor(c.lowHz))
            FlexTuning.Edge.CEILING -> say(words.ceiling(c.highHz))
            null -> Unit
        }
        if (!lim.send) return
        target = lim.hz
        _ui.update { it.copy(pendingHz = lim.hz) }
        if (sending?.isActive == true) return
        sending = scope.launch {
            while (true) {
                val hzNow = target ?: break
                target = null
                val s = _ui.value.state ?: break
                val slice = s.worked ?: break
                if (!act { client.command("slice.tune", JSONObject().put("slice_id", slice.index).put("frequency", FlexTuning.mhz(hzNow))) }) {
                    // Refused: the readout goes back to the radio's own
                    // frequency at once. Presses made while the refused
                    // request was out go too - they were steps from a
                    // frequency the radio never reached. Then the module is
                    // asked where the radio really is, because the socket
                    // sends state only on a change and a refusal changes
                    // nothing: on Brian's Fire TV the big readout stayed on
                    // the refused 3.00 kHz while the radio sat elsewhere.
                    // The resync runs on its own so this loop ends now and
                    // the next press starts a new one rather than landing
                    // in a loop that is about to stop.
                    target = null
                    _ui.update { it.copy(pendingHz = null) }
                    scope.launch { resync() }
                    break
                }
                // The module's page follows the receiver off the edge of the
                // display (pane.js followReceiver); so does this, the same way.
                if (s.followReceiver) FlexTuning.recentre(hzNow, s.pan)?.let { c ->
                    act { client.command("pan.frequency", JSONObject().put("pan", s.pan!!.id).put("center", c / 1e6)) }
                }
            }
        }
    }

    /** The module's current state over HTTP, in place of whatever the socket last said. Quiet if it cannot be had. */
    private suspend fun resync() {
        try {
            val (st, tb) = client.state()
            _ui.update { it.copy(state = st, tables = if (tb.bands.isEmpty() && tb.modes.isEmpty()) it.tables else tb) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    fun setMode(name: String) {
        val slice = _ui.value.slice ?: return
        scope.launch {
            if (act { client.command("slice.mode", JSONObject().put("slice_id", slice.index).put("mode", name)) }) {
                // The step ladder is the mode's: a chosen 10 Hz step makes no sense on FM.
                _ui.update { it.copy(stepHz = null) }
            }
        }
    }

    /** The next (or previous) mode in the module's table. */
    fun cycleMode(by: Int = 1) {
        val modes = _ui.value.tables.modes.map { it.name }.ifEmpty { return }
        val i = modes.indexOf(_ui.value.slice?.mode)
        setMode(modes[Math.floorMod((if (i < 0) 0 else i + by), modes.size)])
    }

    fun cycleBand(by: Int = 1) {
        val u = _ui.value
        val slice = u.slice ?: return
        val bands = u.tables.bands.ifEmpty { return }
        val i = bands.indexOfFirst { it.contains(slice.hz) }
        val next = if (i < 0) (if (by > 0) bands.indexOfFirst { it.lowHz > slice.hz }.takeIf { it >= 0 } ?: 0 else bands.indexOfLast { it.highHz < slice.hz }.takeIf { it >= 0 } ?: (bands.size - 1))
        else Math.floorMod(i + by, bands.size)
        scope.launch { act { client.band(bands[next].name, slice.index) } }
    }

    /** A band by the module's name: the module puts the receiver back where it was on that band. */
    fun cycleBandTo(name: String) {
        val slice = _ui.value.slice ?: return
        scope.launch { act { client.band(name, slice.index) } }
    }

    fun stepBy(by: Int) {
        val u = _ui.value
        _ui.update { it.copy(stepHz = FlexTuning.nextStep(FlexTuning.steps(u.tables.mode(u.slice?.mode)), u.step, by)) }
    }

    // --- the radio's receive DSP, AGC and antenna (API.md 5, slice.agc, slice.antenna)

    /**
     * One DSP function on or off, and/or its level. Only what the slice's
     * `dsp_offered` lists is sent - a newer function the radio lacks would
     * be refused (409) and say nothing useful - and never a level for one
     * that has none (RNN, ANFT: the module answers 400).
     */
    fun setDsp(name: String, enabled: Boolean? = null, level: Int? = null) {
        val slice = writable() ?: return
        val f = FlexDspTable.of(name) ?: return
        if (name !in slice.dspOffered) return
        val lvl = level?.takeIf { f.hasLevel }?.coerceIn(0, 100)
        if (enabled == null && lvl == null) return
        scope.launch {
            act {
                client.command("slice.dsp", JSONObject().put("slice_id", slice.index).put("name", name).apply {
                    enabled?.let { put("enabled", it) }
                    lvl?.let { put("level", it) }
                })
            }
        }
    }

    /** The one-press switch: noise reduction (NR, on every FLEX) on or off. */
    fun toggleNr() {
        val slice = _ui.value.slice ?: return
        setDsp("nr", enabled = slice.dsp["nr"]?.on != true)
    }

    /** A DSP level moved by [by] steps of five, from what the radio last said. */
    fun stepDsp(name: String, by: Int) {
        val level = _ui.value.slice?.dsp?.get(name)?.level ?: return
        setDsp(name, level = FlexDspTable.nextLevel(level, by))
    }

    /** AGC off, slow, med or fast. */
    fun setAgcMode(mode: String) {
        val slice = writable() ?: return
        if (mode !in FlexAgc.MODES) return
        scope.launch { act { client.command("slice.agc", JSONObject().put("slice_id", slice.index).put("mode", mode)) } }
    }

    /** AGC-T, 0-100, moved by [by] steps of five. */
    fun stepAgcThreshold(by: Int) {
        val slice = _ui.value.slice ?: return
        setAgcThreshold(((slice.agcThreshold / FlexAgc.STEP + by) * FlexAgc.STEP))
    }

    fun setAgcThreshold(value: Int) {
        val slice = writable() ?: return
        scope.launch {
            act { client.command("slice.agc", JSONObject().put("slice_id", slice.index).put("threshold", value.coerceIn(0, 100))) }
        }
    }

    /**
     * The receive antenna, from the radio's own list for the slice. Only
     * `rx` is ever sent; the transmit antenna is not something a remote
     * that never transmits has any business choosing.
     */
    fun setAntenna(name: String) {
        val slice = writable() ?: return
        if (name !in slice.antList) return
        scope.launch {
            if (act { client.command("slice.antenna", JSONObject().put("slice_id", slice.index).put("rx", name)) }) {
                _ui.update { u -> u.copy(heldAnt = FlexHeld(slice.index, u.slice?.rxAnt.orEmpty(), name).takeIf { it.want != it.was }) }
            }
        }
    }

    /**
     * RF gain, dB: the preamp above 0, the attenuator below. Only a step on
     * this radio's ladder (FlexRfGain.steps) is sent. It is a panadapter
     * setting (API.md 4a), so it goes as pan.rf_gain to the worked slice's
     * panadapter when the module takes that; an older module gets
     * slice.rf_gain, which a newer one also routes to the slice's panadapter.
     * Named explicitly rather than left to the module's default pane, which
     * with two panes need not be the one this slice is on.
     */
    /**
     * Zoom the worked panadapter one step of the module's span ladder (in is
     * narrower), keeping its centre. Nothing at either end, and nothing from
     * a module without api/scope (the control is not offered then).
     */
    fun zoomScope(zoomIn: Boolean) {
        val pan = scopePan() ?: return
        val now = sentSpan.current() ?: pan.spanSetHz ?: pan.spanHz.toLong()
        val span = FlexScopeControl.zoom(pan.spansHz.orEmpty(), now, zoomIn) ?: return
        sentSpan.sent(span)
        setScope(JSONObject().put("span_hz", span))
    }

    /** The top of the panadapter's scale one press up or down ([FlexScopeControl.REF_PRESS_DB]). */
    fun stepScopeRef(up: Boolean) {
        val pan = scopePan() ?: return
        val now = sentRef.current() ?: pan.refDbm ?: pan.maxDbm
        val ref = FlexScopeControl.ref(now, up) ?: return
        sentRef.sent(ref)
        setScope(JSONObject().put("ref_dbm", ref))
    }

    /** Any of api/scope's settings for the worked panadapter (its id added here). */
    fun setScope(settings: JSONObject) {
        val pan = scopePan() ?: return
        if (_ui.value.viewOnly) { say(words.viewOnly()); return }
        scope.launch { act { client.scope(settings.put("pan", pan.id)) } }
    }

    /** The module's NR2 on the receive audio: on/off or its level 0-100. */
    fun setNr2(on: Boolean? = null, level: Int? = null) {
        if (_ui.value.viewOnly) { say(words.viewOnly()); return }
        scope.launch { act { client.nr2(on, level?.coerceIn(0, 100)) } }
    }

    /** The worked slice's panadapter, when the module can set its scope. */
    private fun scopePan(): FlexPan? = _ui.value.state?.pan?.takeIf { it.hasScopeSettings }

    /**
     * The module reports a panadapter change on the radio's next status
     * line, so quick presses would each start from the old value: what was
     * last sent counts as current for a moment.
     */
    private class Recent<T : Any> {
        private var value: T? = null
        private var at = 0L
        fun sent(v: T) { value = v; at = System.currentTimeMillis() }
        fun current(): T? = value.takeIf { System.currentTimeMillis() - at < 1500 }
    }
    private val sentSpan = Recent<Long>()
    private val sentRef = Recent<Double>()

    fun setRfGain(db: Int) {
        val slice = writable() ?: return
        val u = _ui.value
        if (db !in u.rfSteps) return
        val pan = u.state?.pans?.get(slice.pan)?.takeIf { it.panRfGain }
        scope.launch {
            val sent = act {
                if (pan != null) client.command("pan.rf_gain", JSONObject().put("pan", pan.id).put("value", db))
                else client.command("slice.rf_gain", JSONObject().put("slice_id", slice.index).put("value", db))
            }
            if (sent) {
                _ui.update { u -> u.copy(heldRf = FlexHeld(slice.index, u.slice?.rfGain ?: 0, db).takeIf { it.want != it.was }) }
            }
        }
    }

    /** The next RF gain step up (+1) or down (-1) from what is shown. */
    fun stepRfGain(by: Int) {
        val u = _ui.value
        setRfGain(FlexRfGain.next(u.rfSteps, u.rfGain, by))
    }

    // --- decoders and SSTV (API.md 6, 7) ---------------------------------

    /**
     * The decoder: one of the modes the module offers, or "off". The
     * module's answer is its status, shown at once; the panel opens by
     * itself (settle) when opening by itself is on.
     */
    fun setDecoder(mode: String) {
        val u = _ui.value
        if (writable() == null) return
        val d = u.decoders ?: return
        if (mode != FlexDecoders.OFF && mode !in d.modes) return
        scope.launch {
            var answer: FlexDecoders? = null
            if (act { answer = client.decoders(mode) }) answer?.let { a ->
                _ui.update { v -> settle(v.copy(state = v.state?.copy(decoders = a))) }
            }
        }
    }

    fun setSstv(on: Boolean) {
        if (writable() == null) return
        scope.launch {
            var answer: FlexSstvState? = null
            if (act { answer = client.sstv(on) }) answer?.let { a ->
                _ui.update { v -> settle(v.copy(state = v.state?.copy(sstv = a))) }
            }
            if (on) loadImages()
        }
    }

    /** Tune to the running decoder's calling frequency in its sideband (FlexCalling). */
    fun goToCalling() {
        val c = _ui.value.calling ?: return
        tuneTo(c.hz)
        if (_ui.value.slice?.mode != c.mode) setMode(c.mode)
    }

    fun setAutoOpen(on: Boolean) = _ui.update { it.copy(autoOpen = on) }
    fun openPanel() = _ui.update { it.copy(panel = it.panel.open()) }
    fun closePanel() = _ui.update { it.copy(panel = it.panel.close()) }

    /** The module's kept pictures, newest first, into [FlexUi.images]. Quiet if it cannot be had. */
    suspend fun loadImages() {
        try {
            val list = client.sstvImages()
            _ui.update { it.copy(images = list) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private val pictures = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        // A few PNGs (a PD120 is ~0.5 MB decoded, far less as PNG): enough to page back and forth.
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 12
    }

    /** One kept picture's PNG, cached; null if it cannot be had. */
    suspend fun picture(image: FlexSstvImage): ByteArray? {
        synchronized(pictures) { pictures[image.name] }?.let { return it }
        return try {
            client.sstvImage(image).also { b -> synchronized(pictures) { pictures[image.name] = b } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** The worked slice when this device may change it; else says why not and null. */
    private fun writable(): FlexSlice? {
        val u = _ui.value
        val slice = u.slice
        if (slice == null) {
            say(words.noSlice())
            return null
        }
        if (u.viewOnly) {
            say(words.viewOnly())
            return null
        }
        return slice
    }

    fun toggleMute() {
        val m = !_ui.value.muted
        player.muted = m
        _ui.update { it.copy(muted = m) }
    }

    /** Runs a write; says the module's refusal and returns false. A 403 is remembered: this device may only listen. */
    private suspend fun act(block: suspend () -> Unit): Boolean = try {
        // One write at a time, in the order they were asked for: NR pressed
        // on and straight off again must reach the radio as on, then off,
        // not in whichever order two requests happen to land.
        writes.withLock { block() }
        true
    } catch (e: ShackException) {
        when (e.http) {
            401 -> onSignedOut()
            403 -> _ui.update { it.copy(viewOnly = true) }
        }
        say(e.message?.ifBlank { null } ?: words.refused(e.http))
        false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Anything else - a dropped connection, a body that is not JSON -
        // is said, never thrown: an exception escaping here would end the
        // app, and a refused tune is not worth that.
        say(words.noAnswer(e.message.orEmpty()))
        false
    }

    private fun say(text: String) {
        _messages.tryEmit(text)
    }

    companion object {
        const val RETRY_MS = 2000L
    }
}

/** What [FlexSession] says, so the session runs on the JVM in tests with the sentences stood in. */
interface FlexWords {
    fun noSlice(): String
    fun viewOnly(): String
    fun refused(http: Int): String
    fun noAnswer(why: String): String
    /** Tuning stopped at the bottom of the receiver, [hz]. */
    fun floor(hz: Long): String
    fun ceiling(hz: Long): String

    companion object {
        fun of(context: Context): FlexWords = object : FlexWords {
            override fun noSlice() = context.getString(R.string.flex_no_slice)
            override fun viewOnly() = context.getString(R.string.flex_view_only)
            override fun refused(http: Int) = context.getString(R.string.flex_refused, http)
            override fun noAnswer(why: String) = context.getString(R.string.flex_no_answer, why)
            override fun floor(hz: Long) = context.getString(R.string.flex_floor, edge(hz))
            override fun ceiling(hz: Long) = context.getString(R.string.flex_ceiling, edge(hz))

            /** "30 kHz", "54 MHz": an edge is a round number, so no decimals unless it needs them. */
            private fun edge(hz: Long): String {
                val tag = context.resources.configuration.locales[0].toLanguageTag()
                return when {
                    hz >= 1_000_000 && hz % 1_000_000 == 0L -> ShackFormat.frequency(hz, tag, "MHz", 0)
                    hz >= 1_000_000 -> ShackFormat.frequency(hz, tag, "MHz", 3)
                    else -> ShackFormat.frequency(hz, tag, "kHz", if (hz % 1000 == 0L) 0 else 1)
                }
            }
        }
    }
}
