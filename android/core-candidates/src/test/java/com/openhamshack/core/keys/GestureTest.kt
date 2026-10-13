package com.openhamshack.core.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Presses, holds and repeats from raw downs and ups, on a clock the test sets. */
class GestureTest {
    private fun g(k: RemoteKey, x: Gesture) = KeyGesture(k, x)

    @Test fun okIsAPressOnReleaseNotOnTheDown() {
        val gs = KeyGestures()
        assertEquals(emptyList<KeyGesture>(), gs.onDown(RemoteKey.OK, 0, 0))
        assertEquals(listOf(g(RemoteKey.OK, Gesture.PRESS)), gs.onUp(RemoteKey.OK, 120))
    }

    @Test fun aHoldNeverAlsoPresses() {
        val gs = KeyGestures()
        gs.onDown(RemoteKey.OK, 0, 0)
        assertEquals("a repeat before the hold time is nothing", emptyList<KeyGesture>(), gs.onDown(RemoteKey.OK, 1, 400))
        assertEquals(listOf(g(RemoteKey.OK, Gesture.HOLD)), gs.onDown(RemoteKey.OK, 2, 520))
        assertEquals("the hold fires once", emptyList<KeyGesture>(), gs.onDown(RemoteKey.OK, 3, 600))
        assertEquals("and its release does nothing", emptyList<KeyGesture>(), gs.onUp(RemoteKey.OK, 900))
    }

    @Test fun aHoldFiresByTheClockWithoutRepeats() {
        val gs = KeyGestures()
        gs.onDown(RemoteKey.PLAY_PAUSE, 0, 1_000)
        assertEquals(2_000L, gs.nextHoldAtMs())
        assertEquals(emptyList<KeyGesture>(), gs.tick(1_999))
        assertEquals(listOf(g(RemoteKey.PLAY_PAUSE, Gesture.HOLD)), gs.tick(2_000))
        assertNull(gs.nextHoldAtMs())
        assertEquals(emptyList<KeyGesture>(), gs.onUp(RemoteKey.PLAY_PAUSE, 2_500))
    }

    @Test fun playPauseHeldUnderASecondIsStillAPress() {
        // Mute must never become transmit by a slow thumb: the transmit hold is a full second.
        val gs = KeyGestures()
        gs.onDown(RemoteKey.PLAY_PAUSE, 0, 0)
        assertEquals(emptyList<KeyGesture>(), gs.onDown(RemoteKey.PLAY_PAUSE, 1, 600))
        assertEquals(emptyList<KeyGesture>(), gs.tick(990))
        assertEquals(listOf(g(RemoteKey.PLAY_PAUSE, Gesture.PRESS)), gs.onUp(RemoteKey.PLAY_PAUSE, 995))
    }

    @Test fun arrowsActOnTheDownAndRepeat() {
        val gs = KeyGestures()
        assertEquals(listOf(g(RemoteKey.LEFT, Gesture.PRESS)), gs.onDown(RemoteKey.LEFT, 0, 0))
        assertEquals(listOf(g(RemoteKey.LEFT, Gesture.REPEAT)), gs.onDown(RemoteKey.LEFT, 1, 500))
        assertEquals(listOf(g(RemoteKey.LEFT, Gesture.REPEAT)), gs.onDown(RemoteKey.LEFT, 2, 550))
        assertEquals(emptyList<KeyGesture>(), gs.onUp(RemoteKey.LEFT, 600))
    }

    @Test fun aSwallowedKeyGivesNothingUntilReleased() {
        val gs = KeyGestures()
        gs.onDown(RemoteKey.OK, 0, 0)
        gs.swallow(RemoteKey.OK, 10)
        assertEquals(emptyList<KeyGesture>(), gs.tick(2_000))
        assertEquals(emptyList<KeyGesture>(), gs.onUp(RemoteKey.OK, 2_100))
        // The next press of it is an ordinary press again.
        gs.onDown(RemoteKey.OK, 0, 3_000)
        assertEquals(listOf(g(RemoteKey.OK, Gesture.PRESS)), gs.onUp(RemoteKey.OK, 3_100))
    }

    @Test fun aRepeatWhoseDownWasNeverSeenStartsAfresh() {
        // Focus arrived on the face mid-hold (an overlay closed under a held key).
        val gs = KeyGestures()
        assertEquals(emptyList<KeyGesture>(), gs.onDown(RemoteKey.OK, 7, 5_000))
        assertEquals(listOf(g(RemoteKey.OK, Gesture.PRESS)), gs.onUp(RemoteKey.OK, 5_100))
    }
}
