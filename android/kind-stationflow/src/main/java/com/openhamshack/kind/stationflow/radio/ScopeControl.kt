package com.openhamshack.kind.stationflow.radio

import com.openhamshack.kind.stationflow.api.ScopeCaps
import kotlin.math.abs
import kotlin.math.roundToLong

/*
 * The arithmetic behind the scope's zoom and reference level on the face
 * (D-pad while the scope is in adjust mode, the scope settings panel), kept
 * free of Compose and tested on the JVM. Everything steps along what the
 * module says this model takes (ScopeCaps), never along a guessed list.
 */

/**
 * The span one zoom step from [current]: in is the next narrower entry of
 * the model's ladder, out the next wider; from a span not on the ladder, the
 * nearest one in that direction. Null at either end (the zoom stops, as the
 * radio's own span key does) or with no ladder.
 */
fun zoomSpan(caps: ScopeCaps, current: Long?, zoomIn: Boolean): Long? {
    val ladder = caps.spansHz
    if (ladder.isEmpty()) return null
    if (current == null) return ladder[ladder.size / 2]
    return if (zoomIn) ladder.lastOrNull { it < current } else ladder.firstOrNull { it > current }
}

/** How far one press moves the reference level: 1 dB, or the model's step if that is coarser. */
const val REF_PRESS_DB = 1.0

/**
 * The reference level one press up or down from [current], on the model's
 * step and inside its range; null when it would not move (at the end of the
 * range, or the radio has not said where it is).
 */
fun refStep(caps: ScopeCaps, current: Double?, up: Boolean): Double? {
    current ?: return null
    val by = maxOf(REF_PRESS_DB, caps.refStep)
    val raw = current + if (up) by else -by
    val snapped = (raw / caps.refStep).roundToLong() * caps.refStep
    val next = snapped.coerceIn(caps.refMin, caps.refMax)
    return next.takeIf { abs(it - current) > 1e-9 }
}

/** The next of [list] after [current], wrapping; the first when [current] is not in it. */
fun <T> nextIn(list: List<T>, current: T?, by: Int = 1): T? {
    if (list.isEmpty()) return null
    val i = list.indexOf(current)
    return if (i < 0) list.first() else list[Math.floorMod(i + by, list.size)]
}

/** "±2.5k" for a 5000 Hz span: how the radio's span key prints a full width. */
fun spanLabel(hz: Long): String = "±" + shortHz(hz / 2)

/** "+1.5dB", "0.0dB", "−3.0dB": the reference level as the radio prints it. */
fun refLabel(db: Double): String = (if (db > 0) "+" else if (db < 0) "−" else "") + "%.1fdB".format(java.util.Locale.ROOT, abs(db))
