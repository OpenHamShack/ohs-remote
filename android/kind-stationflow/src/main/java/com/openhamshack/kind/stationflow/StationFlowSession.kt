package com.openhamshack.kind.stationflow

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.openhamshack.core.audio.PcmPlayer
import com.openhamshack.core.audio.pcm16le
import com.openhamshack.core.net.AuthLevel
import com.openhamshack.core.net.ReconnectingSocket
import com.openhamshack.core.net.SocketState
import com.openhamshack.kind.stationflow.api.ExtPreampNote
import com.openhamshack.kind.stationflow.api.Heard
import com.openhamshack.kind.stationflow.api.NotSignedIn
import com.openhamshack.kind.stationflow.api.RadioState
import com.openhamshack.kind.stationflow.api.Refused
import com.openhamshack.kind.stationflow.api.Spectrum
import com.openhamshack.kind.stationflow.api.SstvPicture
import com.openhamshack.kind.stationflow.api.StationFlowClient
import com.openhamshack.kind.stationflow.api.objects
import com.openhamshack.kind.stationflow.api.optLongOrNull
import com.openhamshack.kind.stationflow.api.optStringOrNull

/** The SSTV side: the pictures kept, and the one coming in. */
data class SstvState(
    val pictures: List<SstvPicture> = emptyList(),
    val receiving: Boolean = false,
    /** 0..1 through the picture coming in. */
    val progress: Float? = null,
    /** The picture coming in, as far as it has got. */
    val live: Bitmap? = null,
    /** Set when a picture finishes, so the screen can show it full screen. */
    val arrived: SstvPicture? = null,
    val decoderRunning: Boolean? = null,
)

/** Who holds the digital-modes decoder. */
data class DigiState(val mode: String? = null, val holder: String? = null, val modes: List<String> = emptyList())

/** The receive audio, in a word or two, for the screen. */
data class AudioState(val link: SocketState = SocketState.Connecting, val rate: Int = 0, val error: String? = null)

/**
 * One StationFlow, open on this screen: the sockets the TV listens on, the
 * receive audio, and the controls - receive only (see [StationFlowClient]).
 *
 * Everything runs only while [foreground] is on. Home, another app, the TV
 * going to standby: the sockets close and the audio stops at once, and come
 * back as they were when the app does. Nothing here holds the radio; the
 * module is the one client the radio allows, and this is one of its many
 * listeners.
 */
