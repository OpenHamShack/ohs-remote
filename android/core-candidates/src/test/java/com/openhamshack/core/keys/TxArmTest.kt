package com.openhamshack.core.keys

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Transmit armed then confirmed (REMOTE-KEYS.md §5.2, as Brian decided it),
 * on a clock the test sets. The rules: a single press never starts
 * anything; the arm runs out in five seconds; while armed any key but OK
 * and Play/Pause disarms; while on, every key stops.
 */
class TxArmTest {
    private fun press(k: RemoteKey) = KeyGesture(k, Gesture.PRESS)

    private fun armed(at: Long = 0): TxArm = TxArm().apply {
        assertEquals(TxKey.ARM, gesture(KeyGesture(RemoteKey.PLAY_PAUSE, Gesture.HOLD), at))
        arm(at)
        assertEquals(TxPhase.ARMED, phase)
    }

    @Test fun noSinglePressEverStartsAnything() {
        val tx = TxArm()
        for (k in RemoteKey.entries) for (g in Gesture.entries) {
            if (k == RemoteKey.PLAY_PAUSE && g == Gesture.HOLD) continue
            assertEquals("$k $g", TxKey.PASS, tx.gesture(KeyGesture(k, g), 0))
            assertEquals("$k down", TxKey.PASS, tx.keyDown(k, 0))
        }
        assertEquals(TxPhase.IDLE, tx.phase)
    }

    @Test fun okOrPlayPauseWithinFiveSecondsConfirms() {
        for (k in listOf(RemoteKey.OK, RemoteKey.PLAY_PAUSE)) {
            val tx = armed()
            assertEquals("the down is held for its press", TxKey.TAKEN, tx.keyDown(k, 4_000))
            assertEquals(TxKey.CONFIRM, tx.gesture(press(k), 4_900))
            assertEquals(TxPhase.STARTING, tx.phase)
            tx.started()
            assertEquals(TxPhase.ON, tx.phase)
        }
    }

    @Test fun theArmRunsOutAfterFiveSeconds() {
        val tx = armed(1_000)
        assertEquals(TxKey.PASS, tx.tick(5_999))
        assertEquals(TxKey.DISARM, tx.tick(6_000))
        assertEquals(TxPhase.IDLE, tx.phase)
        // A confirm after it ran out is an ordinary key again: nothing starts.
        assertEquals(TxKey.PASS, tx.gesture(press(RemoteKey.OK), 6_100))
        assertEquals(TxPhase.IDLE, tx.phase)
    }

    @Test fun anyOtherKeyDisarmsWithoutStarting() {
        for (k in RemoteKey.entries - setOf(RemoteKey.OK, RemoteKey.PLAY_PAUSE)) {
            val tx = armed()
            assertEquals("$k", TxKey.DISARM, tx.keyDown(k, 100))
            assertEquals(TxPhase.IDLE, tx.phase)
        }
        // OK held rather than pressed is not a confirm either.
        val tx = armed()
        assertEquals(TxKey.DISARM, tx.gesture(KeyGesture(RemoteKey.OK, Gesture.HOLD), 700))
    }

    @Test fun whileOnEveryKeyStops() {
        for (k in RemoteKey.entries) {
            val tx = armed()
            tx.gesture(press(RemoteKey.OK), 100); tx.started()
            assertEquals("$k", TxKey.STOP, tx.keyDown(k, 60_000))
            assertEquals(TxPhase.IDLE, tx.phase)
        }
        // And while the start is still on its way.
        val tx = armed()
        tx.gesture(press(RemoteKey.PLAY_PAUSE), 100)
        assertEquals(TxKey.STOP, tx.keyDown(RemoteKey.LEFT, 200))
    }

    @Test fun theServerHasTheLastWord() {
        val tx = armed()
        tx.gesture(press(RemoteKey.OK), 100)
        tx.sync(serverOn = false)
        assertEquals("not yet answered: still starting", TxPhase.STARTING, tx.phase)
        tx.sync(serverOn = true)
        assertEquals(TxPhase.ON, tx.phase)
        tx.sync(serverOn = false)
        assertEquals("ended at the station", TxPhase.IDLE, tx.phase)
        // An arm is the device's alone; the server saying off does not touch it.
        val a = armed()
        a.sync(serverOn = false)
        assertEquals(TxPhase.ARMED, a.phase)
    }

    @Test fun aTouchConfirmOnlyFromArmed() {
        val tx = TxArm()
        assertEquals(false, tx.confirm(0))
        tx.arm(0)
        assertEquals(false, tx.confirm(TxArm.ARM_MS))
        tx.arm(10_000)
        assertEquals(true, tx.confirm(10_001))
        assertEquals(TxPhase.STARTING, tx.phase)
    }
}
