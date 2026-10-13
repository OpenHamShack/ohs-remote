package com.openhamshack.remote.ptt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The remote lease on virtual time, against a fake box that behaves as the
 * contract says. Each test is one way a transmission can end, and checks
 * that it ends - and that nothing keeps a carrier up that should not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PttControllerTest {

    /** A box: keyed or not, for whom, and every call it saw. */
    private class FakeBox(private val scope: TestScope) : PttLink {
        var keyed = false
        var keyedFor: String? = null
        var lastBeat = 0L
        val calls = mutableListOf<String>()
        var refuse: String? = null
        var reachable = true
        var keyGate: CompletableDeferred<Unit>? = null

        override suspend fun key(remote: String, force: Boolean): KeyAnswer {
            calls += "key"
            keyGate?.await()
            if (!reachable) throw IOException("unreachable")
            refuse?.let { return KeyAnswer(it) }
            keyed = true; keyedFor = remote; lastBeat = scope.testScheduler.currentTime
            return KeyAnswer(null)
        }

        override suspend fun heartbeat(remote: String): Boolean {
            calls += "beat"
            if (!reachable) throw IOException("unreachable")
            if (!keyed || keyedFor != remote) return false
            lastBeat = scope.testScheduler.currentTime
            return true
        }

        override suspend fun unkey() {
            calls += "unkey"
            if (!reachable) throw IOException("unreachable")
            keyed = false; keyedFor = null
        }

        /** The box's own 5 s lease. */
        fun leaseTick() {
            if (keyed && scope.testScheduler.currentTime - lastBeat > 5000) { keyed = false; keyedFor = null }
        }
    }

    private fun TestScope.controller(box: FakeBox) =
        PttController(box, "me", backgroundScope, backgroundScope, { testScheduler.currentTime })

    @Test
    fun keyingHeartbeatsEverySecondUntilStopped() = runTest {
        val box = FakeBox(this)
        val ptt = controller(box)
        ptt.key()
        runCurrent()
        assertEquals(PttPhase.Keyed, ptt.state.value.phase)
        assertEquals("me", box.keyedFor)
        advanceTimeBy(10_000); runCurrent()
        assertTrue("about ten heartbeats in ten seconds", box.calls.count { it == "beat" } in 10..11)
        ptt.unkey(); runCurrent()
        assertFalse(box.keyed)
        val beats = box.calls.count { it == "beat" }
        advanceTimeBy(5_000); runCurrent()
        assertEquals("no heartbeat after the key-down", beats, box.calls.count { it == "beat" })
    }

    @Test
    fun aRefusalIsShownAndNothingHeartbeats() = runTest {
        val box = FakeBox(this).apply { refuse = "Not transmitting: set your callsign" }
        val ptt = controller(box)
        ptt.key(); runCurrent()
        assertEquals(PttPhase.Idle, ptt.state.value.phase)
        assertEquals("Not transmitting: set your callsign", ptt.state.value.error)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(0, box.calls.count { it == "beat" })
    }

    @Test
    fun goingToTheBackgroundStopsHeartbeatsThenUnkeys() = runTest {
        val box = FakeBox(this)
        val ptt = controller(box)
        ptt.key(); runCurrent()
        advanceTimeBy(2_500); runCurrent()
        ptt.release("the app went to the background"); runCurrent()
        assertFalse(box.keyed)
        assertEquals(PttPhase.Idle, ptt.state.value.phase)
        assertTrue(ptt.state.value.error!!.contains("background"))
        assertEquals("unkey", box.calls.last())
    }

    @Test
    fun releaseNeverStopsATransmissionThisAppDidNotStart() = runTest {
        val box = FakeBox(this).apply { keyed = true; keyedFor = null }   // keyed at the box
        val ptt = controller(box)
        ptt.release("the screen was locked"); runCurrent()
        assertTrue(box.keyed)
        assertTrue(box.calls.isEmpty())
    }

    @Test
    fun theBoxEndingItIsBelievedAtTheNextHeartbeat() = runTest {
        val box = FakeBox(this)
        val ptt = controller(box)
        ptt.key(); runCurrent()
        box.keyed = false   // time-out at the box
        advanceTimeBy(1_100); runCurrent()
        assertEquals(PttPhase.Idle, ptt.state.value.phase)
        assertNotNull(ptt.state.value.error)
    }

    @Test
    fun lostContactGivesUpWithinThreeSecondsAndTheBoxLeaseIsTheBackstop() = runTest {
        val box = FakeBox(this)
        val ptt = controller(box)
        ptt.key(); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        box.reachable = false
        advanceTimeBy(1_500); runCurrent()
        assertTrue(ptt.state.value.linkTrouble)
        assertEquals(PttPhase.Keyed, ptt.state.value.phase)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(PttPhase.Idle, ptt.state.value.phase)
        assertTrue(ptt.state.value.error!!.startsWith("Lost contact"))
        // The key-down cannot get through; the box's lease ends it.
        for (t in 0 until 8) { advanceTimeBy(500); box.leaseTick() }
        assertFalse(box.keyed)
        val beats = box.calls.count { it == "beat" }
        box.reachable = true
        advanceTimeBy(5_000); runCurrent()
        assertEquals("never heartbeats again on its own", beats, box.calls.count { it == "beat" })
    }

    @Test
    fun lettingGoWhileTheKeyUpIsOutUnkeysWhicheverArrivesLast() = runTest {
        val box = FakeBox(this).apply { keyGate = CompletableDeferred() }
        val ptt = controller(box)
        ptt.key(); runCurrent()
        assertEquals(PttPhase.Keying, ptt.state.value.phase)
        ptt.release("the screen was locked"); runCurrent()
        // Our first key-down reached the box before the key-up did...
        box.keyGate!!.complete(Unit); runCurrent()
        // ...and the key-up's answer triggers a second one.
        assertFalse(box.keyed)
        assertEquals(PttPhase.Idle, ptt.state.value.phase)
        assertEquals(0, box.calls.count { it == "beat" })
    }

    @Test
    fun statusOffAirTwiceAfterTheGraceEndsIt() = runTest {
        val box = FakeBox(this)
        val ptt = controller(box)
        ptt.key(); runCurrent()
        ptt.onStatus(onAir = false, txError = "")   // within the grace: the encoder is starting
        ptt.onStatus(onAir = false, txError = "")
        assertEquals(PttPhase.Keyed, ptt.state.value.phase)
        advanceTimeBy(3_500); runCurrent()
        ptt.onStatus(onAir = false, txError = "Not transmitting: the encoder stopped")
        assertEquals(PttPhase.Keyed, ptt.state.value.phase)
        ptt.onStatus(onAir = false, txError = "Not transmitting: the encoder stopped")
        assertEquals(PttPhase.Idle, ptt.state.value.phase)
        assertEquals("Not transmitting: the encoder stopped", ptt.state.value.error)
    }

    @Test
    fun aSecondKeyWhileKeyedIsIgnored() = runTest {
        val box = FakeBox(this)
        val ptt = controller(box)
        ptt.key(); runCurrent()
        ptt.key(); runCurrent()
        assertEquals(1, box.calls.count { it == "key" })
        assertNull(ptt.state.value.error)
    }
}