class StationFlowSession(
    val client: StationFlowClient,
    val title: String,
    /** What the host's sign-in allows: View listens only; Control tunes. Transmit is treated as Control. */
    val level: AuthLevel = AuthLevel.Control,
    /** The session stopped working (401): the host signs in again. */
    private val onSignedOut: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<RadioState?>(null)
    val state: StateFlow<RadioState?> = _state.asStateFlow()

    private val _pendingHz = MutableStateFlow<Long?>(null)
    /** A frequency asked for and not yet reported back by the radio: shown at once, in amber. */
    val pendingHz: StateFlow<Long?> = _pendingHz.asStateFlow()

    private val _step = MutableStateFlow<Long?>(null)
    /** The chosen tuning step; null until chosen, when the mode's default applies. */
    val step: StateFlow<Long?> = _step.asStateFlow()

    private val _messages = MutableSharedFlow<Said>(extraBufferCapacity = 8)
    /** One-line things to say: a refusal, a radio that is not there. The screen reads them in its language. */
    val messages: SharedFlow<Said> = _messages.asSharedFlow()

    val waterfall = Waterfall()

    private val _decodes = MutableStateFlow<List<Heard>>(emptyList())
    val decodes: StateFlow<List<Heard>> = _decodes.asStateFlow()
    private val _spots = MutableStateFlow<List<Heard>>(emptyList())
    val spots: StateFlow<List<Heard>> = _spots.asStateFlow()
    private val _aprs = MutableStateFlow<List<Heard>>(emptyList())
    val aprs: StateFlow<List<Heard>> = _aprs.asStateFlow()
    private val _digi = MutableStateFlow(DigiState())
    val digi: StateFlow<DigiState> = _digi.asStateFlow()
    private val _sstv = MutableStateFlow(SstvState())
    val sstv: StateFlow<SstvState> = _sstv.asStateFlow()

    val player = PcmPlayer()
    private val _audio = MutableStateFlow(AudioState())
    val audio: StateFlow<AudioState> = _audio.asStateFlow()
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private val _aprsChannel = MutableStateFlow<Long?>(null)
    /** This radio's APRS channel (the module's first), once known: what the APRS tab offers to tune to. */
    val aprsChannel: StateFlow<Long?> = _aprsChannel.asStateFlow()
    /** The last stations view, read again once the channel is known (see [loadAprsChannel]). */
    @Volatile private var aprsView: JSONObject? = null
    @Volatile private var audioPath = StationFlowClient.AUDIO
    /** Bytes of audio received on the current socket, for its acks (TV-API.md section 7). */
    @Volatile private var audioReceived = 0L
    @Volatile private var audioAckedAt = 0L
    private val tuneQueue = Channel<Long>(Channel.CONFLATED)
    private var running = false
    private var signedOut = false
    private val jobs = mutableListOf<Job>()
    private val pictures = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 12
    }

    private val stateSocket = socket("api/ws", onText = { t ->
        parse(t)?.let { o ->
            val st = RadioState.parse(o)
            scope.launch {
                _state.value = st
                _pendingHz.value?.let { p -> if (st.freqHz == p) _pendingHz.value = null }
            }
        }
    })

    private val scopeSocket: ReconnectingSocket = socket(StationFlowClient.SCOPE, onText = { t ->
        parse(t)?.let { o ->
            val sweep = Spectrum.parse(o)
            if (sweep != null) {
                // The main receiver's only: a sub-receiver sweep interleaved
                // into the same waterfall would draw two bands as one
                // (docs/QUESTIONS.md). Acked all the same.
                if (sweep.receiver == 0) waterfall.add(sweep)
                // Acknowledged once it is in the waterfall, which the screen
                // draws from on its next frame: the module then keeps at most
                // two frames in flight, so the waterfall is never behind.
                // Not tied to the Canvas itself, so a scope hidden under a
                // panel does not stall the socket.
                o.optLongOrNull("seq")?.let { seq -> scopeSocket.send("""{"ack":$seq}""") }
            } else {
                // The module's own sentence, from its catalog.
                o.optStringOrNull("error")?.let { say(Said.verbatim(it)) }
            }
        }
    })

    private val audioSocket: ReconnectingSocket = ReconnectingSocket(
        scope,
        open = { l -> client.socket(audioPath, l) },
        onText = { t -> parse(t)?.let { o -> audioHeader(o) } },
        onBytes = { b ->
            player.write(pcm16le(b.toByteArray()))
            audioReceived += b.size
            // Tell the module what has arrived, at most every 100 ms, so it
            // keeps no more than window_ms in flight and skips the rest - a
            // gap, never a growing delay (measured behind both of Dock360's
            // proxies: without acks a slightly slow client drifts seconds
            // behind).
            val now = System.currentTimeMillis()
            if (now - audioAckedAt >= 100) {
                audioAckedAt = now
                audioSocket.send("""{"ack":$audioReceived}""")
            }
        },
        onOpen = { player.flush(); audioReceived = 0; audioAckedAt = 0 },
        onUnauthorised = { signedOut("the audio was refused") },
    )

    private val digiSocket = socket("api/digi/ws", onText = { t ->
        val o = parse(t) ?: return@socket
        o.optJSONObject("state")?.let { s ->
            _digi.value = DigiState(s.optStringOrNull("mode"), s.optStringOrNull("holder"),
                s.optJSONArray("modes")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: _digi.value.modes)
        }
        o.optJSONArray("decodes")?.let { arr ->
            val rows = arr.objects().map { Heard.fromDecode(it) }
            if (rows.isNotEmpty()) {
                // Newest first; one list, a few hundred long at most.
                val seen = HashSet<String>()
                _decodes.value = (rows.reversed() + _decodes.value).filter { seen.add(it.key) }.take(MAX_DECODES)
            }
        }
    })

    private val aprsSocket = socket("api/aprs/ws", onText = { t ->
        val o = parse(t) ?: return@socket
        o.optJSONObject("state")?.let { readAprs(it) }
        // A packet: the stations list is the module's to work out, so ask
        // for it again rather than reimplement its grouping here.
        if (o.has("packets")) scope.launch { refreshAprs() }
    })

    private val sstvSocket = socket("api/sstv/ws", onText = { t ->
        val o = parse(t) ?: return@socket
        o.optJSONObject("state")?.let { s ->
            val running = s.optJSONObject("decoder")?.optStringOrNull("state")?.let { it != "stopped" }
            _sstv.value = _sstv.value.copy(pictures = SstvPicture.parseList(s.optJSONArray("pictures")), decoderRunning = running)
        }
        if (o.has("start") || o.has("rows") || o.has("line")) {
            val p = o.optDouble("progress", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
            _sstv.value = _sstv.value.copy(receiving = true, progress = p ?: _sstv.value.progress)
        }
        o.optJSONObject("picture")?.let { rec ->
            SstvPicture.parse(rec)?.let { pic ->
                _sstv.value = _sstv.value.copy(
                    pictures = listOf(pic) + _sstv.value.pictures.filter { it.id != pic.id },
                    receiving = false, progress = null, live = null, arrived = pic)
            }
        }
    })

    /** The audio socket's first message: the format, or why there is no audio. */
    private fun audioHeader(o: JSONObject) {
        val err = o.optStringOrNull("error")
        if (err != null) {
            _audio.value = _audio.value.copy(error = err)
            return
        }
        val rate = o.optInt("rate", 0)
        if (rate > 0) {
            player.start(rate)
            _audio.value = _audio.value.copy(rate = rate, error = null)
        }
    }

    private fun socket(path: String, onText: (String) -> Unit) = ReconnectingSocket(
        scope, open = { l -> client.socket(path, l) }, onText = onText,
        onUnauthorised = { signedOut("$path was refused") },
    )

    private val allSockets get() = listOf(stateSocket, scopeSocket, audioSocket, digiSocket, aprsSocket, sstvSocket)

    /** The state socket's health is the session's: it is what says whether StationFlow is there. */
    val link: StateFlow<SocketState> get() = stateSocket.state

    init {
        scope.launch {
            for (hz in tuneQueue) {
                control { client.setFrequency(hz) }
                // At most ~8 retunes a second however fast the key repeats;
                // the latest asked-for frequency always wins (CONFLATED).
                delay(120)
            }
        }
        // The audio socket's own state, for the screen; and a 404 on the
        // family's name falls back to 1.23.0's.
        scope.launch {
            audioSocket.state.collect { s ->
                if (s is SocketState.Down && s.httpStatus == 404 && audioPath != StationFlowClient.AUDIO_OLD) {
                    audioPath = StationFlowClient.AUDIO_OLD
                }
                if (s !is SocketState.Up) player.flush()
                _audio.value = _audio.value.copy(link = s)
            }
        }
    }

    /** On screen or not. Off stops every socket and the audio at once; on starts them again. */
    fun foreground(on: Boolean) {
        if (on == running || signedOut) return
        running = on
        if (on) {
            allSockets.forEach { it.start() }
            jobs += scope.launch { while (isActive) { refreshSpots(); delay(15_000) } }
            jobs += scope.launch { if (_aprsChannel.value == null) loadAprsChannel(); refreshAprs() }
            jobs += scope.launch { pollLivePicture() }
        } else {
            allSockets.forEach { it.stop() }
            jobs.forEach { it.cancel() }
            jobs.clear()
            player.stop()
            _pendingHz.value = null
        }
    }

    fun close() {
        foreground(false)
        scope.cancel()
    }

    // --- controls ------------------------------------------------------------

    fun setMuted(on: Boolean) {
        _muted.value = on
        player.muted = on
    }

    fun toggleMute() = setMuted(!_muted.value)

    fun setStep(step: Long) { _step.value = step }

    fun cycleStep() { _step.value = nextStep(effectiveStep()) }

    /** One step finer ([by] < 0) or coarser, stopping at the ends (rewind/fast-forward). */
    fun stepBy(by: Int) { _step.value = shiftStep(effectiveStep(), by) }

    fun effectiveStep(): Long = _step.value ?: defaultStep(_state.value?.shownMode)

    /** Left/right on the dial: [dir] steps, shown at once, sent at most every 120 ms. */
    fun tuneBy(dir: Int) {
        if (!mayControl()) return
        val from = _pendingHz.value ?: _state.value?.freqHz ?: return
        tuneTo(stepFrequency(from, effectiveStep(), dir))
    }

    fun tuneTo(hz: Long) {
        if (!mayControl()) return
        _pendingHz.value = hz
        tuneQueue.trySend(hz)
        // If the radio never reports it (refused, out of range), stop
        // showing it as pending; the refusal itself is said by [control].
        scope.launch {
            delay(2500)
            if (_pendingHz.value == hz) _pendingHz.value = null
        }
    }

    fun setMode(label: String) { if (mayControl()) scope.launch { control { client.setMode(label) } } }
    fun setFilter(filter: String) { if (mayControl()) scope.launch { control { client.setFilter(filter) } } }
    fun setVfo(vfo: String) { if (mayControl()) scope.launch { control { client.setVfo(vfo) } } }
    fun setSplit(on: Boolean) { if (mayControl()) scope.launch { control { client.setSplit(on) } } }

    fun cycleMode(by: Int = 1) {
        val st = _state.value ?: return
        cycle(quickModes(st.modes, st.dataModes), st.shownMode, by)?.let { setMode(it) }
    }

    fun cycleFilter() {
        val st = _state.value ?: return
        cycle(listOf("FIL1", "FIL2", "FIL3"), st.filter)?.let { setFilter(it) }
    }

    /**
     * The radio's P.AMP key. On a module with preamp modes it steps through
     * the whole settings - OFF, P.AMP, EXT, P.AMP+EXT on a 9700 - passing
     * over any the module refuses (EXT while the radio's External P.AMP menu
     * entry for this band is off); on an older module it is the level toggle
     * it always was (RadioState.nextPreamp). Nothing is sent before the radio
     * has reported its preamp. Receive only; never keys.
     */
    fun cyclePreamp() {
        val st = _state.value ?: return
        if (!mayControl()) return
        val next = st.nextPreamp() ?: return
        // Passing over EXT without a word would read as a key that cannot reach it.
        if (next.skippedExt) when (st.extPreampNote) {
            ExtPreampNote.MenuOff -> say(Said(R.string.preamp_ext_menu_off))
            ExtPreampNote.MenuUnread -> say(Said(R.string.preamp_ext_menu_unread))
            ExtPreampNote.None -> Unit
        }
        scope.launch {
            control {
                if (next.mode != null) client.setPreampMode(next.mode) else client.setPreamp(next.level ?: 0)
            }
        }
    }

    /**
     * One preamp setting picked from the settings overlay (radio/Choices.kt preampChoices): "mode:<i>"
     * for a module with preamp modes, "level:<n>" for an older one. Receive only.
     */
    fun setPreampChoice(key: String) {
        if (!mayControl()) return
        val n = key.substringAfter(':').toIntOrNull() ?: return
        scope.launch { control { if (key.startsWith("mode:")) client.setPreampMode(n) else client.setPreamp(n) } }
    }

    /**
     * Zoom the radio's scope one step of the model's span ladder. A scope in
     * fixed mode shows its edges, not a span, so zooming puts it in centre
     * mode first (TV-API 8a), in the same request. At either end of the
     * ladder nothing is sent, and nothing is said: the zoom just stops.
     */
    fun zoomScope(zoomIn: Boolean) {
        val st = _state.value ?: return
        val caps = st.scopeCaps ?: return
        val now = sentSpan.current() ?: st.scopeSettings?.spanHz
        val span = com.openhamshack.kind.stationflow.radio.zoomSpan(caps, now, zoomIn) ?: return
        val body = JSONObject().put("span_hz", span)
        if (st.scopeSettings?.mode == "fixed") body.put("mode", "center")
        sentSpan.sent(span)
        setScope(body)
    }

    /** The scope's reference level one press up or down (1 dB), inside the model's range. */
    fun stepScopeRef(up: Boolean) {
        val st = _state.value ?: return
        val caps = st.scopeCaps ?: return
        val now = sentRef.current() ?: st.scopeSettings?.refDb
        val ref = com.openhamshack.kind.stationflow.radio.refStep(caps, now, up) ?: return
        sentRef.sent(ref)
        setScope(JSONObject().put("ref_db", ref))
    }

    /**
     * The module reads the scope settings back about once a second, so three
     * quick presses would each start from the same old value and send it
     * three times. What was last sent counts as where it is for a moment.
     */
    private class Recent<T : Any> {
        private var value: T? = null
        private var at = 0L
        fun sent(v: T) { value = v; at = System.currentTimeMillis() }
        fun current(): T? = value.takeIf { System.currentTimeMillis() - at < 1500 }
    }
    private val sentSpan = Recent<Long>()
    private val sentRef = Recent<Double>()

    /** Any of the scope's settings (TV-API 8a), as the settings panel picks them. */
    fun setScope(settings: JSONObject) {
        if (!mayControl()) return
        scope.launch { control { client.setScope(settings) } }
    }

    /** The module's NR2 on the receive audio: on/off, or its level 0-100. */
    fun setNr2(on: Boolean? = null, level: Int? = null) {
        if (!mayControl()) return
        scope.launch { control { client.setNr2(on, level?.coerceIn(0, 100)) } }
    }

    /** One attenuator setting, dB (0 off), picked from the settings overlay. */
    fun setAtt(db: Int) { if (mayControl()) scope.launch { control { client.setAtt(db) } } }

    /** The radio's ATT: off, then each step the model lists (one, 10 dB, on a 9700), round again. */
    fun cycleAtt() {
        val st = _state.value ?: return
        if (!mayControl() || st.attenuators.isEmpty()) return
        val steps = listOf(0) + st.attenuators
        val now = st.att ?: return
        val next = steps[(steps.indexOf(now).coerceAtLeast(0) + 1) % steps.size]
        scope.launch { control { client.setAtt(next) } }
    }

    /** Band up/down: to the next band's button, by the module's own band list. */
    fun cycleBand(by: Int = 1) {
        val st = _state.value ?: return
        val here = bandOf(st.bands, _pendingHz.value ?: st.freqHz)
        cycle(st.bands, here, by)?.let { tuneTo(it.hz) }
    }

    /** OK on a decode, a spot or an APRS station: its dial and mode. */
    fun tuneToHeard(h: Heard) {
        val hz = h.tuneHz ?: run {
            say(h.call?.let { Said(R.string.session_no_frequency_for, it) } ?: Said(R.string.session_no_frequency))
            return
        }
        tuneWithMode(hz, h.tuneMode, h.call)
    }

    /**
     * The APRS tab's own key: the radio's APRS channel, in FM. Brian pressed
     * OK on that tab on the Fire TV (10 October 2026) and nothing tuned: with
     * no stations heard yet there were no rows, and the one key there started
     * the receiver, which by the module's rule moves nothing (its "tuning is
     * two buttons" note in app/aprs/web.py). A receiver on 145.500 USB hears
     * no APRS, so a tab saying "OK tunes to the channel" needs a key that
     * does, whether or not anything has been heard. The label says both
     * halves, frequency and FM, so it does only what it says.
     */
    fun tuneToAprsChannel() {
        val hz = _aprsChannel.value ?: run { say(Said(R.string.session_no_frequency)); return }
        tuneWithMode(hz, APRS_MODE, null)
    }

    private fun tuneWithMode(hz: Long, mode: String?, call: String?) {
        if (!mayControl()) return
        scope.launch {
            // The mode first, so the radio is never on the new frequency in the old mode; a refused mode is
            // said by [control], and the frequency is still asked for.
            if (mode != null && mode != _state.value?.shownMode) control { client.setMode(mode) }
            tuneTo(hz)
            // Callsign, frequency and mode are all data: one placeholder.
            say(Said(R.string.session_tuned_to, listOfNotNull(call, formatHz(hz), mode).joinToString(" ")))
        }
    }

    fun startDecoder(mode: String) {
        if (mayControl()) scope.launch { control { client.startDecoder(mode) }?.let { say(Said(R.string.session_decoding, mode)) } }
    }

    fun startSstv() {
        if (mayControl()) scope.launch { control { client.startSstv() }?.let { say(Said(R.string.session_sstv_started)) } }
    }

    fun startAprs() {
        if (mayControl()) scope.launch { control { client.startAprs() }?.let { say(Said(R.string.session_aprs_started)) } }
    }

    fun clearArrived() { _sstv.value = _sstv.value.copy(arrived = null) }

    /** An SSTV picture, from the cache or the module. Null if it cannot be had. */
    suspend fun picture(p: SstvPicture): Bitmap? {
        synchronized(pictures) { pictures[p.id] }?.let { return it }
        val bytes = runCatching { client.getBytes(p.path) }.getOrNull() ?: return null
        val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } ?: return null
        synchronized(pictures) { pictures[p.id] = bmp }
        return bmp
    }

    /** The level the module gave this session; View says why and refuses. */
    private fun mayControl(): Boolean {
        val allowed = _state.value?.auth?.effective ?: level
        if (allowed.canControl) return true
        say(Said(R.string.session_view_only))
        return false
    }

    private suspend fun <T> control(block: suspend () -> T): T? = try {
        block()
    } catch (e: NotSignedIn) {
        signedOut(e.message ?: "not signed in"); null
    } catch (e: Refused) {
        say(when (e.status) {
            503 -> Said(R.string.session_no_radio)
            403 -> Said(R.string.session_not_allowed, e.message.orEmpty())
            // The module's own sentence, from its catalog: shown as sent.
            else -> e.message?.let { Said.verbatim(it) } ?: Said(R.string.session_refused)
        }); null
    } catch (e: Exception) {
        say(Said(R.string.session_no_answer, e.message ?: e.javaClass.simpleName)); null
    }

    private fun signedOut(why: String) {
        if (signedOut) return
        signedOut = true
        scope.launch {
            foreground(false)
            Log.i(TAG, "signed out: $why")
            onSignedOut(why)
        }
    }

    private fun say(what: Said) { _messages.tryEmit(what) }

    // --- polling -------------------------------------------------------------

    private suspend fun refreshSpots() {
        val o = runCatching { client.getJson("api/log/spots") }.getOrNull() ?: return
        _spots.value = o.optJSONArray("spots").objects().mapNotNull { Heard.fromSpot(it) }
    }

    private var aprsFetch: Job? = null
    private fun refreshAprs() {
        if (aprsFetch?.isActive == true) return
        aprsFetch = scope.launch {
            runCatching { client.getJson("api/aprs") }.getOrNull()?.let { readAprs(it) }
            delay(1500)   // a burst of packets is one fetch
        }
    }

    private fun readAprs(view: JSONObject) {
        aprsView = view
        _aprs.value = view.optJSONArray("stations").objects().mapNotNull { Heard.fromAprs(it, _aprsChannel.value) }
    }

    /**
     * The APRS channel for this radio, for the tab's key and for tuning to a
     * station whose record has no frequency (the module's records have none).
     * The stations socket can answer before this does, and stations read
     * without the channel have nothing to tune to - OK on them said "no
     * frequency" - so they are read again once it is known.
     */
    private suspend fun loadAprsChannel() {
        val o = runCatching { client.getJson("api/aprs/channels") }.getOrNull() ?: return
        val list = o.optJSONArray("channels").objects()
        _aprsChannel.value = list.firstNotNullOfOrNull { it.optLongOrNull("hz") ?: it.optLongOrNull("freq_hz") }
        aprsView?.let { readAprs(it) }
    }

    /** While a picture comes in, its progress as a picture: live.png every 2 s. */
    private suspend fun pollLivePicture() {
        while (scope.isActive) {
            delay(2000)
            if (!_sstv.value.receiving) continue
            val bytes = runCatching { client.getBytes("api/sstv/live.png") }.getOrNull()
            if (bytes == null) {
                _sstv.value = _sstv.value.copy(receiving = false, live = null, progress = null)
                continue
            }
            val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            if (bmp != null && _sstv.value.receiving) _sstv.value = _sstv.value.copy(live = bmp)
        }
    }

    private fun parse(t: String): JSONObject? = runCatching { JSONObject(t) }.getOrNull()

    companion object {
        private const val TAG = "StationFlow"
        const val MAX_DECODES = 300
        /** What APRS is heard in: the module's own APRS mode (channels.WANT_MODE), as its rows already tune. */
        const val APRS_MODE = "FM"
    }
}
