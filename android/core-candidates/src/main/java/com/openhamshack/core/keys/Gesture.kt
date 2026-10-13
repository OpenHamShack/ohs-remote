package com.openhamshack.core.keys

/** What a key did: pressed once, held, or still held after a press (a repeat). */
enum class Gesture(val id: String) { PRESS("press"), HOLD("hold"), REPEAT("repeat") }

/** One gesture of one key, ready to look up in the [KeyMap]. */
data class KeyGesture(val key: RemoteKey, val gesture: Gesture)

/**
 * Raw key downs, repeats and ups in; presses, holds and repeats out
 * (REMOTE-KEYS.md §6.1).
 *
 * Two kinds of key:
 *
 * - **Keys with a hold** (OK, Menu, Play/Pause) decide on key **up**: a
 *   press if the hold never fired. That is principle 6 of the map: a press
 *   acting on key down let the release land on whatever the press had just
 *   opened (NB moved its OK to key up for exactly that). A hold fires once,
 *   [holdMs] after the down, whether Android sent repeats by then or not
 *   ([tick]): a Bluetooth remote's repeat rate is not ours to rely on.
 *   After a hold, nothing more comes from that key until it is released.
 * - **The rest** act on key down, and their repeats come out as [Gesture.REPEAT]
 *   for the map to take (tuning runs on while held) or ignore.
 *
 * [swallow] drops everything else from a key until its up: the key that
 * stopped a transmission must not then also press something.
 */
class KeyGestures(
    private val holdMs: (RemoteKey) -> Long? = ::defaultHoldMs,
) {
    private class Down(val atMs: Long, var held: Boolean = false, var swallowed: Boolean = false)

    private val down = mutableMapOf<RemoteKey, Down>()

    fun isDown(key: RemoteKey): Boolean = key in down

    /** [key] is down and everything from it is being dropped ([swallow]). */
    fun isSwallowed(key: RemoteKey): Boolean = down[key]?.swallowed == true

    /** A key down; [repeat] is Android's repeat count (0 for the first down). */
    fun onDown(key: RemoteKey, repeat: Int, nowMs: Long): List<KeyGesture> {
        val d = down[key]
        if (d == null || repeat == 0) {
            // A first down, or a down for a key whose first we never saw (focus
            // arrived mid-hold): either way it starts here.
            val fresh = Down(nowMs)
            down[key] = fresh
            return if (holdMs(key) != null) emptyList() else listOf(KeyGesture(key, Gesture.PRESS))
        }
        if (d.swallowed) return emptyList()
        val hold = holdMs(key) ?: return listOf(KeyGesture(key, Gesture.REPEAT))
        if (!d.held && nowMs - d.atMs >= hold) {
            d.held = true
            return listOf(KeyGesture(key, Gesture.HOLD))
        }
        return emptyList()
    }

    fun onUp(key: RemoteKey, nowMs: Long): List<KeyGesture> {
        val d = down.remove(key) ?: return emptyList()
        if (d.swallowed || d.held || holdMs(key) == null) return emptyList()
        return listOf(KeyGesture(key, Gesture.PRESS))
    }

    /** Holds that are due by the clock alone, for keys whose repeats have not come. */
    fun tick(nowMs: Long): List<KeyGesture> = down.mapNotNull { (key, d) ->
        val hold = holdMs(key) ?: return@mapNotNull null
        if (d.held || d.swallowed || nowMs - d.atMs < hold) null
        else { d.held = true; KeyGesture(key, Gesture.HOLD) }
    }

    /** When the next hold falls due, for the caller's timer; null with none pending. */
    fun nextHoldAtMs(): Long? = down.entries.mapNotNull { (key, d) ->
        holdMs(key)?.takeIf { !d.held && !d.swallowed }?.let { d.atMs + it }
    }.minOrNull()

    /** Nothing more from [key] until it is released. */
    fun swallow(key: RemoteKey, nowMs: Long) {
        (down[key] ?: Down(nowMs).also { down[key] = it }).swallowed = true
    }

    /** Forget every key held: the screen lost focus or closed, and their ups will not come here. */
    fun reset() = down.clear()

    companion object {
        /** OK and Menu held: half a second, as the Icom face's typed frequency was. */
        const val HOLD_MS = 500L
        /** Play/Pause held to arm transmit: a full second, never an accidental press (§5.2). */
        const val TX_HOLD_MS = 1000L

        fun defaultHoldMs(key: RemoteKey): Long? = when (key) {
            RemoteKey.OK, RemoteKey.MENU -> HOLD_MS
            RemoteKey.PLAY_PAUSE -> TX_HOLD_MS
            else -> null
        }
    }
}
