package com.openhamshack.remote.shack

import kotlin.math.abs

// Which bookmarks are named on the waterfall, and where. The first OpenWebRX+
// pass named all of them, staggered over three rows; on NB's wide span that
// was twelve names stacked over the spectrum and some printed over each other
// ("Screen shots are too cluttered", Brian, 10/10/2026). Now every bookmark is
// a small tick along the top edge and one is named: the one being looked at
// in the picker, else the one the dial is on or beside. Pure, so "no two
// labels ever overlap" is a test rather than something looked for on a TV.

object ScopeLabels {
    /** A label wanted at centre [x] (pixels), [width] wide; [key] says which. Earlier in the list wins. */
    data class Want(val key: Int, val x: Float, val width: Float)

    /** Where a label goes: [left] edge in pixels, inside 0..span. */
    data class Placed(val key: Int, val left: Float, val width: Float) {
        val right: Float get() = left + width
    }

    /**
     * One row of labels over a [span] pixels wide, none overlapping and none
     * closer than [gap]: each is centred on its tick, pushed in from the
     * edges, and left out if it would touch one already placed. Callers
     * list the most important first, so the named one always survives.
     * A label wider than the whole span is still placed (from 0) if it is
     * first - a long name on a phone is cut by the canvas, not hidden.
     */
    fun place(wants: List<Want>, span: Float, gap: Float = 12f): List<Placed> {
        val out = ArrayList<Placed>(wants.size)
        for (w in wants) {
            val left = (w.x - w.width / 2).coerceIn(0f, (span - w.width).coerceAtLeast(0f))
            val p = Placed(w.key, left, w.width)
            if (out.none { p.left < it.right + gap && it.left < p.right + gap }) out += p
        }
        return out
    }

    /**
     * Which of [marks] is named: [focused] (the picker's) when it is one of
     * them, else the one nearest the [dial] if that is within [near] of the
     * scope's width ([lo]..[hi]) - tuned between two bookmarks far from
     * either, nothing is named, which is the calm default. -1 for none.
     */
    fun named(marks: List<Bookmark>, dial: Double?, focused: Bookmark?, lo: Double?, hi: Double?, near: Double = 0.06): Int {
        if (focused != null) marks.indexOf(focused).let { if (it >= 0) return it }
        if (dial == null || lo == null || hi == null || hi <= lo) return -1
        val i = Bookmarks.nearest(marks, dial)
        if (i < 0) return -1
        return if (abs(marks[i].hz - dial) <= near * (hi - lo)) i else -1
    }
}
