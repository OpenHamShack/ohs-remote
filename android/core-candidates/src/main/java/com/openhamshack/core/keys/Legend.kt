package com.openhamshack.core.keys

/** One line of the key legend: [key] [gesture] does [action]; [active] false shows it dimmed with "—". */
data class LegendRow(val key: RemoteKey, val gesture: Gesture, val action: RemoteAction, val active: Boolean)

/**
 * The legend's lines for a face (REMOTE-KEYS.md §6.2), from the same map the
 * keys are dispatched by, so the two cannot disagree: every press and hold
 * the map binds for the face's class, in the map's order, active where the
 * face [can] do it. Repeats are the press again and are not listed; the ten
 * digits are one line (as [RemoteKey.DIGIT_0]).
 */
fun legendRows(map: KeyMap, faceClass: FaceClass, can: (RemoteAction) -> Boolean): List<LegendRow> =
    map.of(faceClass)
        .filter { it.gesture != Gesture.REPEAT && (it.key.digit == null || it.key == RemoteKey.DIGIT_0) }
        .map { LegendRow(it.key, it.gesture, it.action, it.action == RemoteAction.BACK || can(it.action)) }
