package com.openhamshack.core.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Raw key events through the dispatcher, as a remote sends them: what
 * fires, what Compose is left to have, and transmit's precedence over all.
 */
class KeyDispatcherTest {
    private val everything: (RemoteAction) -> Boolean = { true }

    /** A press: down then up, [ms] apart. */
    private fun KeyDispatcher.press(k: RemoteKey, at: Long, scope: KeyScope = KeyScope.SURFACE,
                                    can: (RemoteAction) -> Boolean = everything, ms: Long = 80): List<KeyOutcome> =
        listOf(onKeyDown(k, 0, at, scope, can), onKeyUp(k, at + ms, can))

    private fun fired(os: List<KeyOutcome>) = os.flatMap { it.fired }.map { it.action }

    @Test fun theSurfaceGetsTheMap() {
        val d = KeyDispatcher(FaceClass.RADIO)
        assertEquals(listOf(RemoteAction.TUNE_DOWN), fired(d.press(RemoteKey.LEFT, 0)))
        assertEquals(listOf(RemoteAction.CONTROLS), fired(d.press(RemoteKey.OK, 1_000)))
        assertEquals(listOf(RemoteAction.STEP_UP), fired(d.press(RemoteKey.FAST_FORWARD, 2_000)))
        assertEquals(listOf(RemoteAction.MUTE), fired(d.press(RemoteKey.PLAY_PAUSE, 3_000)))
        val v = KeyDispatcher(FaceClass.VIDEO)
        assertEquals(listOf(RemoteAction.CHANNEL_PREV), fired(v.press(RemoteKey.LEFT, 0)))
        assertEquals(listOf(RemoteAction.BAND_NEXT), fired(v.press(RemoteKey.FAST_FORWARD, 1_000)))
    }

    @Test fun okActsOnReleaseSoTheReleaseCannotLandOnWhatItOpened() {
        val d = KeyDispatcher(FaceClass.RADIO)
        val down = d.onKeyDown(RemoteKey.OK, 0, 0, KeyScope.SURFACE, everything)
        assertTrue("the down is the face's", down.consumed)
        assertTrue(down.fired.isEmpty())
        assertEquals(listOf(Fired(RemoteAction.CONTROLS)), d.onKeyUp(RemoteKey.OK, 90, everything).fired)
    }

    @Test fun okHeldTypesAFrequencyAndDoesNotAlsoOpenControls() {
        val d = KeyDispatcher(FaceClass.RADIO)
        d.onKeyDown(RemoteKey.OK, 0, 0, KeyScope.SURFACE, everything)
        assertEquals(listOf(Fired(RemoteAction.FREQUENCY)), d.onKeyDown(RemoteKey.OK, 1, 520, KeyScope.SURFACE, everything).fired)
        assertTrue(d.onKeyUp(RemoteKey.OK, 900, everything).fired.isEmpty())
    }

    @Test fun heldArrowsTuneOnOnARadioButNotChannelsOnVideo() {
        val r = KeyDispatcher(FaceClass.RADIO)
        r.onKeyDown(RemoteKey.RIGHT, 0, 0, KeyScope.SURFACE, everything)
        assertEquals(listOf(Fired(RemoteAction.TUNE_UP, repeat = true)), r.onKeyDown(RemoteKey.RIGHT, 1, 500, KeyScope.SURFACE, everything).fired)
        val v = KeyDispatcher(FaceClass.VIDEO)
        v.onKeyDown(RemoteKey.RIGHT, 0, 0, KeyScope.SURFACE, everything)
        val rep = v.onKeyDown(RemoteKey.RIGHT, 1, 500, KeyScope.SURFACE, everything)
        assertTrue("taken, so focus does not wander off the picture", rep.consumed)
        assertTrue(rep.fired.isEmpty())
    }

    @Test fun onAControlTheDpadIsCompose_sButAcceleratorsStillAct() {
        val d = KeyDispatcher(FaceClass.RADIO)
        for (k in listOf(RemoteKey.LEFT, RemoteKey.UP, RemoteKey.DOWN, RemoteKey.OK)) {
            val os = d.press(k, 0, KeyScope.CONTROL)
            assertFalse("$k moves focus or presses the control", os.any { it.consumed })
        }
        assertEquals(listOf(RemoteAction.STEP_DOWN), fired(d.press(RemoteKey.REWIND, 0, KeyScope.CONTROL)))
        assertEquals(listOf(RemoteAction.BAND_NEXT), fired(d.press(RemoteKey.CHANNEL_UP, 0, KeyScope.CONTROL)))
        assertEquals(listOf(RemoteAction.SETTINGS), fired(d.press(RemoteKey.MENU, 0, KeyScope.CONTROL)))
    }

    @Test fun insideAnOverlayOnlyMuteAndTransmitReachBeyondIt() {
        val d = KeyDispatcher(FaceClass.RADIO)
        for (k in RemoteKey.entries - RemoteKey.PLAY_PAUSE) {
            assertFalse("$k is the overlay's", d.press(k, 0, KeyScope.OVERLAY).any { it.consumed })
        }
        assertEquals(listOf(RemoteAction.MUTE), fired(d.press(RemoteKey.PLAY_PAUSE, 0, KeyScope.OVERLAY)))
    }

    @Test fun backIsNeverTheMapsOutsideTransmit() {
        // Back goes to the screen's BackHandlers: the innermost overlay first, then leave.
        val d = KeyDispatcher(FaceClass.RADIO)
        assertFalse(d.press(RemoteKey.BACK, 0).any { it.consumed })
    }

    @Test fun anActionTheFaceLacksFallsThrough() {
        val d = KeyDispatcher(FaceClass.RADIO)
        val noBand: (RemoteAction) -> Boolean = { it != RemoteAction.BAND_NEXT && it != RemoteAction.BAND_PREV }
        assertFalse(d.press(RemoteKey.CHANNEL_UP, 0, can = noBand).any { it.consumed })
    }

