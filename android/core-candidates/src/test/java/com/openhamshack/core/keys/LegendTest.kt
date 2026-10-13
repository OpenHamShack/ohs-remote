package com.openhamshack.core.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The legend says what the keys do on this face: no more, no less, and never something else. */
class LegendTest {
    @Test fun theLegendListsExactlyWhatTheMapBindsAndMarksWhatTheFaceCannot() {
        val can: (RemoteAction) -> Boolean = { it != RemoteAction.BAND_NEXT && it != RemoteAction.BAND_PREV && it != RemoteAction.TRANSMIT }
        val rows = legendRows(KeyMap.STANDARD, FaceClass.RADIO, can)
        // Each line agrees with the map the dispatcher uses.
        rows.forEach { assertEquals(KeyMap.STANDARD.action(FaceClass.RADIO, it.key, it.gesture), it.action) }
        // Every press and hold of the map is there (digits as one line).
        val bound = KeyMap.STANDARD.of(FaceClass.RADIO).filter { it.gesture != Gesture.REPEAT && it.key.digit in listOf(null, 0) }
        assertEquals(bound.map { it.key to it.gesture }, rows.map { it.key to it.gesture })
        assertFalse(rows.single { it.key == RemoteKey.CHANNEL_UP }.active)
        assertFalse(rows.single { it.key == RemoteKey.PLAY_PAUSE && it.gesture == Gesture.HOLD }.active)
        assertTrue(rows.single { it.key == RemoteKey.LEFT }.active)
        assertTrue("Back always does something", rows.single { it.key == RemoteKey.BACK }.active)
        assertEquals(1, rows.count { it.key.digit != null })
    }

    @Test fun aVideoFaceSaysChannelsWhereARadioSaysTuning() {
        val rows = legendRows(KeyMap.STANDARD, FaceClass.VIDEO) { true }
        assertEquals(RemoteAction.CHANNEL_PREV, rows.single { it.key == RemoteKey.LEFT }.action)
    }
}
