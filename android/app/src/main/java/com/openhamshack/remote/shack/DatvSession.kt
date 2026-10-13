package com.openhamshack.remote.shack

import android.app.Application
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
import org.json.JSONObject
import com.openhamshack.remote.R
import com.openhamshack.remote.data.PlayerSettings
import com.openhamshack.remote.player.RxPlayer

data class DatvUi(
    val status: DatvStatus? = null,
    val bands: List<DatvBand> = emptyList(),
    val connected: Boolean = false,
    val problem: Problem? = null,
)

/**
 * OpenHamShack DATV open on this device: the received picture (the
 * transport stream, through a [StreamRelay] so it is opened as this
 * device), the receiver's status once a second, and tuning by band,
 * channel, typed frequency and symbol rate.
 *
 * Receive only, on every device: the server has no route that keys, and
 * nothing here would send one. Tuning needs the Control level, which the
 * controller holds at its door; a refusal is shown in its words.
 *
 * As with the other kinds, nothing runs while the app is not in front: a
 * TV in standby should not pull a transport stream from the controller.
 */
class DatvSession(
    private val context: Application,
    val app: ShackApp,
    val controllerName: String,
    private val client: DatvClient,
    appBase: String,
    token: () -> String?,
    /** The controller refused the token: pair or sign in again. */
    private val onSignedOut: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _ui = MutableStateFlow(DatvUi())
    val ui: StateFlow<DatvUi> = _ui.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One line: a refused tune, a started receiver. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val player = RxPlayer(context)
    private val relay = StreamRelay(shackHttp, appBase.trimEnd('/'), token)
    private var settings = PlayerSettings()
    private var poll: Job? = null
    private var foreground = false

    init {
        scope.launch { runCatching { client.bands() }.onSuccess { b -> _ui.update { it.copy(bands = b) } } }
    }

    fun setPlayerSettings(s: PlayerSettings) {
        settings = s
        updatePlayer()
    }

    fun foreground(on: Boolean) {
        foreground = on
        if (on && poll == null) poll = scope.launch { pollLoop() }
        if (!on) {
            poll?.cancel()
            poll = null
            _ui.update { it.copy(connected = false) }
        }
        updatePlayer()
    }

    private fun updatePlayer() {
        if (foreground) player.play(relay.url("/api/rx/stream.ts"), settings) else player.stop()
    }

    private suspend fun pollLoop() {
        var lost = false
        while (scope.isActive) {
            try {
                val st = client.status()
                _ui.update { it.copy(status = st, connected = true, problem = null) }
                // The TCP connection behind the picture may have died with
                // the link: reopen once it answers again (REMOTE-CONTRACT 6).
                if (lost) player.reconnect()
                lost = false
                if (_ui.value.bands.isEmpty()) runCatching { client.bands() }.onSuccess { b -> _ui.update { it.copy(bands = b) } }
            } catch (e: ShackException) {
                if (e.http == 401) {
                    onSignedOut()
                    return
                }
                lost = true
                _ui.update { it.copy(connected = false, problem = Problem(e.http, e.message.orEmpty())) }
            } catch (e: Exception) {
                lost = true
                _ui.update { it.copy(connected = false, problem = Problem(null, e.message.orEmpty())) }
            }
            delay(1000)
        }
    }

    /** Tune to [freqHz] (and [sr] when given); a frequency in no band is refused here, before asking. */
    fun tune(freqHz: Double, sr: Int? = null) {
        val changes = datvTune(_ui.value.bands, freqHz, sr)
        if (changes == null) {
            say(context.getString(R.string.datv_no_band))
            return
        }
        act { client.set(changes) }
    }

    fun setRate(sr: Int) = act { client.set(JSONObject().put("rxSr", sr)) }

    /** A band's default channel: the engine moves the dial itself on a band change. */
    fun band(name: String) = act { client.set(JSONObject().put("band", name)) }

    fun start() = act {
        val r = client.start()
        r.optString("startError").takeIf { it.isNotBlank() }?.let { say(it) }
    }

    fun stop() = act { client.stop() }

    private fun act(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                // The answer to a set is only what changed; the next poll is
                // the whole truth, so ask for it now rather than in a second.
                runCatching { client.status() }.onSuccess { st -> _ui.update { it.copy(status = st) } }
            } catch (e: ShackException) {
                if (e.http == 401) onSignedOut() else say(e.message?.ifBlank { null } ?: context.getString(R.string.shack_problem_http, e.http))
            } catch (e: Exception) {
                say(context.getString(R.string.shack_problem_network))
            }
        }
    }

    private fun say(text: String) {
        _messages.tryEmit(text)
    }

    fun close() {
        foreground(false)
        player.release()
        relay.close()
        scope.cancel()
    }
}
