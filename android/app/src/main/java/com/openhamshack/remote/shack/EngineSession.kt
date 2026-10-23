package com.openhamshack.remote.shack

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import com.openhamshack.remote.ptt.KeyAnswer
import com.openhamshack.remote.ptt.PttController
import com.openhamshack.remote.ptt.PttLink
import kotlin.math.abs

/** Which spectrum feed the scope shows: the whole passband (`wide`) or around the dial (`zoom`). */
enum class Span(val feed: String) { WIDE("wide"), ZOOM("zoom") }

/** Something went wrong, said by the server ([text]) or only known by its HTTP status. */
data class Problem(val http: Int?, val text: String)

data class EngineUi(
    val status: EngineStatus? = null,
    val state: EngineState = EngineState(),
    val connected: Boolean = false,
    val problem: Problem? = null,
    /** The engine's last toast, for a line on screen. */
    val toast: String? = null,
    val stepHz: Int,
    val span: Span = Span.WIDE,
    val muted: Boolean = false,
    /** Where the dial is going while a tune is on its way; shown in place of the engine's dial. */
    val pendingDialHz: Double? = null,
    val modes: List<String> = emptyList(),
    val dialMin: Double? = null,
    val dialMax: Double? = null,
    val audioRate: Int = 0,
    /** Channels the receive audio is playing with (1 or 2), from its headers; 0 while there is none. */
    val audioChannels: Int = 0,
    /** The shared logbook as this app shows it, newest first; null until asked for. */
    val log: List<Qso>? = null,
    /** The engine has noise reduction (`nr` in its spec), and its own sentence for it. */
    val hasNr: Boolean = false,
    val nrHelp: String = "",
    /**
     * The controller refused a receive-side change for this person's level
     * (403 level_too_low): controls that need Control are shown disabled.
     * Unknown until something is refused - the store API does not say the
     * level up front.
     */
    val viewOnly: Boolean = false,
    /** A character decoder's text (CW, RTTY, PSK), as a rolling line. */
    val decoded: DecodedLine = DecodedLine(),
    /** A slotted decoder's messages (FT8, FT4, Q65, JS8), oldest first. */
    val decodes: SlotDecodes = SlotDecodes(),
    /** Changes on every SSTV line painted ([EngineSession.sstv]). */
    val sstvVersion: Int = 0,
    /** Counts SSTV pictures started (a new picture takes over from one being looked back at). */
    val sstvStarts: Int = 0,
    /** The engine's memories, frequency order: the bookmarks along the scope. */
    val bookmarks: List<Bookmark> = emptyList(),
    /** The decoder the engine runs and the panel drawn for it ([PanelState]). */
    val panel: PanelState = PanelState(),
    /** Decoder panels open by themselves when their decoder starts (the device's setting). */
    val autoOpen: Boolean = true,
    /** The receive passband's power, dBFS (the engine's `meter` feed, uncalibrated: not S-units). */
    val rxDb: Double? = null,
    /** The meter's calibrated reading, or why it has none (SMeter); empty from a server that says neither. */
    val sMeter: SMeter = SMeter(),
    /** The shared band-plan data, once read (Radio only): the strip and the band buttons. */
    val plan: PlanData? = null,
    /** The band button last pressed here (RadioBands.current: it stays lit while the dial is inside it). */
    val pressedBand: String? = null,
    /** DAB's station list, while the DAB area's panel is open (Radio only); null when it is closed. */
    val dab: DabUi? = null,
    /** "Scan all blocks" while it runs; null otherwise. */
    val dabScan: DabScanProgress? = null,
    /** What the last scan found, kept for this opening of the app. */
    val dabFound: List<DabFound> = emptyList(),
    /** The bands pressed last on this device for this box, newest first (RecentBands). */
    val recentBands: List<String> = emptyList(),
    /** Local frequencies, while their screen is open (Radio only); null when it is closed. */
    val local: LocalUi? = null,
    /** The meter's stereo flag in wfm, ten times a second (null outside wfm). */
    val meterStereo: Boolean? = null,
    /** Radio's FM station list (`api/fm/stations`) once read; [fmMissing] for a server without one (404). */
    val fmList: FmList? = null,
    val fmMissing: Boolean = false,
    /** Radio's DAB station list (`api/dab/stations`) once read; [dabListMissing] for a server without one. */
    val dabList: DabList? = null,
    val dabListMissing: Boolean = false,
    /** The server's sentence for a scan it refused, or a list it could not give. */
    val stationsProblem: String = "",
) {
    val dialHz: Double? get() = pendingDialHz ?: state.dialHz

    /**
     * The radio is making stereo now (status `audio.stereo`: Radio's wide-FM
     * pilot locked), for a stereo badge. Says nothing about whether this
     * device is playing two channels - [audioChannels] says that.
     */
    val stereo: Boolean get() = (meterStereo ?: status?.audio?.stereo) == true

    /**
     * Who holds the receiver so that tuning would be refused: a DAB session.
     * A band scan holds it too (`rxOwner` by "scan"), but Radio ends a scan
     * the moment anyone does anything and puts everything back, so a scan
     * never stops the operator.
     */
    val holder: RxOwner? get() = state.rxOwner?.takeIf { it.by != "scan" }

    /**
     * The modes to offer: the server's list less any it says this receiver
     * cannot do ([EngineState.modeMissing]: wide FM on an HL2), which the
     * pickers leave out or show disabled with the sentence rather than send
     * to be refused.
     */
    val offered: List<String> get() = modes.filter { state.modeMissing(it) == null }

    /** Radio's band buttons: the dataset's bands with buttons, less any the receiver says it cannot reach. Empty until the data is read. */
    val bandButtons: List<BandButton>
        get() = plan?.let { RadioBands.buttons(it.vocab, status?.rxRange?.first, status?.rxRange?.second, ListenAreas.ALL) }.orEmpty()

    /** The lit band button: the one last pressed while the dial is in it, else the server's band name or the dial. */
    val currentBand: BandButton? get() = RadioBands.current(bandButtons, state.band, dialHz, pressedBand)
}

