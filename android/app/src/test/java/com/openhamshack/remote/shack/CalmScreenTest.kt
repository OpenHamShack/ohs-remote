package com.openhamshack.remote.shack

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The calmer NB/Radio screen (Brian, 10/10/2026: "too cluttered"): one
 * bookmark named on the waterfall and never two labels over each other,
 * the bookmark/mode picker's D-pad behaviour, and key hints that go away.
 * Against the memories the simulators answered (DecoderPanelTest's
 * fixtures) and NB's spec.
 */
class CalmScreenTest {
    private fun text(name: String) = javaClass.classLoader!!.getResourceAsStream("shack/$name")!!.bufferedReader().readText()
    private val nb by lazy { Bookmark.parseList(JSONObject(text("nb-memories.json"))) }
    private val nbModes by lazy {
        val a = JSONObject(text("nb-spec.json")).getJSONObject("mode").getJSONArray("choices")
        (0 until a.length()).map { a.getString(it) }
    }

    // NB's wide span, as the simulator's scope draws it.
    private val lo = 10_489_495_000.0
    private val hi = 10_490_005_000.0

    // --- labels on the waterfall ----------------------------------------------

    private fun overlaps(p: List<ScopeLabels.Placed>, gap: Float) =
        p.indices.any { i -> p.indices.any { j -> i < j && p[i].left < p[j].right + gap && p[j].left < p[i].right + gap } }

    @Test fun everyNbBookmarkWantingANameAtOnceStillGivesNoOverlap() {
        // The old screen's worst case: all twelve, each ~150 px wide, on a 900 px waterfall.
        val span = 900f
        val wants = nb.mapIndexed { i, b -> ScopeLabels.Want(i, ((b.hz - lo) / (hi - lo) * span).toFloat(), 150f) }
        val placed = ScopeLabels.place(wants, span)
        assertFalse(overlaps(placed, 12f))
        assertTrue("the first asked for is always named", placed.first().key == 0)
        assertTrue(placed.all { it.left >= 0f && it.right <= span })
    }

    @Test fun noOverlapForAnyLayoutAtAll() {
        val r = Random(7)
        repeat(2_000) {
            val span = 200f + r.nextFloat() * 1600f
            val wants = List(1 + r.nextInt(20)) { i -> ScopeLabels.Want(i, r.nextFloat() * span, 20f + r.nextFloat() * 300f) }
            val placed = ScopeLabels.place(wants, span, gap = 8f)
            assertFalse("overlap at span $span: $placed", overlaps(placed, 8f))
            assertEquals(0, placed.first().key)
        }
    }

    @Test fun aLabelAtTheEdgeIsPushedInsideNotCut() {
        val p = ScopeLabels.place(listOf(ScopeLabels.Want(0, 2f, 120f), ScopeLabels.Want(1, 899f, 120f)), 900f)
        assertEquals(0f, p[0].left, 0f)
        assertEquals(900f, p[1].right, 0f)
    }

    @Test fun theNamedBookmarkIsTheFocusedOneElseTheOneByTheDial() {
        val ft8 = nb.indexOfFirst { it.name == "FT8" }
        val emergency = nb.first { it.name.startsWith("Emergency") }
        // On the FT8 dial: FT8 is named.
        assertEquals(ft8, ScopeLabels.named(nb, 10_489_540_000.0, null, lo, hi))
        // The picker looking at another: that one, wherever the dial is.
        assertEquals(nb.indexOf(emergency), ScopeLabels.named(nb, 10_489_540_000.0, emergency, lo, hi))
        // Far from every bookmark (beyond 6% of the span): none - the calm default.
        val gaps = nb.zipWithNext().maxBy { it.second.hz - it.first.hz }
        val between = (gaps.first.hz + gaps.second.hz) / 2
        assertEquals(-1, ScopeLabels.named(nb, between, null, lo, hi))
        assertEquals(-1, ScopeLabels.named(nb, null, null, lo, hi))
        assertEquals(-1, ScopeLabels.named(emptyList(), 10_489_540_000.0, null, lo, hi))
    }

    // --- the picker -------------------------------------------------------------

    @Test fun upOpensOnTheBookmarkByTheDialAndOkChoosesIt() {
        val p = PickerFocus.open(nb, nbModes, 10_489_540_020.0, "ft8", 0)!!
        assertEquals(PickerRow.MARKS, p.row)
        assertEquals("FT8", nb[p.index].name)
        val step = p.key(PickerKey.OK, 100)
        assertEquals(PickerStep.Choose(PickerRow.MARKS, p.index), step)
    }