    @Test fun holdPlayPauseArmsThenOkStarts() {
        val d = KeyDispatcher(FaceClass.RADIO)
        d.onKeyDown(RemoteKey.PLAY_PAUSE, 0, 0, KeyScope.SURFACE, everything)
        assertEquals(1_000L, d.nextDueMs())
        assertEquals(TxKey.ARM, d.tick(1_000, everything).tx)
        assertEquals(TxPhase.ARMED, d.tx.phase)
        assertTrue("releasing the hold neither mutes nor confirms", d.onKeyUp(RemoteKey.PLAY_PAUSE, 1_200, everything).let { it.fired.isEmpty() && it.tx == TxKey.PASS })
        assertEquals(TxPhase.ARMED, d.tx.phase)
        val os = d.press(RemoteKey.OK, 3_000)
        assertEquals(TxKey.CONFIRM, os.last().tx)
        assertTrue("OK confirms and opens nothing", fired(os).isEmpty())
    }

    @Test fun anyOtherKeyWhileArmedDisarmsAndDoesNothingElse() {
        val d = KeyDispatcher(FaceClass.RADIO)
        d.onKeyDown(RemoteKey.PLAY_PAUSE, 0, 0, KeyScope.SURFACE, everything)
        d.tick(1_000, everything)
        d.onKeyUp(RemoteKey.PLAY_PAUSE, 1_100, everything)
        val os = d.press(RemoteKey.RIGHT, 2_000)
        assertEquals(TxKey.DISARM, os.first().tx)
        assertTrue("the arrow did not tune", fired(os).isEmpty())
        assertEquals(TxPhase.IDLE, d.tx.phase)
    }

    @Test fun backWhileArmedDisarmsInsteadOfLeaving() {
        val d = KeyDispatcher(FaceClass.RADIO)
        d.tx.arm(0)
        val down = d.onKeyDown(RemoteKey.BACK, 0, 10, KeyScope.SURFACE, everything)
        assertTrue(down.consumed)
        assertEquals(TxKey.DISARM, down.tx)
        assertTrue("its release is spent too", d.onKeyUp(RemoteKey.BACK, 100, everything).consumed)
    }

    @Test fun whileOnAnyKeyStopsOnItsDownAndNothingElseHappens() {
        for (k in RemoteKey.entries) for (scope in KeyScope.entries) {
            val d = KeyDispatcher(FaceClass.RADIO)
            d.tx.arm(0); d.tx.confirm(1); d.tx.started()
            val down = d.onKeyDown(k, 0, 10_000, scope, everything)
            assertEquals("$k in $scope", TxKey.STOP, down.tx)
            assertTrue(down.consumed)
            val rest = listOf(d.onKeyDown(k, 1, 10_600, scope, everything), d.onKeyUp(k, 10_700, everything))
            assertTrue("$k in $scope: nothing after the stop", fired(rest).isEmpty() && rest.all { it.consumed && it.tx == TxKey.PASS })
        }
    }

    @Test fun aKeyHeldFromBeforeTheStartStopsOnItsNextRepeat() {
        val d = KeyDispatcher(FaceClass.RADIO)
        d.onKeyDown(RemoteKey.LEFT, 0, 0, KeyScope.SURFACE, everything)
        d.tx.arm(0); d.tx.confirm(1); d.tx.started()
        assertEquals(TxKey.STOP, d.onKeyDown(RemoteKey.LEFT, 3, 700, KeyScope.SURFACE, everything).tx)
    }

    @Test fun theArmRunsOutByTheClock() {
        val d = KeyDispatcher(FaceClass.RADIO)
        d.tx.arm(0)
        assertEquals(TxArm.ARM_MS, d.nextDueMs())
        assertEquals(TxKey.DISARM, d.tick(TxArm.ARM_MS, everything).tx)
        // OK after it ran out: an ordinary OK, nothing transmitted.
        assertEquals(listOf(RemoteAction.CONTROLS), fired(d.press(RemoteKey.OK, TxArm.ARM_MS + 100)))
    }

    @Test fun anOkDownWhileArmedThatOutlivesTheArmDoesNothing() {
        val d = KeyDispatcher(FaceClass.RADIO)
        d.tx.arm(0)
        d.onKeyDown(RemoteKey.OK, 0, 4_900, KeyScope.SURFACE, everything)
        val up = d.onKeyUp(RemoteKey.OK, 5_100, everything)
        assertTrue("neither confirmed nor opened controls", up.fired.isEmpty() && up.tx == TxKey.PASS)
        assertEquals(TxPhase.IDLE, d.tx.phase)
    }

    @Test fun aFaceThatCannotTransmitSaysSo() {
        val d = KeyDispatcher(FaceClass.RADIO)
        val noTx: (RemoteAction) -> Boolean = { it != RemoteAction.TRANSMIT }
        d.onKeyDown(RemoteKey.PLAY_PAUSE, 0, 0, KeyScope.SURFACE, noTx)
        val held = d.tick(1_000, noTx)
        assertTrue(held.txUnavailable)
        assertEquals(TxPhase.IDLE, d.tx.phase)
        assertTrue("and the release does not mute", d.onKeyUp(RemoteKey.PLAY_PAUSE, 1_100, noTx).fired.isEmpty())
    }

    @Test fun digitsOpenTheFrequencyWithTheDigit() {
        val d = KeyDispatcher(FaceClass.RADIO)
        assertEquals(Fired(RemoteAction.FREQUENCY, digit = 7), d.press(RemoteKey.DIGIT_7, 0).flatMap { it.fired }.single())
    }
}