/**
 * One NB or Radio app, open on this device: its state, the scope, receive
 * audio, tuning, and (phones and tablets only) the transmit lease.
 *
 * Feeds stop when the app is not in front ([foreground] false): a TV in
 * standby or a phone in a pocket should not pull 200 kB/s of spectrum and
 * audio from the controller, and a transmitter must not be left keyed by
 * a screen nobody can see (the [ptt] controller releases first).
 *
 * [canTransmit] is false on a TV, whatever its token allows: TV kinds never
 * ask for transmit (GUIDE 2), and no PTT controller exists for them at all.
 */
class EngineSession(
    val app: ShackApp,
    val controllerName: String,
    private val client: EngineClient,
    private val clientId: String,
    canTransmit: Boolean,
    private val sendScope: CoroutineScope,
    autoOpen: Boolean = true,
    /** Names this box to [bandMemory]: the controller and the app. */
    private val boxKey: String = app.id,
    /** Where Radio's band buttons remember this device's last spot on each band; null keeps nothing. */
    private val bandMemory: BandMemory? = null,
    /** Reads the shared band-plan data (Radio only); called once, off the main thread. */
    private val planData: (() -> PlanData)? = null,
) {
    val kind: Kind get() = app.kind
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _ui = MutableStateFlow(EngineUi(stepHz = Tuning.defaultStep(app.kind), autoOpen = autoOpen))
    val ui: StateFlow<EngineUi> = _ui.asStateFlow()

    val scopeData = Scope()
    /** SSTV pictures this session has seen; read under its own lock (lines arrive on the socket's thread). */
    val sstv = SstvReceiver()
    private val _scopeVersion = MutableStateFlow(0)
    val scopeVersion: StateFlow<Int> = _scopeVersion.asStateFlow()

    private val player = PcmPlayer()
    private var feeds: Job? = null
    private var socket: WebSocket? = null
    private var sending: Job? = null
    private var settle: Job? = null
    @Volatile private var target: Double? = null
    /** The server answered `?channels=2` with 400 bad_channels: ask it mono from now on ([audioChannelsToRequest]). */
    @Volatile private var stereoRefused = false
    /** Panels learnt from a mode's own events, for a decoder [DecoderPanel] has no name for. */
    private val seen = mutableMapOf<String, DecoderPanel>()
    /** This device's last spot on each band of this box (Radio); written back a moment after it settles. */
    private var spots: Map<String, BandSpot> = emptyMap()
    /** The DAB station this device last chose on each block of this box (block label -> service id). */
    private var dabServices: Map<String, String> = emptyMap()
    private var spotsSaved: Map<String, BandSpot> = emptyMap()
    private var saving: Job? = null

    val ptt: PttController? = if (canTransmit) PttController(
        EnginePttLink(client), clientId, scope, sendScope, System::currentTimeMillis) else null

    /**
     * Enable TX for the digital modes, on every device, a TV included: the
     * server decides who may (the Transmit level today; an opted-in TV
     * later, REMOTE-KEYS.md "Decided"), and a TV asking is refused with a
     * reason the screen shows. Voice PTT ([ptt]) stays phones and tablets only.
     */
    val enableTx = EnableTxController(EngineAutoTxLink(client), clientId, scope, sendScope, System::currentTimeMillis)

    init {
        scope.launch {
            runCatching { client.spec() }.onSuccess { spec ->
                val sp = EngineSpec.parse(spec)
                _ui.update {
                    it.copy(modes = sp.modes, dialMin = sp.dialMin, dialMax = sp.dialMax, hasNr = sp.hasNr, nrHelp = sp.nrHelp)
                }
            }
            // Once per opening: memories change at the station, rarely, and
            // an operator's new one shows the next time the app is opened.
            runCatching { client.memories() }.onSuccess { b -> _ui.update { it.copy(bookmarks = b) } }
        }
        if (kind == Kind.RADIO && planData != null) scope.launch {
            val (data, kept) = kotlinx.coroutines.withContext(Dispatchers.IO) {
                planData.invoke() to (bandMemory?.load(boxKey) ?: emptyMap())
            }
            val recent = kotlinx.coroutines.withContext(Dispatchers.IO) { bandMemory?.loadRecent(boxKey).orEmpty() }
            dabServices = kotlinx.coroutines.withContext(Dispatchers.IO) { bandMemory?.loadDab(boxKey).orEmpty() }
            spots = kept
            spotsSaved = kept
            _ui.update { it.copy(plan = data, recentBands = recent) }
            track(_ui.value.state)
        }
    }

    fun foreground(on: Boolean, reason: String = "") {
        if (!on) { ptt?.release(reason); enableTx.release(reason) }
        if (on && feeds == null) startFeeds()
        // DAB's session too: a TV in standby should not keep the server decoding a multiplex.
        if (!on) { stopFeeds(); saveSpots(); closeDab() }
    }

    private fun startFeeds() {
        feeds = scope.launch {
            launch { audioLoop() }
            launch { eventsLoop() }
            launch { statusLoop() }
        }
    }

    private fun stopFeeds() {
        feeds?.cancel()
        feeds = null
        socket?.cancel()
        socket = null
        player.stop()
        _ui.update { it.copy(connected = false) }
    }

    fun close() {
        // The session is the server's: stop it on the way out (fire and forget, on the send scope, which outlives this one).
        _ui.value.dab?.session?.let { id -> sendScope.launch { runCatching { client.decoderStop(id) } } }
        dabPoll?.cancel()
        ptt?.release("")
        enableTx.release("")
        stopFeeds()
        saveSpots()
        scope.cancel()
    }

    // --- feeds ----------------------------------------------------------

    /** The full state once per connection, then the socket's changes over it. */
    private suspend fun eventsLoop() {
        while (scope.isActive) {
            val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
            val span = _ui.value.span
            try {
                val st = client.state()
                _ui.update { u -> EngineState(st).let { s -> u.copy(state = s, problem = null, panel = u.panel.decoder(DecoderPanel.of(s.mode, seen), u.autoOpen)) } }
                track(_ui.value.state)
            } catch (e: Exception) {
                fail(e)
                delay(RETRY_MS)
                continue
            }
            // Radio's `stations` events (a band's list made anew) are not in the default set; NB has none.
            val kinds = listOf("state", "toast", "meter", "events", span.feed) + if (kind == Kind.RADIO) listOf("stations") else emptyList()
            socket = client.events(kinds, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _ui.update { it.copy(connected = true, problem = null) }
                }
                override fun onMessage(webSocket: WebSocket, text: String) = onEvent(EngineEvent.parse(text))
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (response != null) fail(failure(response.code, runCatching { response.body?.string() }.getOrNull().orEmpty()))
                    closed.complete(Unit)
                }
            })
            closed.await()
            _ui.update { it.copy(connected = false) }
            delay(RETRY_MS)
        }
    }

    private fun onEvent(e: EngineEvent) {
        when (e) {
            is EngineEvent.Hello -> _ui.update { it.copy(status = e.status) }
            is EngineEvent.State -> {
                // A list made anew also moves stationsAt: read it again, as the stations event does.
                val before = _ui.value.state.stationsAt
                if (e.changes.has("stationsAt")) scope.launch {
                    val after = _ui.value.state.stationsAt
                    for (band in listOf(Stations.FM, Stations.DAB)) if (after[band] != before[band]) refreshStations(band)
                }
                // A scan that ended without a new list (aborted, failed) changes the list's status: read that too.
                val scanning = _ui.value.state.bandScan
                if (scanning != null && e.changes.has("bandScan")) scope.launch {
                    if (_ui.value.state.bandScan?.band != scanning.band) refreshStations(scanning.band)
                }
                _ui.update { u ->
                    val s = u.state.merge(e.changes)
                    // The engine has caught up with the last tune: show its dial again.
                    val pending = u.pendingDialHz?.takeUnless { p -> target == null && s.dialHz?.let { abs(it - p) < 1 } == true }
                    // Band Activity learns where the dial is, from the engine's own dial
                    // (not a tune on its way): a band change or a jump beyond the mode's
                    // passband starts a new labelled section. A state that carries no dial
                    // (a change to something else) leaves it alone.
                    val decodes = s.dialHz?.let { dial -> u.decodes.retune(s.band, dial, s.mode, System.currentTimeMillis() / 1000.0) } ?: u.decodes
                    // The receiver given back: the "playing DAB" sentence left on the toast line no longer holds.
                    val was = u.state.rxOwner?.text
                    val toast = if (was != null && s.rxOwner == null && u.toast == was) null else u.toast
                    u.copy(state = s, pendingDialHz = pending, decodes = decodes, panel = u.panel.decoder(DecoderPanel.of(s.mode, seen), u.autoOpen),
                        toast = toast)
                }
                // On the session's own thread: the socket's messages arrive on OkHttp's, and the spots are not shared.
                if (kind == Kind.RADIO) scope.launch { track(_ui.value.state) }
            }
            is EngineEvent.Spectrum -> {
                // Only the feed asked for; a frame of the other in flight
                // across a span change would restart the waterfall twice.
                if (e.frame.kind == _ui.value.span.feed) {
                    scopeData.add(e.frame)
                    _scopeVersion.value = scopeData.version
                }
            }
            is EngineEvent.Toast -> _ui.update { it.copy(toast = e.text) }
            // Each meter item says afresh whether there is a calibration: one without `s` ends the reading.
            is EngineEvent.Meter -> _ui.update { it.copy(rxDb = e.rxDb ?: it.rxDb, sMeter = e.s, meterStereo = e.stereo) }
            // A band's list made anew: read again if this screen has it.
            is EngineEvent.StationsMade -> refreshStations(e.band)
            is EngineEvent.Decoded -> { learn(e); _ui.update { it.copy(decoded = it.decoded.add(e)) } }
            is EngineEvent.Slot -> { learn(e); _ui.update { it.copy(decodes = it.decodes.add(e.decode)) } }
            is EngineEvent.Sstv -> {
                learn(e)
                val started = synchronized(sstv) { sstv.apply(e.event) }
                _ui.update {
                    it.copy(sstvVersion = sstv.version, sstvStarts = it.sstvStarts + if (started) 1 else 0,
                        panel = if (started) it.panel.sstvStarted(it.autoOpen) else it.panel)
                }
            }
            else -> Unit
        }
    }

    /** A decoder this build has no name for shows what it is by its events; the panel follows once known. */
    private fun learn(e: EngineEvent) {
        val (mode, panel) = DecoderPanel.ofEvent(e) ?: return
        if (seen[mode] == panel) return
        seen[mode] = panel
        _ui.update { it.copy(panel = it.panel.decoder(DecoderPanel.of(it.state.mode, seen), it.autoOpen)) }
    }

    /** Status every 2 s: onAir and txError are status, not state, and the lease reads them. */
    private suspend fun statusLoop() {
        while (scope.isActive) {
            runCatching { client.status() }.onSuccess { st ->
                _ui.update { it.copy(status = st) }
                enableTx.onStatus(st.autoTx)
            }
            delay(2000)
        }
    }

    private suspend fun audioLoop() {
        while (scope.isActive) {
            try {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    // Stereo only from a server whose status says it understands
                    // the question. Asked afresh for each connection rather than
                    // taken from the hello or the 2 s loop: a reconnect is often
                    // a server restarted into another version, and one GET per
                    // connection is nothing. A status that fails asks mono.
                    val audio = (runCatching { client.status() }.getOrNull() ?: _ui.value.status)?.audio
                    val asked = audioChannelsToRequest(kind, audio, stereoRefused)
                    client.openAudioOrMono(asked) { stereoRefused = true }.use { r ->
                        // What the headers say came back, never what was asked for.
                        val fmt = PcmFormat.parse(r.header("Content-Type"), r.header("X-Audio-Format"))
                            ?: throw ShackException(r.code, "audio_format", "")
                        player.muted = _ui.value.muted
                        player.start(fmt.rate, fmt.channels)
                        _ui.update { it.copy(audioRate = fmt.rate, audioChannels = fmt.channels) }
                        val src = r.body!!.byteStream()
                        val frame = 2 * fmt.channels
                        val bytes = ByteArray(fmt.rate / 25 * frame)   // 40 ms
                        var carry = 0
                        while (isActive) {
                            val n = src.read(bytes, carry, bytes.size - carry)
                            if (n < 0) break
                            // Whole frames only: a read can end mid-sample or
                            // between L and R, and the rest waits for the next.
                            val have = carry + n
                            val usable = have - have % frame
                            if (usable > 0) player.write(pcm16le(bytes, 0, usable))
                            carry = have - usable
                            if (carry > 0) System.arraycopy(bytes, usable, bytes, 0, carry)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Audio comes back by itself; the events socket reports why
                // the app is unreachable, so this loop says nothing.
            }
            player.stop()
            _ui.update { it.copy(audioRate = 0, audioChannels = 0) }
            delay(RETRY_MS)
        }
    }

    private fun fail(e: Throwable) {
        val p = if (e is ShackException) Problem(e.http, e.message.orEmpty()) else Problem(null, e.message.orEmpty())
        _ui.update { it.copy(problem = p, connected = false) }
    }

    // --- controls -------------------------------------------------------

    /** [by] steps of the current step size. Each press moves the shown dial at once; the controller gets the latest. */
    fun tune(by: Int) {
        val u = _ui.value
        val from = u.dialHz ?: return
        if (held()) return
        sendDial(Tuning.stepped(from, u.stepHz, by, u.dialMin, u.dialMax, ListenAreas.origin(from, u.stepHz)))
    }

    /**
     * Whether a decoder session holds the receiver (`rxOwner`: DAB, SERVER-API
     * "DAB"), so the dial and mode are not the operator's: Radio would refuse
     * the set (409 receiver_busy), so none is sent, and the server's sentence
     * is the toast line instead. Stopping DAB gives the receiver back.
     */
    private fun held(): Boolean {
        val o = _ui.value.holder ?: return false
        _ui.update { it.copy(toast = o.text.ifEmpty { it.toast }) }
        return true
    }

    fun setDial(hz: Double) {
        if (held()) return
        sendDial(hz)
    }

    private fun sendDial(hz: Double) {
        target = hz
        _ui.update { it.copy(pendingDialHz = hz) }
        if (sending?.isActive == true) return
        // At most one set in flight; presses meanwhile coalesce into the
        // newest target, so holding a D-pad key does not queue a second
        // of stale retunes behind it.
        sending = scope.launch {
            while (true) {
                val hz2 = target ?: break
                target = null
                try {
                    client.set(JSONObject().put("vfo", hz2))
                } catch (e: Exception) {
                    // The receiver taken by a DAB session meanwhile: its sentence, not a lost connection.
                    if (e is ShackException && e.code == RECEIVER_BUSY) _ui.update { it.copy(toast = e.message) } else fail(e)
                    _ui.update { it.copy(pendingDialHz = null) }
                    target = null
                    break
                }
            }
            // An engine that lands somewhere else (a clamp, a band edge)
            // never reports the dial asked for: stop showing it after a
            // moment and show the engine's.
            settle?.cancel()
            settle = scope.launch {
                delay(1500)
                if (target == null) _ui.update { it.copy(pendingDialHz = null) }
            }
        }
    }

    /**
     * The mode, chosen from the engine's own list (`api/spec` choices). The
     * engine starts that mode's decoder, and its state message opens the
     * panel, as for a change made anywhere else.
     */
    fun setMode(mode: String) {
        if (mode == _ui.value.state.mode || held()) return
        scope.launch { control { client.set(JSONObject().put("mode", mode)) } }
    }

    /**
     * Tune to a bookmark: its dial and its mode in one `api/set`, all or
     * nothing, as the engine's own recall does - so choosing "FT8 20m"
     * lands on the dial with the FT8 decoder running, and its panel opens.
     * The dial moves on screen at once, as a D-pad tune does.
     */
    fun goTo(b: Bookmark) { if (!held()) goTo(b.hz, b.mode) }

    private fun goTo(hz: Double, mode: String) {
        target = null
        _ui.update { it.copy(pendingDialHz = hz) }
        scope.launch {
            val ok = control { client.set(JSONObject().put("vfo", hz).put("mode", mode)) }
            settle?.cancel()
            settle = scope.launch {
                if (ok) delay(1500)
                if (target == null) _ui.update { it.copy(pendingDialHz = null) }
            }
        }
    }

    /** The decoder panel shown again (OK row, or the phone's button), or put away (BACK on it). */
    fun openPanel() = _ui.update { it.copy(panel = it.panel.open()) }
    fun closePanel() = _ui.update { it.copy(panel = it.panel.close()) }

    /**
     * Band Activity's Clear: the displayed list only. Nothing is sent to the
     * engine and nothing else is touched - the logbook is not in this list
     * and the server keeps whatever it keeps.
     */
    fun clearDecodes() = _ui.update { it.copy(decodes = it.decodes.cleared()) }

    /** The device's setting changed: from now on, not for the panel already shown. */
    fun setAutoOpen(on: Boolean) = _ui.update { it.copy(autoOpen = on) }

    /**
     * A receive-side change that needs Control: a refusal for the level marks
     * the screen view only (as [nr]); anything else is the screen's problem
     * line. True when the engine took it.
     */
    private suspend fun control(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: ShackException) {
        when {
            e.http == 403 -> _ui.update { it.copy(viewOnly = true, toast = e.message) }
            // Refusals that are about the radio, not the link (a DAB session holds the receiver, on the air, too
            // slow a rate): the server's sentence on the toast line rather than the screen's connection problem.
            e.code in SAID_REFUSALS -> _ui.update { it.copy(toast = e.message.orEmpty().ifEmpty { e.code }) }
            else -> fail(e)
        }
        false
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        fail(e)
        false
    }

    fun step(by: Int) = _ui.update { it.copy(stepHz = Tuning.nextStep(kind, it.stepHz, by)) }

    fun mode(by: Int) {
        if (held()) return
        val u = _ui.value
        val next = Tuning.nextMode(u.offered, u.state.mode, by)
        if (next == u.state.mode) return
        scope.launch { runCatching { client.set(JSONObject().put("mode", next)) }.onFailure { fail(it) } }
    }

    /**
     * Radio's band up or down (a TV's channel keys, the controls row): the
     * same jump as the band buttons ([bandTo]), so a TV lands where a tablet
     * would. Before the band-plan data is read, the server's own band step
     * (its pad action, which uses its band stacks) rather than nothing.
     */
    fun band(by: Int) {
        val u = _ui.value
        if (u.plan == null || u.bandButtons.isEmpty()) {
            scope.launch { runCatching { client.padAction("band", "turn", by) }.onFailure { fail(it) } }
            return
        }
        RadioBands.step(u.bandButtons, u.state.band, u.dialHz, by, u.pressedBand)?.let { bandTo(it) }
    }

    /**
     * A band button: to this device's last spot on [b], or its usual place
     * for the mode Radio is in (RadioBands.target). The dial and the mode
     * in one `api/set`, as a bookmark; receive only, nothing keys.
     */
    fun bandTo(b: BandButton) {
        val u = _ui.value
        if (u.plan == null) return
        // Another device's DAB holds the receiver: say so rather than be refused.
        val owner = u.holder
        if (owner != null && owner.session != u.dab?.session && b.area?.id != ListenAreas.DAB) { held(); return }
        val recent = RecentBands.push(u.recentBands, b.name)
        if (recent != u.recentBands) {
            _ui.update { it.copy(recentBands = recent) }
            bandMemory?.let { m -> sendScope.launch(Dispatchers.IO) { m.saveRecent(boxKey, recent) } }
        }
        // The station list takes the scope's place now, so the bands stay in
        // reach while it is up: leaving DAB for another band ends its session
        // first, which gives the receiver back (Radio puts the old dial and
        // mode back), and only then tunes - a set sent before would be refused.
        if (b.area?.id != ListenAreas.DAB && u.dab != null) { closeDab { pressBand(b) }; return }
        pressBand(b)
    }

    private fun pressBand(b: BandButton) {
        val u = _ui.value
        val data = u.plan ?: return
        b.area?.let { area ->
            // A listening area: its spot, its mode (or the fallback when the
            // server has no such mode) and its step, every press - an area has
            // no FT8/SSB/CW places to step through. Receive only, as every
            // band button: a dial, a mode and a step, nothing keys.
            val spot = ListenAreas.target(area, u.offered)
            _ui.update { it.copy(stepHz = spot.stepHz, pressedBand = b.name) }
            // DAB is a decoder session on a block, not a demodulator: its
            // press opens the station list on the block the dial is near
            // (11D the first time), which tunes there itself.
            if (area.id == ListenAreas.DAB) { openDab(DabBlocks.at(u.dialHz) ?: DabBlocks.DEFAULT); return }
            if (spot.mode == null) sendDial(spot.hz) else goTo(spot.hz, spot.mode)
            return
        }
        _ui.update { it.copy(pressedBand = b.name) }
        val plan = u.state.planInUse.ifEmpty { null }
        // The band the dial is in already: its next place (FT8, SSB, CW), as a radio's band key pressed twice.
        val spot = if (u.currentBand == b) RadioBands.next(b.band, u.state.mode, u.bookmarks, data, plan) ?: return
        else RadioBands.target(b.band, spots[b.name], u.state.mode, u.bookmarks, data, plan)
        goTo(spot.hz, spot.mode)
    }

    // --- DAB: the station list (Dab.kt) --------------------------------------

    private var dabPoll: Job? = null

    /** The toast for a DAB session stopped from elsewhere, in the screen's words (set by the screen: this class has no resources). */
    var dabStoppedText: String = ""

    /**
     * The DAB panel on [block]: a `dab` decoder session there (any session
     * of ours on another block stopped first), playing the station this
     * device last chose on that block, and its feed polled once a second
     * while the panel is open. The session tunes the receiver itself and
     * holds it (SERVER-API "DAB": `rxOwner`), so nothing here sets the dial.
     * A refusal (receiver_busy, on_air, rate_too_low, a bad block) is the
     * panel's problem line in the server's words, not the screen's.
     */
    fun openDab(block: DabBlock, service: String? = null) {
        val old = _ui.value.dab?.session
        dabPoll?.cancel()
        dabScanJob?.cancel()
        dabScanJob = null
        val missing = _ui.value.dab?.missingText.orEmpty()
        _ui.update { it.copy(dabScan = null, dab = DabUi(block, missingText = missing), pressedBand = ListenAreas.DAB) }
        if (missing.isEmpty()) scope.launch {
            // The catalogue's word for what cannot play here (no faad2: DAB+), once per opening.
            runCatching { DabFeed.missingText(client.decoders()) }.onSuccess { t ->
                if (t.isNotEmpty()) _ui.update { u -> u.copy(dab = u.dab?.copy(missingText = t)) }
            }
        }
        dabPoll = scope.launch {
            old?.let { id -> stopAwaited(id) }
            val id = try {
                client.decoderStart(DabFeed.start(block, service ?: dabServices[block.label])).optString("id").ifEmpty { null }
            } catch (e: ShackException) {
                _ui.update { u -> u.copy(dab = u.dab?.copy(problem = e.message.orEmpty().ifEmpty { e.code })) }
                return@launch
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { u -> u.copy(dab = u.dab?.copy(problem = e.message.orEmpty())) }
                return@launch
            } ?: return@launch
            _ui.update { u -> u.copy(dab = u.dab?.copy(session = id)) }
            while (isActive) {
                val since = _ui.value.dab?.since ?: break
                try {
                    val p = client.decoderPoll(id, since)
                    _ui.update { u -> u.copy(dab = u.dab?.let { d -> DabFeed.apply(d, p) }) }
                } catch (e: ShackException) {
                    // Stopped from elsewhere (another device's Stop DAB, the radio stopping): the list goes,
                    // the receiver is the operator's again, and the toast line says so.
                    if (e.http == 404) {
                        _ui.update { u -> u.copy(dab = null, dabScan = null, toast = dabStoppedText) }
                        break
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                delay(DAB_POLL_MS)
            }
        }
    }

    /** The next or previous block (5A-13F), a new session there. */
    fun dabBlock(by: Int) {
        val d = _ui.value.dab ?: return
        val next = DabBlocks.step(d.block, by)
        if (next != d.block) openDab(next)
    }

    /** Play [sid]: only a service the server listed and can play (a dab+ one without faad2 carries a why and is not asked for). */
    fun dabPlay(sid: String) {
        val d = _ui.value.dab ?: return
        if (!DabFeed.canPlay(d, sid)) return
        val id = d.session ?: return
        scope.launch {
            if (control { client.decoderParams(id, JSONObject().put("service", sid)) }) {
                // Remembered per block, per box: the next start on this block plays it at once.
                dabServices = dabServices + (d.block.label to sid)
                val m = bandMemory
                val keep = dabServices
                if (m != null) sendScope.launch(Dispatchers.IO) { m.saveDab(boxKey, keep) }
            }
        }
    }

    /**
     * The panel put away: its session stopped, which gives the receiver back
     * (Radio puts the dial and mode from before back), and [then] once the
     * server has answered - a tune sent sooner would be refused as busy.
     */
    fun closeDab(then: (() -> Unit)? = null) {
        val id = _ui.value.dab?.session
        dabPoll?.cancel()
        dabPoll = null
        dabScanJob?.cancel()
        dabScanJob = null
        _ui.update { it.copy(dab = null, dabScan = null) }
        if (id == null) { then?.invoke(); return }
        scope.launch {
            stopAwaited(id)
            then?.invoke()
        }
    }

    /** Stop a session of ours and wait for the answer, even from a coroutine being cancelled: the next start must find the receiver free. */
    private suspend fun stopAwaited(id: String) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { runCatching { client.decoderStop(id) } }
    }

    /**
     * "Stop DAB" where the receiver is held (`rxOwner`): ours, the panel
     * closes; another device's, its session is stopped here when the
     * server lets this level do that, and its refusal said otherwise.
     */
    fun stopReceiverOwner() {
        val o = _ui.value.holder ?: return
        if (o.session == _ui.value.dab?.session) { closeDab(); return }
        scope.launch {
            try {
                client.decoderStopChecked(o.session)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ShackException) {
                _ui.update { it.copy(viewOnly = it.viewOnly || e.http == 403, toast = e.message.orEmpty().ifEmpty { e.code }) }
            } catch (e: Exception) {
                _ui.update { it.copy(toast = e.message.orEmpty()) }
            }
        }
    }

    private var dabScanJob: Job? = null

    // --- the station lists (Stations.kt) -------------------------------------

    /** Read Radio's FM list (the picker opening, or a list made anew). A 404 is an older Radio: no list, nothing more said. */
    fun loadFm() {
        scope.launch {
            try {
                val l = Stations.parseFm(client.fmStations())
                _ui.update { it.copy(fmList = l, fmMissing = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ShackException) {
                _ui.update { it.copy(fmMissing = e.http == 404, stationsProblem = if (e.http == 404) "" else e.message.orEmpty()) }
            } catch (_: Exception) {
            }
        }
    }

    /** Read Radio's DAB list; an older Radio's 404 makes Remote's own block scan the DAB picker's (Stations.dabSource). */
    fun loadDabList() {
        scope.launch {
            try {
                val l = Stations.parseDab(client.dabStations())
                _ui.update { it.copy(dabList = l, dabListMissing = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ShackException) {
                _ui.update { it.copy(dabListMissing = e.http == 404, stationsProblem = if (e.http == 404) "" else e.message.orEmpty()) }
            } catch (_: Exception) {
            }
        }
    }

    /** A band's list made anew (the `stations` event, stationsAt moving): read again the lists this screen has read. */
    private fun refreshStations(band: String) {
        val u = _ui.value
        if (band == Stations.FM && u.fmList != null) loadFm()
        if (band == Stations.DAB && u.dabList != null) loadDabList()
    }

    /**
     * Scan now (`POST api/scan`, Control). Radio's progress comes in state
     * (`bandScan`), the new list with the `stations` event. A refusal
     * (receiver_busy, unsupported) is said in its words.
     */
    fun scanNow(band: String) {
        if (kind != Kind.RADIO) return
        scope.launch {
            try {
                client.scan(Stations.scanBody(band))
                _ui.update { it.copy(stationsProblem = "") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ShackException) {
                _ui.update { it.copy(viewOnly = it.viewOnly || e.http == 403, stationsProblem = e.message.orEmpty().ifEmpty { e.code }) }
            } catch (e: Exception) {
                _ui.update { it.copy(stationsProblem = e.message.orEmpty()) }
            }
        }
    }

    /**
     * An FM station from the list: its best frequency in wide FM (FM where the
     * receiver cannot) with the 100 kHz step, as SERVER-API says to tune it.
     * Leaving DAB first, as any band change does; refused while a DAB session
     * of another device holds the receiver.
     */
    fun tuneFm(st: FmStation) {
        val u = _ui.value
        val owner = u.holder
        if (owner != null && owner.session != u.dab?.session) { held(); return }
        val mode = if (u.offered.isEmpty() || "wfm" in u.offered) "wfm" else "fm"
        val go = {
            _ui.update { it.copy(stepHz = 100_000, pressedBand = "fm-broadcast") }
            goTo(st.best.hz, mode)
        }
        if (u.dab != null) closeDab(go) else go()
    }

    /** A DAB service from the server's list: on this block's session, chosen live; elsewhere, a session there with it. */
    fun playListed(block: String, sid: String) {
        val b = DabBlocks.byLabel(block) ?: return
        val d = _ui.value.dab
        if (d != null && d.block == b && d.session != null && d.services.any { it.sid == sid }) { dabPlay(sid); return }
        openDab(b, sid)
    }

    // --- local frequencies (LocalFreqs) --------------------------------------

    /** Local frequencies' screen opened: the list read (again: the operator's own may have changed elsewhere). */
    fun openLocal() {
        _ui.update { it.copy(local = LocalUi(list = it.local?.list, loading = true)) }
        scope.launch { readLocal() }
    }

    fun closeLocal() = _ui.update { it.copy(local = null) }

    private suspend fun readLocal() {
        try {
            val l = LocalFreqs.parse(client.local(), System.currentTimeMillis())
            _ui.update { u -> u.copy(local = u.local?.copy(list = l, loading = false, problem = "")) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ShackException) {
            // 404: a Radio without the list yet (its API is not out); the screen says so rather than showing nothing.
            _ui.update { u -> u.copy(local = u.local?.copy(loading = false, problem = e.message.orEmpty(), missing = e.http == 404)) }
        } catch (e: Exception) {
            _ui.update { u -> u.copy(local = u.local?.copy(loading = false, problem = e.message.orEmpty())) }
        }
    }

    /** OK on a local frequency: its dial, its mode where the server offers it, its step; receive only, nothing keys. */
    fun tuneLocal(spot: LocalSpot) {
        val u = _ui.value
        val owner = u.holder
        if (owner != null && owner.session != u.dab?.session) { held(); return }
        val go = {
            spot.stepHz?.let { s -> _ui.update { it.copy(stepHz = s) } }
            if (spot.mode == null) sendDial(spot.hz) else goTo(spot.hz, spot.mode)
        }
        if (u.dab != null) closeDab(go) else go()
    }

    /** One of the operator's own added (Radio takes name, frequency and mode; the step is the channel's own); the list read again after. */
    fun addMine(name: String, hz: Double, mode: String) {
        val body = LocalFreqs.mineBody(name, hz, mode)
        scope.launch { if (localWrite { client.localAdd(body) }) readLocal() }
    }

    /** One of the operator's own changed: only what changed is sent (PUT). */
    fun editMine(was: LocalEntry, name: String, hz: Double) {
        val id = was.id ?: return
        val body = LocalFreqs.mineChanges(was, name, hz)
        if (body.length() == 0) return
        scope.launch { if (localWrite { client.localEdit(id, body) }) readLocal() }
    }

    /** The radius the list covers (Radio's `localRadiusKm`, Control level), then the list read again at it. */
    fun setLocalRadius(km: Int) {
        scope.launch { if (control { client.set(JSONObject().put("localRadiusKm", km)) }) readLocal() }
    }

    fun deleteMine(id: String) {
        scope.launch { if (localWrite { client.localDelete(id) }) readLocal() }
    }

    /** A change to the operator's own: a level refusal marks the screen view only, as any receive-side change does; any other is the list's problem line. */
    private suspend fun localWrite(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: ShackException) {
        _ui.update { u -> u.copy(viewOnly = u.viewOnly || e.http == 403, local = u.local?.copy(problem = e.message.orEmpty())) }
        false
    } catch (e: Exception) {
        _ui.update { u -> u.copy(local = u.local?.copy(problem = e.message.orEmpty())) }
        false
    }

    /**
     * "Scan all blocks" (DabScan): each Band III block in turn, one session
     * at a time - the dial to its centre, a session, polled until it syncs,
     * says there is nothing, or stays silent too long - and the ones with
     * services kept. The list shows each block as it is tried. When it ends
     * the list opens on the block it started from if that was found, else
     * the first found. A refusal of the decoder itself (no dab decoder, too
     * many sessions) stops the scan with the server's sentence; [dabScanStop]
     * stops it where it is.
     */
    fun dabScan() {
        val start = _ui.value.dab?.block ?: return
        val old = _ui.value.dab?.session
        dabPoll?.cancel()
        dabPoll = null
        dabScanJob?.cancel()
        dabScanJob = scope.launch {
            old?.let { id -> stopAwaited(id) }
            val found = mutableListOf<DabFound>()
            val total = DabBlocks.ALL.size
            for ((i, block) in DabBlocks.ALL.withIndex()) {
                _ui.update { it.copy(dab = DabUi(block, missingText = it.dab?.missingText.orEmpty()),
                    dabScan = DabScanProgress(i, total, block, found.toList())) }
                val id = try {
                    client.decoderStart(DabFeed.start(block)).optString("id").ifEmpty { null }
                } catch (e: ShackException) {
                    // A block the receiver cannot hold (outside_span) is passed over; a decoder that will not run anywhere ends the scan.
                    if (e.code !in SCAN_FATAL && e.http != 401 && e.http != 403) continue
                    _ui.update { u -> u.copy(dabScan = null, dabFound = found.toList(), dab = DabUi(start, problem = e.message.orEmpty().ifEmpty { e.code })) }
                    return@launch
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    continue
                } ?: continue
                _ui.update { u -> u.copy(dab = u.dab?.copy(session = id)) }
                val began = System.currentTimeMillis()
                try {
                    while (isActive) {
                        val d = _ui.value.dab ?: break
                        when (DabScan.verdict(d, System.currentTimeMillis() - began)) {
                            DabScan.Verdict.KEEP -> { found += DabScan.found(d); break }
                            DabScan.Verdict.SKIP -> break
                            DabScan.Verdict.WAIT -> {}
                        }
                        delay(DabScan.POLL_MS)
                        runCatching { client.decoderPoll(id, d.since) }.onSuccess { p ->
                            _ui.update { u -> u.copy(dab = u.dab?.let { x -> DabFeed.apply(x, p) }) }
                        }
                    }
                } finally {
                    // Waited for, even when Stop cancels the scan: one DAB session holds the receiver at a time.
                    stopAwaited(id)
                    _ui.update { u -> u.copy(dab = u.dab?.copy(session = null)) }
                }
            }
            _ui.update { it.copy(dabFound = found.toList(), dabScan = null) }
            openDab(DabScan.settle(start, found))
        }
    }

    /** Stop a scan where it is: what it found so far is kept, and the list opens on the block it had reached. */
    fun dabScanStop() {
        val p = _ui.value.dabScan ?: return
        dabScanJob?.cancel()
        dabScanJob = null
        _ui.update { it.copy(dabFound = p.found, dabScan = null) }
        openDab(p.block)
    }

    /** The engine's dial and mode, remembered against the band they are in (RadioBands.remember). */
    private fun track(s: EngineState) {
        val data = _ui.value.plan ?: return
        // In a listening area inside an amateur band (PMR446 in 70 cm) the
        // band would otherwise remember the PMR channel as its own spot.
        if (_ui.value.currentBand?.area != null) return
        val next = RadioBands.remember(spots, data.vocab, s.dialHz, s.mode)
        if (next == spots) return
        spots = next
        // Written a moment after the dial settles: a held D-pad key moves it ten times a second.
        saving?.cancel()
        saving = scope.launch { delay(SAVE_AFTER_MS); saveSpots() }
    }

    private fun saveSpots() {
        val m = bandMemory ?: return
        if (spots == spotsSaved) return
        spotsSaved = spots
        m.save(boxKey, spots)
    }

    fun span(s: Span) {
        if (s == _ui.value.span) return
        _ui.update { it.copy(span = s) }
        // The socket is reopened for the other feed: asking for both would
        // double what the controller sends for a picture of one.
        socket?.close(1000, null)
    }

    /** Read the logbook (on demand: it is a screen the operator opens, not a feed). */
    fun loadLog() {
        scope.launch { runCatching { client.log() }.onSuccess { l -> _ui.update { it.copy(log = l) } }.onFailure { fail(it) } }
    }

    /**
     * Noise reduction on or off (state key `nr`, Control). A refusal for the
     * level marks the screen view only, so the switch shows disabled rather
     * than failing again on every press.
     */
    fun nr(on: Boolean) {
        scope.launch {
            try {
                client.set(JSONObject().put("nr", on))
            } catch (e: ShackException) {
                if (e.http == 403) _ui.update { it.copy(viewOnly = true, toast = e.message) } else fail(e)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /**
     * Radio's SDR antenna input ([AntennaChoice]): the input's driver name,
     * or "" for the driver's default. Receive-side, so Control, not
     * Transmit (SERVER-API: `soapyAntenna` through api/set at control
     * level), and applied live by the server: the next state carries the
     * new `deviceStatus.antenna`, which is what the screen then shows -
     * nothing is assumed here before the radio says so. A refusal for the
     * level marks the screen view only, as the mode and NR do.
     */
    /**
     * Wide FM's de-emphasis, 50 or 75 µs (`wfmDeemphasis`, Control level):
     * a receive-side setting like NR, so a refusal marks the screen view only.
     */
    fun setDeemphasis(us: Int) {
        if (us !in WFM_DEEMPHASIS_US) return
        scope.launch { control { client.set(JSONObject().put("wfmDeemphasis", us.toDouble())) } }
    }

    /**
     * Calibrate Radio's S-meter: the signal now (the server's 3 s average)
     * is [dbm]. Control level, like any receive-side change; the server's
     * answer, or its refusal in its own words, is the screen's toast line.
     * [done] is the sentence to show when it takes.
     */
    fun calibrate(dbm: Double, done: String) {
        if (kind != Kind.RADIO) return
        scope.launch { if (calWrite { client.smeterCalibrate(dbm) }) _ui.update { it.copy(toast = done) } }
    }

    /** Clear the calibration for the dial's band and antenna (not the whole table). */
    fun clearCalibration(done: String) {
        if (kind != Kind.RADIO) return
        scope.launch { if (calWrite { client.smeterClear() }) _ui.update { it.copy(toast = done) } }
    }

    /**
     * A calibration request: a refusal is a sentence for the toast line
     * (409 cannot_calibrate says why: AGC, between bands, clipping), not the
     * screen's connection problem as [control] would make it; a level
     * refusal also marks the screen view only.
     */
    private suspend fun calWrite(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: ShackException) {
        _ui.update { it.copy(viewOnly = it.viewOnly || e.http == 403, toast = e.message.orEmpty().ifEmpty { e.code }) }
        false
    } catch (e: Exception) {
        _ui.update { it.copy(toast = e.message.orEmpty()) }
        false
    }

    fun setAntenna(name: String) {
        if (AntennaChoice.of(_ui.value.state)?.chosen == name) return
        scope.launch { control { client.set(JSONObject().put("soapyAntenna", name)) } }
    }

    /** A line for the screen's toast row, from the screen itself (a refusal it can tell before asking). */
    fun note(text: String) = _ui.update { it.copy(toast = text) }

    fun mute(on: Boolean) {
        player.muted = on
        _ui.update { it.copy(muted = on) }
    }

    companion object {
        const val RETRY_MS = 2000L
        const val SAVE_AFTER_MS = 2000L
        const val DAB_POLL_MS = 1000L
    }
}

/**
 * The remote lease over the store API (SERVER-API "Transmitting"), for the
 * shared [PttController]: the same dead-man, heartbeat and give-up rules as
 * a Classic box. Only on air under this client's own lease counts as keyed.
 */
class EnginePttLink(private val client: EngineClient) : PttLink {
    override suspend fun key(remote: String, force: Boolean): KeyAnswer {
        val st = client.ptt(true, remote)
        return when {
            st.onAir && st.leaseHolder == remote -> KeyAnswer(null)
            st.onAir -> {
                runCatching { client.ptt(false, remote) }
                KeyAnswer(LEASE_MISSING)
            }
            else -> KeyAnswer(st.txError.ifBlank { st.txBlocked.ifBlank { NOT_ON_AIR } })
        }
    }

    override suspend fun heartbeat(remote: String): Boolean = try {
        client.heartbeat(remote)
        true
    } catch (e: ShackException) {
        if (e.http == 409) false else throw e
    }

    override suspend fun unkey() {
        client.ptt(false, "")
    }

    companion object {
        // Sentinels the UI turns into catalog text (no user-visible literals here).
        const val LEASE_MISSING = "\u0000lease_missing"
        const val NOT_ON_AIR = "\u0000not_on_air"
    }
}

/** Decoder refusals that end a DAB scan rather than skip one block (the receiver taken, on the air, too slow a rate). */
private val SCAN_FATAL = setOf("decoder_unavailable", "too_many_sessions", "receiver_busy", "on_air", "rate_too_low")

/** Radio's refusal while a DAB session holds the receiver (SERVER-API "DAB"). */
const val RECEIVER_BUSY = "receiver_busy"

/** Refusals about the radio rather than the link: said on the toast line in the server's words. */
private val SAID_REFUSALS = setOf(RECEIVER_BUSY, "on_air", "rate_too_low", "out_of_range", "bad_params")

/** The de-emphasis choices Radio's server takes (openhamshack-nb ohsradio/state.py DEEMPHASIS_US). */
val WFM_DEEMPHASIS_US = listOf(50, 75)

/** The local-frequencies screen: the list once read, while it is being read, and the server's last word. */
data class LocalUi(val list: LocalList? = null, val loading: Boolean = false, val problem: String = "", val missing: Boolean = false)
