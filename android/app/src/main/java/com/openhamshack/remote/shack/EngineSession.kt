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
) {
    val dialHz: Double? get() = pendingDialHz ?: state.dialHz

    /**
     * The radio is making stereo now (status `audio.stereo`: Radio's wide-FM
     * pilot locked), for a stereo badge. Says nothing about whether this
     * device is playing two channels - [audioChannels] says that.
     */
    val stereo: Boolean get() = status?.audio?.stereo == true

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
            spots = kept
            spotsSaved = kept
            _ui.update { it.copy(plan = data) }
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
            socket = client.events(listOf("state", "toast", "meter", "events", span.feed), object : WebSocketListener() {
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
                _ui.update { u ->
                    val s = u.state.merge(e.changes)
                    // The engine has caught up with the last tune: show its dial again.
                    val pending = u.pendingDialHz?.takeUnless { p -> target == null && s.dialHz?.let { abs(it - p) < 1 } == true }
                    // Band Activity learns where the dial is, from the engine's own dial
                    // (not a tune on its way): a band change or a jump beyond the mode's
                    // passband starts a new labelled section. A state that carries no dial
                    // (a change to something else) leaves it alone.
                    val decodes = s.dialHz?.let { dial -> u.decodes.retune(s.band, dial, s.mode, System.currentTimeMillis() / 1000.0) } ?: u.decodes
                    u.copy(state = s, pendingDialHz = pending, decodes = decodes, panel = u.panel.decoder(DecoderPanel.of(s.mode, seen), u.autoOpen))
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
            is EngineEvent.Meter -> _ui.update { it.copy(rxDb = e.rxDb ?: it.rxDb, sMeter = e.s) }
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
        setDial(Tuning.stepped(from, u.stepHz, by, u.dialMin, u.dialMax, ListenAreas.origin(from, u.stepHz)))
    }

    fun setDial(hz: Double) {
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
                    fail(e)
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
        if (mode == _ui.value.state.mode) return
        scope.launch { control { client.set(JSONObject().put("mode", mode)) } }
    }

    /**
     * Tune to a bookmark: its dial and its mode in one `api/set`, all or
     * nothing, as the engine's own recall does - so choosing "FT8 20m"
     * lands on the dial with the FT8 decoder running, and its panel opens.
     * The dial moves on screen at once, as a D-pad tune does.
     */
    fun goTo(b: Bookmark) = goTo(b.hz, b.mode)

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
        if (e.http == 403) _ui.update { it.copy(viewOnly = true, toast = e.message) } else fail(e)
        false
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        fail(e)
        false
    }

    fun step(by: Int) = _ui.update { it.copy(stepHz = Tuning.nextStep(kind, it.stepHz, by)) }

    fun mode(by: Int) {
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
            if (spot.mode == null) setDial(spot.hz) else goTo(spot.hz, spot.mode)
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

    /**
     * The DAB panel on [block]: the dial to the block's centre, a `dab`
     * decoder session there (any session of ours on another block stopped
     * first), and its feed polled once a second while the panel is open.
     * A refusal (decoder_unavailable, too many sessions) is the panel's
     * problem line in the server's words, not the screen's.
     */
    fun openDab(block: DabBlock) {
        val old = _ui.value.dab?.session
        dabPoll?.cancel()
        _ui.update { it.copy(dab = DabUi(block), pressedBand = ListenAreas.DAB) }
        setDial(block.centreHz)
        dabPoll = scope.launch {
            old?.let { id -> runCatching { client.decoderStop(id) } }
            val id = try {
                client.decoderStart(DabFeed.start(block)).optString("id").ifEmpty { null }
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
                runCatching { client.decoderPoll(id, since) }.onSuccess { p ->
                    _ui.update { u -> u.copy(dab = u.dab?.let { d -> DabFeed.apply(d, p) }) }
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
        scope.launch { control { client.decoderParams(id, JSONObject().put("service", sid)) } }
    }

    /** The panel put away: its session stopped, so the server is not left decoding a block nobody is listening to. */
    fun closeDab() {
        val id = _ui.value.dab?.session
        dabPoll?.cancel()
        dabPoll = null
        _ui.update { it.copy(dab = null) }
        id?.let { scope.launch { runCatching { client.decoderStop(it) } } }
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

/** The de-emphasis choices Radio's server takes (openhamshack-nb ohsradio/state.py DEEMPHASIS_US). */
val WFM_DEEMPHASIS_US = listOf(50, 75)
