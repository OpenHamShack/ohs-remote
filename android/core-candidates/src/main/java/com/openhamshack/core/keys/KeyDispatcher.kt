package com.openhamshack.core.keys

/** Where focus is on a face, as far as the keys care. */
enum class KeyScope {
    /** On the face's surface (dial, waterfall, display, picture): every key of the map acts. */
    SURFACE,
    /** On another control of the main screen: the D-pad and OK are that control's; accelerators still act. */
    CONTROL,
    /** An overlay is up: only mute and the transmit keys reach beyond it (§4 principle 5). */
    OVERLAY,
}

/** An action the map fired: [repeat] while a key is held, [digit] for a typed digit. */
data class Fired(val action: RemoteAction, val repeat: Boolean = false, val digit: Int? = null)

/** What one key event came to. [consumed] false: let Compose have it (focus moves, a control presses). */
data class KeyOutcome(
    val consumed: Boolean,
    val fired: List<Fired> = emptyList(),
    val tx: TxKey = TxKey.PASS,
    /** Play/Pause was held on a face that does not transmit: say so rather than do nothing. */
    val txUnavailable: Boolean = false,
) {
    companion object {
        val PASS = KeyOutcome(false)
        val TAKEN = KeyOutcome(true)
    }
}

/**
 * The whole of a face's key handling that is not drawing: raw key events in,
 * the map's actions and transmit's steps out. Pure, so every rule here is
 * tested without a screen (KeyDispatcherTest); the Compose glue
 * (core tv/RemoteKeys.kt) only feeds it and carries out what it says.
 *
 * The order of precedence, highest first:
 * 1. Transmit. Starting or on air, any key down stops it and the rest of
 *    that key is dropped. Armed, OK or Play/Pause pressed confirms and any
 *    other key disarms. Nothing else sees those keys.
 * 2. Ownership, decided at a key's first down by [KeyScope]: a key the
 *    face does not own in this scope is passed to Compose for the whole of
 *    its press, so focus can move inside an overlay or along a row of
 *    controls without the map stealing the arrows.
 * 3. The map, for the face's class, and only for actions the face [can] do:
 *    a key bound to something this face lacks falls through to Compose.
 *
 * Back is not dispatched here outside transmit: Back closes the innermost
 * overlay or leaves, through the screen's BackHandlers (rememberBackRouting).
 */
