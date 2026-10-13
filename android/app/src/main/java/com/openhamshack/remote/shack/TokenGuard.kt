package com.openhamshack.remote.shack

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What Remote does when an app behind the controller answers 401.
 *
 * Every kind used to call "forget the token and pair again" on its first
 * 401, so one refusal from a module destroyed a good device token. A module
 * answers 401 proxy_required for the moments it is restarting or Caddy is
 * reloading - an app update on the controller does both - and Brian's Fire
 * TV was sent back to "Pair this TV" from the Flex screen exactly then
 * (ohs-box, 10/10/2026, 07:08).
 *
 * So an app's 401 is only a question for the platform ([check], the
 * controller's own token check: ShackController.tokenState). If the
 * platform says the token is revoked or unknown, [onRevoked] pairs or signs
 * in again, as before. Otherwise - the token is good, or the platform could
 * not be asked - the refusal was transient: [reconnecting] is shown, and
 * after a back-off [onStillGood] opens the app afresh (a kind may have
 * stopped itself on the 401, as Icom and DATV do). Refusals in a row back
 * off further, up to [MAX_BACKOFF_MS], so a module that stays down is
 * asked about every half-minute, not hammered.
 *
 * One check at a time: a kind's several sockets often all fail together,
 * and their 401s are one event.
 */
class TokenGuard(
    private val scope: CoroutineScope,
    private val check: suspend () -> TokenState,
    private val onRevoked: () -> Unit,
    private val onStillGood: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _reconnecting = MutableStateFlow(false)
    /** A refusal is being checked or waited out: the screen says "Reconnecting…". */
    val reconnecting: StateFlow<Boolean> = _reconnecting.asStateFlow()

    private var job: Job? = null
    private var strikes = 0
    private var last = 0L

    /** An app said 401 (or the person chose "pair again" after one). */
    fun refused() {
        if (job?.isActive == true) return
        job = scope.launch {
            val t = now()
            if (t - last > CALM_MS) strikes = 0
            last = t
            strikes++
            _reconnecting.value = true
            try {
                if (check() == TokenState.REVOKED) {
                    _reconnecting.value = false
                    onRevoked()
                    return@launch
                }
                delay(backoffMs(strikes))
                _reconnecting.value = false
                onStillGood()
            } finally {
                _reconnecting.value = false
            }
        }
    }

    /** Leaving the controller: no reconnect afterwards. */
    fun cancel() {
        job?.cancel()
        job = null
        _reconnecting.value = false
    }

    companion object {
        /** A minute without a refusal and the next one starts the back-off again. */
        const val CALM_MS = 60_000L
        const val MAX_BACKOFF_MS = 30_000L

        /** 1 s, 2 s, 4 s ... to [MAX_BACKOFF_MS]: a restart is usually over in the first. */
        fun backoffMs(strikes: Int): Long = (1000L shl (strikes - 1).coerceIn(0, 5)).coerceAtMost(MAX_BACKOFF_MS)
    }
}
