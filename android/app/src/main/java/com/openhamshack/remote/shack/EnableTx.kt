package com.openhamshack.remote.shack

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/*
 * Enable TX: the NB/Radio store server's automatic transmit for the digital
 * modes (openhamshack-nb docs/SERVER-API.md "Automatic transmit", main f3bbac2),
 * as WSJT-X's Enable TX with CAT PTT. This device arms it and chooses what
 * goes - call CQ, answer a decode, or send a line in a text mode - and the
 * server keys each over itself, through PTT's own path and checks.
 *
 * The arm is a lease, like PTT's: this device heartbeats it every second,
 * between overs as well as during them, and the server disarms after 5 s of
 * silence. So a TV that sleeps, a phone in a pocket, or a lost network all
 * end it without anyone having to.
 */

/** `status.autoTx`: what the server's Enable TX is doing. */
data class AutoTxStatus(
    val armed: Boolean,
    /** The client id holding it; this device's own id when it armed. */
    val remote: String?,
    val mode: String,
    /** A CQ or a QSO is under way: overs are due. */
    val active: Boolean,
    val dx: String,
    /** The next over's message (FT8/FT4/Q65). */
    val next: String,
    /** Why it last went off. */
    val why: String,
    val sequenced: List<String>,
    val text: List<String>,
) {
    companion object {
        fun parse(o: JSONObject) = AutoTxStatus(
            armed = o.optBoolean("armed"),
            remote = o.optString("remote").takeUnless { o.isNull("remote") || it.isEmpty() },
            mode = if (o.isNull("mode")) "" else o.optString("mode"),
            active = o.optBoolean("active"),
            dx = if (o.isNull("dx")) "" else o.optString("dx"),
            next = o.optString("next"),
            why = o.optString("why"),
            sequenced = o.optJSONArray("sequenced").strings(),
            text = o.optJSONArray("text").strings(),
        )

        private fun org.json.JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

        /** The modes the server sequences (CQ, replies) and the line modes, as openhamshack-nb f3bbac2 has them. */
        val SEQUENCED = listOf("ft8", "ft4", "q65")
        val LINES = listOf("psk31", "psk63", "psk125", "rtty", "cw")
    }
}

/** What to send with the arm. */
sealed interface TxChoice {
    /** Call CQ in the soonest slot, then every slot of that parity. */
    data object Cq : TxChoice
    /** Answer this decode, as the desktop's double-click does. */
    data class Reply(val decode: SlotDecode) : TxChoice
    /** One line, now, in a text mode. */
    data class Line(val text: String) : TxChoice

    /** The body of `POST api/autotx` for [remote]. */
    fun body(remote: String): JSONObject = JSONObject().put("on", true).put("remote", remote).also { b ->
        when (this) {
            Cq -> b.put("cq", true)
            is Reply -> b.put("reply", JSONObject().put("text", decode.text).put("slot", decode.slot).put("snr", decode.snr)
                .put("df", decode.df).put("mode", decode.mode).apply {
                    if (decode.sender.isNotEmpty()) put("call", decode.sender)
                    if (decode.grid.isNotEmpty()) put("grid", decode.grid)
                })
            is Line -> b.put("text", text)
        }
    }
}

/** A refusal as the server gave it: its code for the screen to explain, its sentence to show. */
data class TxRefusal(val code: String, val text: String, val http: Int?)

/** What [EnableTxController] needs of the server; the tests use a fake. */
interface AutoTxLink {
    /** Arm and choose. The server's autoTx after it; throws [ShackException] with its refusal. */
    suspend fun arm(remote: String, choice: TxChoice): AutoTxStatus?
    /** true: still armed for us (or on air under our lease). false: the server has disarmed (409). Throws on network trouble. */
    suspend fun heartbeat(remote: String): Boolean
    /** Disarm, and end any over on the air. */
    suspend fun disarm()
}

class EngineAutoTxLink(private val client: EngineClient) : AutoTxLink {
    override suspend fun arm(remote: String, choice: TxChoice): AutoTxStatus? = client.autotx(choice.body(remote)).autoTx

    override suspend fun heartbeat(remote: String): Boolean = try {
        client.heartbeat(remote)
        true
    } catch (e: ShackException) {
        if (e.http == 409) false else throw e
    }

    override suspend fun disarm() {
        client.autotx(JSONObject().put("on", false))
    }
}

enum class EnableTxPhase { Idle, Arming, Armed }

data class EnableTxState(
    val phase: EnableTxPhase = EnableTxPhase.Idle,
    /** Why it was refused or ended; shown until the next arm. */
    val error: TxRefusal? = null,
    /** Heartbeats are failing but the give-up time has not passed. */
    val linkTrouble: Boolean = false,
    /** The last choice sent (what the screen says is going on). */
    val choice: TxChoice? = null,
)

