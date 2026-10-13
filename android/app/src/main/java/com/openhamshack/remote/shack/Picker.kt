package com.openhamshack.remote.shack

// The TV's bookmark and mode picker. The mode and bookmark rows used to sit
// on the NB/Radio screen all the time, two rows of chips between the dial
// and the waterfall; Brian found the screen too cluttered (10/10/2026). Now
// Up opens one overlay with the bookmarks on top and the modes under them,
// and it goes away on OK, BACK or a few seconds without a key.
//
// The overlay is one focus target that moves a highlight, not a row of
// focusable chips: Compose's focus search is geometric and leaks out of
// overlays (GUIDE 5), and with one target there is nothing for it to leak
// to. That also makes the whole of its D-pad behaviour this pure class,
// tested on the JVM.

enum class PickerRow { MARKS, MODES }

/** A D-pad key as the picker sees it. */
enum class PickerKey { LEFT, RIGHT, UP, DOWN, OK, BACK }

/** What a key did: moved the highlight, chose the highlighted item, or closed the picker. */
sealed interface PickerStep {
    data class Move(val to: PickerFocus) : PickerStep
    data class Choose(val row: PickerRow, val index: Int) : PickerStep
    data object Close : PickerStep
}

/**
 * The highlight: [row] and [index] in it, over [marks] bookmarks and
 * [modes] modes. [touchedMs] is the last key, for closing by itself.
 */
data class PickerFocus(val marks: Int, val modes: Int, val row: PickerRow, val index: Int, val touchedMs: Long) {
    companion object {
        /** Idle this long and the picker closes by itself. */
        const val IDLE_MS = 8_000L

        /**
         * Opened from the waterfall: on the bookmark nearest the dial (the
         * one the waterfall names), else - no bookmarks on this engine - on
         * the current mode. Null when there is nothing to pick at all.
         */
        fun open(marks: List<Bookmark>, modes: List<String>, dial: Double?, mode: String, nowMs: Long): PickerFocus? = when {
            marks.isNotEmpty() -> PickerFocus(marks.size, modes.size, PickerRow.MARKS, Bookmarks.nearest(marks, dial).coerceAtLeast(0), nowMs)
            modes.isNotEmpty() -> PickerFocus(0, modes.size, PickerRow.MODES, modes.indexOf(mode).coerceAtLeast(0), nowMs)
            else -> null
        }
    }

    /**
     * The lists changed under the open picker (the span moved, so the
     * bookmarks on the scope did): keep the highlight on a real item.
     * Null when both lists are now empty.
     */
    fun fit(marks: Int, modes: Int): PickerFocus? {
        val row = when {
            row == PickerRow.MARKS && marks > 0 -> PickerRow.MARKS
            row == PickerRow.MODES && modes > 0 -> PickerRow.MODES
            marks > 0 -> PickerRow.MARKS
            modes > 0 -> PickerRow.MODES
            else -> return null
        }
        val n = if (row == PickerRow.MARKS) marks else modes
        return copy(marks = marks, modes = modes, row = row, index = index.coerceIn(0, n - 1))
    }

    /**
     * One key. Left/Right walk the row and stop at its ends (a wrap from
     * the last bookmark to the first jumps across the band unseen); Down
     * from the bookmarks goes to the modes, on [currentMode]'s chip; Up
     * from the modes comes back to the bookmark the highlight left; Up on
     * the top row stays (there is nothing above); Down on the bottom row
     * closes, back to the waterfall the picker sits over. OK chooses,
     * BACK closes without choosing.
     */
    fun key(k: PickerKey, nowMs: Long, currentMode: Int = -1, lastMark: Int = -1): PickerStep {
        val n = if (row == PickerRow.MARKS) marks else modes
        return when (k) {
            PickerKey.LEFT -> PickerStep.Move(copy(index = (index - 1).coerceAtLeast(0), touchedMs = nowMs))
            PickerKey.RIGHT -> PickerStep.Move(copy(index = (index + 1).coerceAtMost(n - 1), touchedMs = nowMs))
            PickerKey.DOWN ->
                if (row == PickerRow.MARKS && modes > 0) PickerStep.Move(copy(row = PickerRow.MODES,
                    index = currentMode.takeIf { it in 0 until modes } ?: 0, touchedMs = nowMs))
                else PickerStep.Close
            PickerKey.UP ->
                if (row == PickerRow.MODES && marks > 0) PickerStep.Move(copy(row = PickerRow.MARKS,
                    index = lastMark.takeIf { it in 0 until marks } ?: 0, touchedMs = nowMs))
                else PickerStep.Move(copy(touchedMs = nowMs))
            PickerKey.OK -> PickerStep.Choose(row, index)
            PickerKey.BACK -> PickerStep.Close
        }
    }

    /** No key for [IDLE_MS]: time to close by itself. */
    fun idle(nowMs: Long): Boolean = nowMs - touchedMs >= IDLE_MS
}