    @Test fun downGoesToTheModesOnTheCurrentOneAndUpComesBack() {
        val p = PickerFocus.open(nb, nbModes, 10_489_540_000.0, "ft8", 0)!!
        val right = (p.key(PickerKey.RIGHT, 1) as PickerStep.Move).to
        assertEquals(p.index + 1, right.index)
        val down = (right.key(PickerKey.DOWN, 2, currentMode = nbModes.indexOf("ft8")) as PickerStep.Move).to
        assertEquals(PickerRow.MODES, down.row)
        assertEquals("ft8", nbModes[down.index])
        val back = (down.key(PickerKey.UP, 3, lastMark = right.index) as PickerStep.Move).to
        assertEquals(PickerRow.MARKS, back.row)
        assertEquals(right.index, back.index)
        // Down on the bottom row closes: back to the waterfall underneath.
        assertEquals(PickerStep.Close, down.key(PickerKey.DOWN, 4))
        assertEquals(PickerStep.Close, down.key(PickerKey.BACK, 4))
    }

    @Test fun leftAndRightStopAtTheEndsAndUpOnTopStays() {
        var p = PickerFocus.open(nb, nbModes, lo, "usb", 0)!!
        assertEquals(0, p.index)
        p = (p.key(PickerKey.LEFT, 1) as PickerStep.Move).to
        assertEquals(0, p.index)
        repeat(nb.size + 3) { p = (p.key(PickerKey.RIGHT, 2) as PickerStep.Move).to }
        assertEquals(nb.size - 1, p.index)
        val up = (p.key(PickerKey.UP, 3) as PickerStep.Move).to
        assertEquals(PickerRow.MARKS, up.row)
        assertEquals(p.index, up.index)
    }

    @Test fun anEngineWithNoBookmarksOpensOnItsModes() {
        val p = PickerFocus.open(emptyList(), nbModes, 1.0, "cw", 0)!!
        assertEquals(PickerRow.MODES, p.row)
        assertEquals("cw", nbModes[p.index])
        // Up has nowhere to go, Down closes.
        assertEquals(PickerRow.MODES, (p.key(PickerKey.UP, 1) as PickerStep.Move).to.row)
        assertEquals(PickerStep.Close, p.key(PickerKey.DOWN, 1))
        assertNull(PickerFocus.open(emptyList(), emptyList(), 1.0, "cw", 0))
    }

    @Test fun itClosesByItselfAfterAFewIdleSecondsAndEachKeyPutsThatOff() {
        val p = PickerFocus.open(nb, nbModes, lo, "usb", 1_000)!!
        assertFalse(p.idle(1_000 + PickerFocus.IDLE_MS - 1))
        assertTrue(p.idle(1_000 + PickerFocus.IDLE_MS))
        val moved = (p.key(PickerKey.RIGHT, 5_000) as PickerStep.Move).to
        assertFalse(moved.idle(1_000 + PickerFocus.IDLE_MS))
    }

    @Test fun theHighlightStaysOnARealItemWhenTheBookmarksChange() {
        val p = PickerFocus(12, 5, PickerRow.MARKS, 11, 0)
        assertEquals(3, p.fit(4, 5)!!.index)
        val gone = p.fit(0, 5)!!
        assertEquals(PickerRow.MODES, gone.row)
        assertEquals(4, gone.index)
        assertNull(p.fit(0, 0))
    }

    // --- NB's channel keys: the next bookmark along the band --------------------

    @Test fun channelKeysStepBookmarksPassingTheOneTheDialIsOn() {
        val marks = listOf(Bookmark("A", 10_489_540_000.0, "cw", true), Bookmark("B", 10_489_600_000.0, "usb", true),
            Bookmark("C", 10_489_750_000.0, "ft8", true))
        assertEquals("B", Bookmarks.step(marks, 10_489_540_020.0, +1)?.name)
        assertEquals("A", Bookmarks.step(marks, 10_489_600_000.0, -1)?.name)
        assertEquals(null, Bookmarks.step(marks, 10_489_750_000.0, +1))
        assertEquals("C", Bookmarks.step(marks, null, -1)?.name)
        assertEquals(null, Bookmarks.step(emptyList(), 1.0, +1))
    }
}
