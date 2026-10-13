package com.openhamshack.remote

import android.app.Application
import android.icu.text.ListFormatter
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.openhamshack.remote.api.ApiException
import com.openhamshack.remote.api.OpenHamShackClient
import com.openhamshack.remote.api.Qo100
import com.openhamshack.remote.api.Qo100Waterfall
import com.openhamshack.remote.api.Status
import com.openhamshack.remote.api.TxOptions
import com.openhamshack.remote.camera.CameraSender
import com.openhamshack.remote.camera.SrtTarget
import com.openhamshack.remote.data.PlayerSettings
import com.openhamshack.remote.data.SavedBox
import com.openhamshack.remote.data.Store
import com.openhamshack.remote.player.RxPlayer
import com.openhamshack.remote.ptt.OpenHamShackPttLink
import com.openhamshack.remote.ptt.PttController
import com.openhamshack.remote.shack.StreamRelay
import com.openhamshack.remote.shack.shackHttp
import com.openhamshack.remote.ui.RxTune

sealed interface Link {
    data object Connecting : Link
    data object Up : Link
    data class Down(val why: String) : Link
}

/**
 * The QO-100 WB store app on an OpenHamShack controller (kind `qo100wb`):
 * the Classic box's API under the app's base, the device token instead of
 * a PIN session (REMOTE-API 2, "On the OpenHamShack platform").
 */
class PlatformApp(
    /** The controller's base URL plus the app's relative base, no trailing slash. */
    val baseUrl: String,
    val appName: String,
    val controllerName: String,
    /** The device token as the controller entry holds it now. */
    val token: () -> String?,
    /** The controller refused the token (revoked, or reinstalled): pair again. */
    val onSignedOut: () -> Unit,
)

/**
 * One open box: its status poll, its PTT lease, its receive player and the
 * camera sender. Everything that must stop when the operator walks away
 * stops in [foreground] (false) and [close].
 *
 * With [platform] set it is the QO-100 WB app on a controller: same screens,
 * no PIN (the level decides), no camera (the app's SRT ingest port is not
 * published by a controller), and the picture through a [StreamRelay] so it
 * is opened as this device, not anonymously.
 */