/**
 * Enable TX held by this device, with its heartbeat. The rule is
 * PttController's: **the safe direction is off**. It never re-arms by
 * itself; every reason to stop sends a disarm, and the server's own 5 s
 * lease is the backstop if that never arrives.
 *
 * - [enable] arms with a choice, then heartbeats every second.
 * - A heartbeat answered 409: the server already disarmed (QSO done, a
 *   refused over, the watchdog, someone at the station): idle at once.
 * - Heartbeats failing for [giveUpMs]: give up, say so, send a disarm.
 * - Two status polls in a row saying not armed for us, after
 *   [statusGraceMs]: believe the server, with its reason.
 * - [release] (background, screen off, leaving the screen): disarm only if
 *   this device armed it.
 *
 * Disarms go out on [sendScope], which outlives the session's scope.
 */
class EnableTxController(
    private val link: AutoTxLink,
    val remoteId: String,
    private val scope: CoroutineScope,
    private val sendScope: CoroutineScope,
    private val now: () -> Long,
    private val beatIntervalMs: Long = 1000,
    private val giveUpMs: Long = 3000,
    private val statusGraceMs: Long = 3000,
) {
    private val _state = MutableStateFlow(EnableTxState())
    val state: StateFlow<EnableTxState> = _state.asStateFlow()

    private var wanted = false
    private var beatJob: Job? = null
    private var armedAt = 0L
    private var notArmedPolls = 0

    /** Arm with [choice], or (already armed) choose again. */
    fun enable(choice: TxChoice) {
        val was = _state.value.phase
        if (was == EnableTxPhase.Arming) return
        wanted = true
        _state.value = EnableTxState(if (was == EnableTxPhase.Armed) EnableTxPhase.Armed else EnableTxPhase.Arming, choice = choice)
        scope.launch {
            val refusal: TxRefusal? = try {
                link.arm(remoteId, choice)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: ShackException) {
                TxRefusal(e.code, e.message.orEmpty(), e.http)
            } catch (e: Exception) {
                // The request may have reached the server and only the answer been lost: make sure.
                sendDisarm()
                TxRefusal(NO_ANSWER, e.message.orEmpty(), null)
            }
            if (!wanted) {
                // Stopped while the arm was on its way.
                sendDisarm()
                return@launch
            }
            if (refusal != null) {
                // A refused choice while armed (no call in that message) leaves
                // the arm as it was on the server; this side stops all the same:
                // the operator chose something that did not happen.
                if (was == EnableTxPhase.Armed) sendDisarm()
                stopLocally(refusal)
                return@launch
            }
            armedAt = now()
            notArmedPolls = 0
            _state.value = EnableTxState(EnableTxPhase.Armed, choice = choice)
            if (beatJob?.isActive != true) startHeartbeats()
        }
    }

    /** The operator's stop: always sends a disarm. */
    fun stop() {
        stopLocally(null)
        sendDisarm()
    }

    /** A reason outside the operator's hand (background, screen off, leaving). Only if this device armed it. */
    fun release(reason: String) {
        if (_state.value.phase == EnableTxPhase.Idle && !wanted) return
        stopLocally(TxRefusal(RELEASED, reason, null))
        sendDisarm()
    }

    /** Every status poll: the server has the last word. */
    fun onStatus(auto: AutoTxStatus?) {
        if (_state.value.phase != EnableTxPhase.Armed) return
        if (auto != null && auto.armed && auto.remote == remoteId) {
            notArmedPolls = 0
            return
        }
        if (now() - armedAt < statusGraceMs) return
        if (++notArmedPolls >= 2) {
            stopLocally(TxRefusal(SERVER_OFF, auto?.why.orEmpty(), null))
            sendDisarm()
        }
    }

    private fun stopLocally(error: TxRefusal?) {
        wanted = false
        beatJob?.cancel()
        beatJob = null
        _state.value = EnableTxState(error = error)
    }

    private fun startHeartbeats() {
        beatJob = scope.launch {
            var lastOk = now()
            while (isActive && wanted) {
                val started = now()
                val still: Boolean? = try {
                    link.heartbeat(remoteId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (!wanted) return@launch
                when (still) {
                    true -> {
                        lastOk = now()
                        if (_state.value.linkTrouble) _state.value = _state.value.copy(linkTrouble = false)
                    }
                    false -> {
                        // The server ended it; its reason comes with the next status.
                        wanted = false
                        _state.value = EnableTxState(error = TxRefusal(SERVER_OFF, "", null))
                        return@launch
                    }
                    null -> {
                        if (now() - lastOk >= giveUpMs) {
                            wanted = false
                            _state.value = EnableTxState(error = TxRefusal(LINK_LOST, "", null))
                            sendDisarm()
                            return@launch
                        }
                        _state.value = _state.value.copy(linkTrouble = true)
                    }
                }
                delay((beatIntervalMs - (now() - started)).coerceAtLeast(0))
            }
        }
    }

    private fun sendDisarm() {
        sendScope.launch {
            repeat(3) { attempt ->
                try {
                    link.disarm()
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (attempt < 2) delay(400)
                }
            }
        }
    }

    companion object {
        // This side's own reasons, for the screen to say in the catalog's words.
        const val NO_ANSWER = "no_answer"
        const val RELEASED = "released"
        const val SERVER_OFF = "server_off"
        const val LINK_LOST = "link_lost"
    }
}