class KeyDispatcher(
    val faceClass: FaceClass,
    val map: KeyMap = KeyMap.STANDARD,
    val tx: TxArm = TxArm(),
    private val gestures: KeyGestures = KeyGestures(),
) {
    /** Keys whose first down this dispatcher took: their repeats and up are its too. */
    private val owned = mutableSetOf<RemoteKey>()
    /** Keys whose first down was taken by transmit: their gestures go to transmit only. */
    private val forTx = mutableSetOf<RemoteKey>()

    fun onKeyDown(key: RemoteKey, repeat: Int, nowMs: Long, scope: KeyScope, can: (RemoteAction) -> Boolean): KeyOutcome {
        if (repeat == 0 || !gestures.isDown(key)) {
            when (val t = tx.keyDown(key, nowMs)) {
                TxKey.STOP, TxKey.DISARM -> {
                    gestures.swallow(key, nowMs)
                    owned += key
                    forTx -= key
                    return KeyOutcome(true, tx = t)
                }
                TxKey.TAKEN -> {
                    owned += key
                    forTx += key
                    gestures.onDown(key, 0, nowMs)
                    return KeyOutcome.TAKEN
                }
                else -> {}
            }
            if (!owns(key, scope, can)) {
                owned -= key
                return KeyOutcome.PASS
            }
            owned += key
            forTx -= key
            return run(gestures.onDown(key, 0, nowMs), nowMs, can)
        }
        if (key !in owned) return KeyOutcome.PASS
        // A key held while transmit started (a different key armed and was
        // confirmed) stops it on its next repeat, as any key does.
        if (tx.phase == TxPhase.STARTING || tx.phase == TxPhase.ON) {
            if (key !in forTx) {
                val t = tx.keyDown(key, nowMs)
                gestures.swallow(key, nowMs)
                return KeyOutcome(true, tx = t)
            }
        }
        return run(gestures.onDown(key, repeat, nowMs), nowMs, can)
    }

    fun onKeyUp(key: RemoteKey, nowMs: Long, can: (RemoteAction) -> Boolean): KeyOutcome {
        if (key !in owned) {
            gestures.onUp(key, nowMs)
            return KeyOutcome.PASS
        }
        owned -= key
        return run(gestures.onUp(key, nowMs), nowMs, can)
    }

    /**
     * The clock: holds that fall due without a repeat, and the arm's own
     * time-out. Call it at [nextDueMs] while anything is pending.
     */
    fun tick(nowMs: Long, can: (RemoteAction) -> Boolean): KeyOutcome {
        if (tx.tick(nowMs) == TxKey.DISARM) return KeyOutcome(true, tx = TxKey.DISARM)
        val held = gestures.tick(nowMs)
        return if (held.isEmpty()) KeyOutcome(false) else run(held, nowMs, can)
    }

    /** The rest of [key] is being dropped: it stopped or disarmed transmit, and must not reach a control. */
    fun swallowing(key: RemoteKey): Boolean = key in owned && gestures.isSwallowed(key)

    /** When [tick] next has something to do, or null. */
    fun nextDueMs(): Long? = listOfNotNull(
        gestures.nextHoldAtMs(),
        tx.untilMs.takeIf { tx.phase == TxPhase.ARMED },
    ).minOrNull()

    /** Focus left the screen (a dialog's window, the app in the background): no key is held any more. */
    fun reset() {
        gestures.reset()
        owned.clear()
        forTx.clear()
    }

    private fun owns(key: RemoteKey, scope: KeyScope, can: (RemoteAction) -> Boolean): Boolean {
        if (key == RemoteKey.BACK) return false
        // Play/Pause is owned everywhere: mute and transmit reach past any overlay.
        if (key == RemoteKey.PLAY_PAUSE) return true
        return when (scope) {
            KeyScope.OVERLAY -> false
            KeyScope.CONTROL -> key.accelerator && bound(key, can)
            KeyScope.SURFACE -> bound(key, can)
        }
    }

    /** Some gesture of [key] does something this face can do; else Compose may have it. */
    private fun bound(key: RemoteKey, can: (RemoteAction) -> Boolean): Boolean =
        map.of(faceClass).any { it.key == key && it.action != RemoteAction.BACK && can(it.action) }

    private fun run(gs: List<KeyGesture>, nowMs: Long, can: (RemoteAction) -> Boolean): KeyOutcome {
        if (gs.isEmpty()) return KeyOutcome.TAKEN
        val fired = mutableListOf<Fired>()
        var t = TxKey.PASS
        var unavailable = false
        for (g in gs) {
            val fromTx = g.key in forTx
            if (g.gesture == Gesture.PRESS || g.gesture == Gesture.HOLD) forTx -= g.key
            val r = tx.gesture(g, nowMs)
            if (r != TxKey.PASS) {
                if (r == TxKey.ARM && !can(RemoteAction.TRANSMIT)) {
                    unavailable = true
                    continue
                }
                if (r == TxKey.ARM) tx.arm(nowMs)
                t = r
                continue
            }
            // The rest of a key transmit took (its arm ran out under it): dropped.
            if (fromTx) continue
            val a = map.action(faceClass, g.key, g.gesture) ?: continue
            if (a == RemoteAction.TRANSMIT || a == RemoteAction.BACK || !can(a)) continue
            fired += Fired(a, repeat = g.gesture == Gesture.REPEAT, digit = g.key.digit)
        }
        return KeyOutcome(true, fired, t, unavailable)
    }
}
