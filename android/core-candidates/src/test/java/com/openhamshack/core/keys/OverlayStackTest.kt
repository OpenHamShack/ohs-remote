package com.openhamshack.core.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Back closes the innermost overlay before it leaves; choice overlays close when left alone. */
class OverlayStackTest {
    @Test fun backClosesInnermostFirstThenMayLeave() {
        var s = OverlayStack().push("controls", true, 0).push("settings", false, 10)
        var (next, spent) = s.back()
        assertTrue(spent); assertEquals("controls", next.top)
        s = next
        s.back().let { next = it.first; spent = it.second }
        assertTrue(spent); assertFalse(next.any)
        assertFalse("nothing open: Back is the face's (leave)", next.back().second)
    }

    @Test fun closingOneClosesWhatWasOpenedOverIt() {
        val s = OverlayStack().push("a", false, 0).push("b", false, 0).push("c", true, 0)
        assertEquals(listOf("a"), s.close("b").open.map { it.id })
        assertEquals(s, s.close("nope"))
    }

    @Test fun choiceOverlaysCloseAfterEightIdleSecondsAndKeysPutThatOff() {
        var s = OverlayStack().push("choose", idleClose = true, nowMs = 0)
        assertEquals(OverlayStack.IDLE_MS, s.idleAtMs())
        s = s.touched(5_000)
        assertTrue(s.expire(OverlayStack.IDLE_MS).any)
        assertFalse(s.expire(5_000 + OverlayStack.IDLE_MS).any)
    }

    @Test fun settingsPanelsNeverTimeOut() {
        val s = OverlayStack().push("settings", idleClose = false, nowMs = 0)
        assertNull(s.idleAtMs())
        assertTrue(s.expire(1_000_000).any)
    }

    @Test fun reopeningBringsToTheTop() {
        val s = OverlayStack().push("a", false, 0).push("b", false, 0).push("a", true, 5)
        assertEquals(listOf("b", "a"), s.open.map { it.id })
    }
}
