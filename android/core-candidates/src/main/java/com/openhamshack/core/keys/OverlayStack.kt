package com.openhamshack.core.keys

/**
 * What is open over a face, innermost last (REMOTE-KEYS.md §6.1, the
 * overlay stack). Back closes the top one and only then leaves the face
 * (principle 4); overlays that only offer choices (Choose, quick controls,
 * the legend) close by themselves after [IDLE_MS] without a key, as NB's
 * picker always has, while panels that edit settings stay.
 *
 * Pure: the Compose side (rememberOverlays) holds one in state and asks it.
 */
data class OverlayStack(val open: List<Entry> = emptyList()) {
    /** [id] is the face's own name for the overlay; [idleClose] for choice overlays. */
    data class Entry(val id: String, val idleClose: Boolean, val touchedMs: Long)

    val top: String? get() = open.lastOrNull()?.id
    val any: Boolean get() = open.isNotEmpty()

    fun isOpen(id: String): Boolean = open.any { it.id == id }

    /** Open [id] on top; opening one already open brings it to the top. */
    fun push(id: String, idleClose: Boolean, nowMs: Long): OverlayStack =
        OverlayStack(open.filter { it.id != id } + Entry(id, idleClose, nowMs))

    /** Close [id] and anything opened over it. */
    fun close(id: String): OverlayStack {
        val i = open.indexOfFirst { it.id == id }
        return if (i < 0) this else OverlayStack(open.take(i))
    }

    /**
     * Back: the top overlay closes, and the answer says whether Back was
     * spent on it. False means nothing was open: the face may leave.
     */
    fun back(): Pair<OverlayStack, Boolean> = if (open.isEmpty()) this to false else OverlayStack(open.dropLast(1)) to true

    /** A key reached the top overlay: its idle clock starts again. */
    fun touched(nowMs: Long): OverlayStack =
        if (open.isEmpty()) this else OverlayStack(open.dropLast(1) + open.last().copy(touchedMs = nowMs))

    /** When the top overlay closes by itself, or null if it does not. */
    fun idleAtMs(): Long? = open.lastOrNull()?.takeIf { it.idleClose }?.let { it.touchedMs + IDLE_MS }

    /** The top overlay, left alone too long, closed. */
    fun expire(nowMs: Long): OverlayStack {
        val at = idleAtMs() ?: return this
        return if (nowMs >= at) OverlayStack(open.dropLast(1)) else this
    }

    companion object {
        /** NB's picker's eight seconds (shack/Picker.kt), now every choice overlay's. */
        const val IDLE_MS = 8_000L
    }
}
