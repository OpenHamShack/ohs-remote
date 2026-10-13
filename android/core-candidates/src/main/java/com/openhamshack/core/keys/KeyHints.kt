package com.openhamshack.core.keys

// The transient half of the key legend (REMOTE-KEYS.md §6.2), from the
// NB/Radio TV screen where it started (shack/KeyHints.kt). Hints were once a
// permanent footer line, read once and then clutter for the rest of the
// evening. Now they show for a few seconds when a face opens, list only
// keys not yet used, and come back briefly each time a key is used for the
// first time - so what is left to learn is offered, and someone who knows
// the remote stops seeing them at all. The full legend (Menu held, Info)
// is always there for the rest.

/** [used]: actions done since the face opened. [untilMs]: the hints show until then. */
data class KeyHints(val used: Set<RemoteAction> = emptySet(), val untilMs: Long = 0L) {
    companion object {
        const val OPEN_MS = 6_000L
        const val AGAIN_MS = 4_000L

        /** The order hints are offered in: the commonest first. */
        val ORDER = listOf(
            RemoteAction.TUNE_UP, RemoteAction.CHANNEL_NEXT, RemoteAction.CHOOSE, RemoteAction.FOCUS_DOWN,
            RemoteAction.CONTROLS, RemoteAction.STEP_UP, RemoteAction.BAND_NEXT, RemoteAction.SETTINGS,
            RemoteAction.MUTE, RemoteAction.HELP,
        )

        /** Actions that come in pairs count as one: using either teaches both. */
        fun canonical(a: RemoteAction): RemoteAction = when (a) {
            RemoteAction.TUNE_DOWN -> RemoteAction.TUNE_UP
            RemoteAction.CHANNEL_PREV -> RemoteAction.CHANNEL_NEXT
            RemoteAction.STEP_DOWN -> RemoteAction.STEP_UP
            RemoteAction.BAND_PREV -> RemoteAction.BAND_NEXT
            else -> a
        }
    }

    /** The face opened: show them. */
    fun opened(nowMs: Long): KeyHints = copy(untilMs = nowMs + OPEN_MS)

    /** An action was used. The first use of each brings the rest back for a moment; later uses change nothing. */
    fun pressed(a: RemoteAction, nowMs: Long): KeyHints {
        val c = canonical(a)
        return if (c in used) this else copy(used = used + c, untilMs = nowMs + AGAIN_MS)
    }

    /** The hints still worth showing, of those that do something here now ([available]). */
    fun pending(available: Set<RemoteAction>): List<RemoteAction> {
        val have = available.map { canonical(it) }.toSet()
        return ORDER.filter { it in have && it !in used }
    }

    fun visible(nowMs: Long, available: Set<RemoteAction>): Boolean = nowMs < untilMs && pending(available).isNotEmpty()
}
