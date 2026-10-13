package com.openhamshack.core.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The transient hints: at open, then only for actions not yet used (moved from NB's CalmScreenTest). */
class KeyHintsTest {
    private val all = setOf(RemoteAction.TUNE_UP, RemoteAction.TUNE_DOWN, RemoteAction.CHOOSE, RemoteAction.FOCUS_DOWN,
        RemoteAction.STEP_UP, RemoteAction.CONTROLS)

    @Test fun hintsShowAtOpenThenFadeAndOnlyNewKeysBringThemBack() {
        var h = KeyHints().opened(0)
        assertTrue(h.visible(1_000, all))
        assertFalse(h.visible(KeyHints.OPEN_MS, all))
        h = h.pressed(RemoteAction.TUNE_DOWN, 10_000)
        assertTrue("a first use brings the rest back", h.visible(10_001, all))
        assertFalse("tune down teaches tune up too", RemoteAction.TUNE_UP in h.pending(all))
        assertFalse(h.visible(10_000 + KeyHints.AGAIN_MS, all))
        // Tuning again teaches nothing new: they stay away.
        assertEquals(h, h.pressed(RemoteAction.TUNE_UP, 20_000))
        // A hint for a key that does nothing here (no decoder open) is not offered.
        assertFalse(RemoteAction.FOCUS_DOWN in h.pending(all - RemoteAction.FOCUS_DOWN))
        // Every key known: nothing to show, even at open.
        val known = all.fold(KeyHints()) { acc, k -> acc.pressed(k, 0) }.opened(30_000)
        assertFalse(known.visible(30_001, all))
    }
}