class BoxSession(
    private val app: Application,
    initial: SavedBox,
    clientId: String,
    private val store: Store,
    /** Outlives this session: key-downs sent as it closes still go out. */
    sendScope: CoroutineScope,
    val platform: PlatformApp? = null,
) {
    var box: SavedBox = initial
        private set
    val client = if (platform != null) OpenHamShackClient(platform.baseUrl, null, platform.token)
        else OpenHamShackClient(initial.address, initial.session)
    private val relay = platform?.let { StreamRelay(shackHttp, it.baseUrl, it.token) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()
    private val _link = MutableStateFlow<Link>(Link.Connecting)
    val link: StateFlow<Link> = _link.asStateFlow()
    // Events, not state: the same refusal twice must show twice, and
    // showing one must not depend on clearing it.
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One line for a snackbar: a refused change, a sign-in result. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _txOptions = MutableStateFlow<TxOptions?>(null)
    val txOptions: StateFlow<TxOptions?> = _txOptions.asStateFlow()

    /** The transmit settings panel's last word: a refusal, or what waits for the key-up. */
    data class TxNote(val text: String, val refused: Boolean)
    private val _txNote = MutableStateFlow<TxNote?>(null)
    val txNote: StateFlow<TxNote?> = _txNote.asStateFlow()

    val ptt = PttController(OpenHamShackPttLink(client), clientId, scope, sendScope, { SystemClock.elapsedRealtime() })
    val player = RxPlayer(app)
    val camera = CameraSender(app)

    /** The last /api/qo100 answer while the QO-100 page is shown; null until the first. */
    private val _qo100 = MutableStateFlow<Qo100?>(null)
    val qo100: StateFlow<Qo100?> = _qo100.asStateFlow()
    /** Built up from the answers' waterfall rows; touched only on the main thread. */
    val qo100Waterfall = Qo100Waterfall()
    private val _qo100WfVersion = MutableStateFlow(0)
    /** Changes whenever [qo100Waterfall] does. */
    val qo100WfVersion: StateFlow<Int> = _qo100WfVersion.asStateFlow()
    private var qo100Shown = false
    private var qo100Job: Job? = null

    private var playerSettings = PlayerSettings()
    private var foreground = false
    private var receiveShown = true
    private var pollJob: Job? = null
    /** The receiver whose stream plays: the one the box has as heard. */
    private var playRx = 1

    init {
        loadTxOptions()
    }

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            scope.launch { ptt.release("the phone lost its network connection") }
        }
    }

    init {
        app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(netCallback)
    }

    fun say(text: String) {
        _messages.tryEmit(text)
    }

    /**
     * The app is on screen (true) or not. Off: unkey, stop the camera and the
     * picture, stop polling - in that order, the transmitter first.
     */
    fun foreground(on: Boolean, reason: String = "the app went to the background") {
        foreground = on
        if (on) {
            startPolling()
            updatePlayer()
            updateQo100()
        } else {
            ptt.release(reason)
            camera.release()
            pollJob?.cancel()
            pollJob = null
            updateQo100()
            player.stop()
        }
    }

    /**
     * The QO-100 page is on screen (true) or not. Its poll of /api/qo100 is
     * what keeps the box's connection to BATC open, so it runs only while
     * the page shows and the app is in the foreground: leaving the page lets
     * the box drop the feed 20 s later, as the console's page does.
     */
    fun setQo100Shown(shown: Boolean) {
        qo100Shown = shown
        updateQo100()
    }

    private fun updateQo100() {
        if (!(foreground && qo100Shown)) {
            qo100Job?.cancel()
            qo100Job = null
            return
        }
        if (qo100Job?.isActive == true) return
        qo100Job = scope.launch {
            // Once a second, as the console's page does: BATC sends a frame
            // every 250 ms and the waterfall gains a row a second.
            while (isActive) {
                try {
                    val a = client.qo100(qo100Waterfall.seq)
                    a.waterfall?.let {
                        qo100Waterfall.add(it)
                        _qo100WfVersion.value = qo100Waterfall.version
                    }
                    _qo100.value = a
                } catch (e: Exception) {
                    // The console's words; the old picture is not kept, since
                    // drawn as if current it would mislead.
                    _qo100.value = (_qo100.value ?: Qo100.parse(JSONObject())).copy(
                        spectrum = emptyList(), signals = emptyList(), live = false, age = null,
                        error = app.getString(R.string.wb_qo100_not_answering), waterfall = null,
                    )
                }
                delay(1000)
            }
        }
    }

    /** The receive picture is on screen; off while the camera page is (no speaker into the microphone). */
    fun setReceiveShown(shown: Boolean) {
        receiveShown = shown
        updatePlayer()
    }

    fun setPlayerSettings(s: PlayerSettings) {
        playerSettings = s
        updatePlayer()
    }

    private fun updatePlayer() {
        val url = relay?.url(OpenHamShackClient.PATH_STREAM + if (playRx == 1) "" else "?rx=$playRx") ?: client.streamUrl(playRx)
        if (foreground && receiveShown) player.play(url, playerSettings) else player.stop()
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            // Once a second, as the console's own refresh() does.
            while (isActive) {
                refresh()
                delay(1000)
            }
        }
    }

    suspend fun refresh() {
        try {
            // Judge the session only by a poll that carried it: one already
            // in flight when the PIN was entered answers "not authorised",
            // and must not throw away the session that has just arrived.
            val sent = client.session
            val st = client.status()
            _status.value = st
            if (_link.value is Link.Down) player.reconnect()
            _link.value = Link.Up
            ptt.onStatus(st.onAir, st.txError)
            val heard = st.receivers.firstOrNull { it.heard }?.id ?: 1
            if (heard != playRx) {
                playRx = heard
                updatePlayer()
            }
            if (sent != null && sent == client.session && !st.auth.authorised && !st.auth.local) sessionEnded()
        } catch (e: ApiException) {
            if (platform != null && e.code == 401) platformSignedOut() else _link.value = Link.Down(e.message ?: app.getString(R.string.wb_did_not_answer))
        } catch (e: Exception) {
            _link.value = Link.Down(e.message ?: app.getString(R.string.wb_did_not_answer))
        }
    }

    // --- actions -----------------------------------------------------------

    fun login(pin: String, done: (String?) -> Unit) {
        if (platform != null) return done(null)
        scope.launch {
            try {
                val token = client.login(pin)
                saveBox(box.copy(session = token))
                refresh()
                done(null)
            } catch (e: Exception) {
                done(e.message ?: app.getString(R.string.wb_signin_failed))
            }
        }
    }

    /**
     * The box no longer honours this phone's session: the PIN was changed
     * or cleared at the box (which ends every remote session), or the
     * session was signed out elsewhere. Back to view only, saying so.
     */
    private suspend fun sessionEnded() {
        if (client.session == null) return
        ptt.release("the PIN session ended")
        client.session = null
        saveBox(box.copy(session = null))
        say(app.getString(R.string.wb_signed_out_by_box))
    }

    /**
     * The controller no longer honours this device's token (a 401: revoked,
     * or a controller reinstalled). The transmitter first, then pairing
     * again, which closes this session.
     */
    private fun platformSignedOut() {
        ptt.release("the controller signed this device out")
        platform?.onSignedOut?.invoke()
    }

    /**
     * Why this session cannot change something, in the operator's words:
     * on a controller the level (the gate's), on a Classic box the PIN (the
     * console's).
     */
    fun viewOnlyReason(st: Status? = _status.value): String {
        val auth = st?.auth ?: return app.getString(R.string.wb_platform_not_connected)
        if (!auth.platform) return auth.viewOnlyReason
        return app.getString(R.string.wb_platform_view_only, levelWord(auth.level))
    }

    /** Why transmitting is refused at this level, on a controller; '' when it is not. */
    fun txLevelReason(st: Status? = _status.value): String {
        val auth = st?.auth ?: return ""
        if (!auth.platform || auth.canTransmit) return ""
        return app.getString(R.string.wb_platform_needs_transmit, levelWord(auth.level))
    }

    private fun levelWord(level: String) = app.getString(com.openhamshack.remote.ui.levelRes(level))

    /** Forget the session on this phone only; the box keeps honouring it (a year). */
    fun forgetPin() {
        ptt.release("the PIN was forgotten on this phone")
        client.session = null
        scope.launch {
            saveBox(box.copy(session = null))
            refresh()
        }
    }

    /** End the session at the box as well (POST /api/logout), then forget it here. */
    fun signOut() {
        ptt.release("this phone signed out")
        scope.launch {
            runCatching { client.logout() }
            client.session = null
            saveBox(box.copy(session = null))
            refresh()
            say(app.getString(R.string.wb_signed_out))
        }
    }

    fun loadTxOptions() {
        scope.launch { runCatching { client.txOptions() }.onSuccess { _txOptions.value = it } }
    }

    /**
     * POST /api/tx/tune. A refusal (400) saves nothing and its sentence is
     * shown in the panel. While on air the box says which changes wait for
     * the next key-up (all but power), and the panel says so.
     */
    fun txTune(patch: JSONObject) = act {
        try {
            val st = client.txTune(patch)
            adopt(st)
            val later = st.txTune?.nextKeyUp.orEmpty()
            val powerNow = st.txTune?.changed?.containsKey("gainDb") == true
            _txNote.value = when {
                st.onAir && later.isNotEmpty() -> {
                    // The setting names as a locale's list ("frequency and
                    // power"), not joined with commas here; a key the app
                    // has no word for is shown as the box named it.
                    val words = later.map { k -> TX_KEY_WORDS[k]?.let { app.getString(it) } ?: k }
                    val list = ListFormatter.getInstance().format(words)
                    TxNote(app.getString(if (powerNow) R.string.wb_tx_next_keyup_power else R.string.wb_tx_next_keyup, list), false)
                }
                st.onAir && powerNow -> TxNote(app.getString(R.string.wb_tx_power_on_air), false)
                else -> null
            }
        } catch (e: ApiException) {
            if (e.code == 400) _txNote.value = TxNote(app.getString(R.string.wb_tx_not_saved, e.message ?: app.getString(R.string.wb_tx_refused)), true) else throw e
        }
    }

    fun tune(id: Int, frequencyMHz: Double? = null, symbolRateKs: Double? = null, offsetMHz: Double? = null) =
        act { adopt(client.tune(id, frequencyMHz, symbolRateKs, offsetMHz)) }

    /** One QO-100 tune (ui/Qo100Tune.kt: the console's click-to-tune), as any receive tune. */
    fun tune(t: RxTune) = tune(t.id, t.frequencyMHz, t.symbolRateKs, t.offsetMHz)

    fun hear(id: Int) = act {
        client.selectReceiver(id, _status.value?.rxEnlarged ?: false)
        refresh()
    }

    fun keepTransmitting() = act { adopt(client.keepTransmitting()) }

    /** Picture source "stream": what the camera sends is what the box transmits. */
    fun useStreamSource() = act {
        adopt(client.config(JSONObject().put("source", "stream")))
        say(app.getString(R.string.wb_source_now_stream))
    }

    fun switchStreamToSrt() = act { adopt(client.config(JSONObject().put("streamProtocol", "srt"))) }

    fun savePassphrase(p: String) = scope.launch { saveBox(box.copy(passphrase = p.ifBlank { null })) }

    /**
     * Where the camera sends: this box's address (never one the box reports),
     * and the Stream source's port, latency and passphrase from
     * /api/stream/larix - the passphrase only comes with the PIN session;
     * without one, the one typed on this phone. Null with the reason said
     * when sending would be pointless.
     */
    suspend fun srtTarget(): SrtTarget? {
        if (platform != null) {
            say(app.getString(R.string.wb_platform_no_camera))
            return null
        }
        val info = try {
            client.larix()
        } catch (e: Exception) {
            say(app.getString(R.string.wb_no_answer, e.message.orEmpty()))
            return null
        }
        if (info.protocol != "srt") {
            say(app.getString(R.string.wb_stream_not_srt, info.protocol.uppercase()))
            return null
        }
        val s = _status.value?.stream
        val port = info.port ?: s?.port ?: return null.also { say(app.getString(R.string.wb_stream_no_port)) }
        val pass = if (!info.secretSet) null else info.srtPassphrase ?: box.passphrase
        if (info.secretSet && pass.isNullOrBlank()) {
            say(app.getString(R.string.wb_stream_needs_passphrase))
            return null
        }
        return SrtTarget(box.address.host, port, pass, info.latencyMs ?: s?.latencyMs ?: 200)
    }

    /** A POST's answer: the new state, with the auth this session already knew. */
    private fun adopt(st: Status) {
        val prev = _status.value
        _status.value = if (st.authReported || prev == null) st else st.copy(auth = prev.auth)
    }

    private fun act(block: suspend () -> Unit) {
        val st = _status.value
        if (st != null && st.auth.viewOnly) {
            say(viewOnlyReason(st))
            return
        }
        scope.launch {
            try {
                block()
            } catch (e: ApiException) {
                // On a controller a 403 is the gate's level refusal, said in
                // its own words; only a 401 means the token is gone.
                if (platform != null && e.code == 401) platformSignedOut()
                else if ((e.code == 401 || e.code == 403) && client.session != null) sessionEnded()
                else say(e.message ?: app.getString(R.string.wb_refused))
            } catch (e: Exception) {
                say(app.getString(R.string.wb_no_answer, e.message.orEmpty()))
            }
        }
    }

    private suspend fun saveBox(b: SavedBox) {
        // On a controller [box] is the controller's entry, whose token the
        // controller's navigation owns: nothing here may write it back.
        if (platform != null) return
        box = b
        store.updateBox(b)
    }

    fun close() {
        foreground(false, "the box was closed on this phone")
        runCatching { app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(netCallback) }
        camera.destroy()
        player.release()
        relay?.close()
        scope.cancel()
    }
}

/** The box's key names, as the operator reads them: catalog entries. */
val TX_KEY_WORDS = mapOf(
    "frequencyMHz" to R.string.wb_tx_key_frequency, "symbolRateKs" to R.string.wb_tx_key_symbol_rate,
    "mode" to R.string.wb_tx_key_mode, "constellation" to R.string.wb_tx_key_modulation,
    "fec" to R.string.wb_tx_key_fec, "gainDb" to R.string.wb_tx_key_power,
)
