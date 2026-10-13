package com.openhamshack.remote.ptt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.openhamshack.remote.api.TxCheck

/**
 * What the controller needs from the box: the three PTT calls of the
 * remote lease. [OpenHamShackPttLink] is the real one; the tests use a fake.
 */
interface PttLink {
    /** Key up naming [remote]. The box's answer: refused with a reason, or not. */
    suspend fun key(remote: String, force: Boolean): KeyAnswer

    /** true: still keyed for us. false: the box has unkeyed (409). Throws on network trouble. */
    suspend fun heartbeat(remote: String): Boolean

    /** Key down, whoever keyed. */
    suspend fun unkey()
}

/** [error] non-null: not transmitting, and why (status.txError). [check]: "transmit anyway" is offered. */
data class KeyAnswer(val error: String?, val check: TxCheck? = null)

enum class PttPhase { Idle, Keying, Keyed }

data class PttState(
    val phase: PttPhase = PttPhase.Idle,
    /** Why the last transmission ended or was refused; shown until the next key. */
    val error: String? = null,
    /** A busy/unchecked QO-100 refusal: the next key may be forced, for a minute. */
    val check: TxCheck? = null,
    /** Heartbeats are failing but the give-up time has not passed yet. */
    val linkTrouble: Boolean = false,
)

/**
 * Transmit with the box's remote lease.
 *
 * The rule throughout: **the safe direction is off**. The box keys only on
 * an explicit request and unkeys itself 5 s after the last heartbeat
 * (the contract); this side adds its own reasons to stop and never a reason
 * to stay up:
 *
 *  - [key] sends `on` with this install's id, then heartbeats every second.
 *  - A heartbeat answered 409 means the box already stopped (time-out, an
 *    encoder error, someone at the box): drop to idle at once.
 *  - Heartbeats failing for [giveUpMs] (3 s): give up, say so, and send a
 *    key-down in case the network comes back - the box's own 5 s lease is
 *    the backstop, this is the faster of the two.
 *  - Two status polls in a row saying off air after [statusGraceMs] of the
 *    key-up: the box stopped; believe it.
 *  - [release] (the app went to the background, the screen locked, the
 *    network went, the box screen closed): stop heartbeating first, then
 *    send the key-down. Only when *this* app keyed - backgrounding a phone
 *    must never stop a transmission someone started at the box.
 *  - Letting go while the key-up is still on its way: the key-down is sent
 *    straight away *and* again when the key-up's answer arrives, so it is
 *    correct whichever request the box sees last.
 *
 * Key-downs go out on [sendScope], which outlives the session's own scope:
 * a key-down launched as the box screen closes must not be cancelled with it.
 */
class PttController(
    private val link: PttLink,
    private val remoteId: String,
    private val scope: CoroutineScope,
    private val sendScope: CoroutineScope,
    private val now: () -> Long,
    private val beatIntervalMs: Long = 1000,
    private val giveUpMs: Long = 3000,
    private val statusGraceMs: Long = 3000,
) {
    private val _state = MutableStateFlow(PttState())
    val state: StateFlow<PttState> = _state.asStateFlow()

    private var wanted = false
    private var beatJob: Job? = null
    private var keyedAt = 0L
    private var offAirPolls = 0

    /** Key up. Ignored unless idle. [force]: the console's "transmit anyway". */
    fun key(force: Boolean = false) {
        if (_state.value.phase != PttPhase.Idle) return
        wanted = true
        _state.value = PttState(PttPhase.Keying)
        scope.launch {
            val answer = try {
                link.key(remoteId, force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KeyAnswer(error = "Not transmitting: " + (e.message ?: "OpenHamShack did not answer"), check = null).also {
                    // The request may have reached the box and only the answer
                    // been lost: make sure.
                    sendUnkey()
                }
            }
            if (!wanted) {
                // Let go (release/unkey) while the key-up was out. State is
                // already idle; the box may have keyed after our first key-down.
                sendUnkey()
                return@launch
            }
            if (answer.error != null) {
                wanted = false
                _state.value = PttState(error = answer.error, check = answer.check)
                return@launch
            }
            keyedAt = now()
            offAirPolls = 0
            _state.value = PttState(PttPhase.Keyed)
            startHeartbeats()
        }
    }

    /** The operator's stop: always sends a key-down, even if someone else keyed. */
    fun unkey() {
        stopLocally(null)
        sendUnkey()
    }

    /**
     * A reason outside the operator's hand to stop (background, screen off,
     * network lost, leaving the box). Sends a key-down only if this app keyed.
     */
    fun release(reason: String) {
        if (_state.value.phase == PttPhase.Idle && !wanted) return
        stopLocally("Stopped transmitting: $reason")
        sendUnkey()
    }

    /** Every status poll while this app holds the key: the box has the last word. */
    fun onStatus(onAir: Boolean, txError: String) {
        if (_state.value.phase != PttPhase.Keyed) return
        if (onAir) {
            offAirPolls = 0
            return
        }
        if (now() - keyedAt < statusGraceMs) return
        if (++offAirPolls >= 2) {
            stopLocally(txError.ifBlank { "OpenHamShack stopped transmitting" })
            sendUnkey()
        }
    }

    private fun stopLocally(error: String?) {
        wanted = false
        beatJob?.cancel()
        beatJob = null
        _state.value = PttState(error = error)
    }

    private fun startHeartbeats() {
        beatJob?.cancel()
        beatJob = scope.launch {
            var lastOk = now()
            while (isActive && wanted) {
                val started = now()
                val stillKeyed: Boolean? = try {
                    link.heartbeat(remoteId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (!wanted) return@launch
                when (stillKeyed) {
                    true -> {
                        lastOk = now()
                        if (_state.value.linkTrouble) _state.value = _state.value.copy(linkTrouble = false)
                    }
                    false -> {
                        // The box ended it; the reason is in its txError, which
                        // the status poll shows. Nothing to send.
                        wanted = false
                        _state.value = PttState(error = "OpenHamShack is no longer transmitting")
                        return@launch
                    }
                    null -> {
                        if (now() - lastOk >= giveUpMs) {
                            wanted = false
                            _state.value = PttState(
                                error = "Lost contact with OpenHamShack, so stopped. The box stops by itself " +
                                    "within 5 s of the last heartbeat it heard.",
                            )
                            sendUnkey()
                            return@launch
                        }
                        _state.value = _state.value.copy(linkTrouble = true)
                    }
                }
                delay((beatIntervalMs - (now() - started)).coerceAtLeast(0))
            }
        }
    }

    private fun sendUnkey() {
        sendScope.launch {
            repeat(3) { attempt ->
                try {
                    link.unkey()
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (attempt < 2) delay(400)
                }
            }
        }
    }
}
