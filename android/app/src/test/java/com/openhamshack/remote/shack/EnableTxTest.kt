package com.openhamshack.remote.shack

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Enable TX's arm and heartbeat on virtual time, against a fake server that
 * behaves as openhamshack-nb's autotx does (SERVER-API "Automatic transmit"):
 * armed for one remote, disarmed after 5 s without a heartbeat, 409 to a
 * heartbeat once disarmed. Each test is one way it can end, and checks that
 * it ends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EnableTxTest {

    private class FakeServer(private val scope: TestScope) : AutoTxLink {
        var armedFor: String? = null
        var lastBeat = 0L
        val calls = mutableListOf<String>()
        val bodies = mutableListOf<JSONObject>()
        var refuse: ShackException? = null
        var reachable = true
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun arm(remote: String, choice: TxChoice): AutoTxStatus? {
            calls += "arm"
            bodies += choice.body(remote)
            gate?.await()
            if (!reachable) throw IOException("unreachable")
            refuse?.let { throw it }
            armedFor = remote; lastBeat = scope.testScheduler.currentTime
            return status()
        }

        override suspend fun heartbeat(remote: String): Boolean {
            calls += "beat"
            if (!reachable) throw IOException("unreachable")
            if (armedFor != remote) return false
            lastBeat = scope.testScheduler.currentTime
            return true
        }

        override suspend fun disarm() {
            calls += "off"
            if (!reachable) throw IOException("unreachable")
            armedFor = null
        }

        /** The server's own 5 s lease on the arm. */
        fun leaseTick() {
            if (armedFor != null && scope.testScheduler.currentTime - lastBeat > 5000) armedFor = null
        }

        fun status(why: String = "") = AutoTxStatus(armedFor != null, armedFor, "ft8", true, "", "CQ M0TST IO75", why,
            AutoTxStatus.SEQUENCED, AutoTxStatus.LINES)
    }

    private fun TestScope.controller(s: FakeServer) =
        EnableTxController(s, "me", backgroundScope, backgroundScope, { testScheduler.currentTime })

    @Test fun armingHeartbeatsEverySecondUntilStopped() = runTest {
        val s = FakeServer(this)
        val tx = controller(s)
        tx.enable(TxChoice.Cq); runCurrent()
        assertEquals(EnableTxPhase.Armed, tx.state.value.phase)
        assertEquals("me", s.armedFor)
        assertTrue(s.bodies.single().getBoolean("cq"))
        advanceTimeBy(10_000); runCurrent()
        s.leaseTick()
        assertEquals("the heartbeat holds the arm between overs", "me", s.armedFor)
        assertTrue("about ten heartbeats in ten seconds", s.calls.count { it == "beat" } in 10..11)
        tx.stop(); runCurrent()
        assertEquals(null, s.armedFor)
        val beats = s.calls.count { it == "beat" }
        advanceTimeBy(5_000); runCurrent()
        assertEquals("no heartbeat after the disarm", beats, s.calls.count { it == "beat" })
    }

    @Test fun aTvRefusedForItsLevelIsToldWhyAndNothingHeartbeats() = runTest {
        val s = FakeServer(this).apply { refuse = ShackException(403, "level_too_low", "This needs the Transmit level") }
        val tx = controller(s)
        tx.enable(TxChoice.Cq); runCurrent()
        assertEquals(EnableTxPhase.Idle, tx.state.value.phase)
        assertEquals(TxRefusal("level_too_low", "This needs the Transmit level", 403), tx.state.value.error)
        advanceTimeBy(5_000); runCurrent()
        assertFalse(s.calls.contains("beat"))
    }

    @Test fun theServerDisarmingIsBelievedAtTheNextHeartbeat() = runTest {
        val s = FakeServer(this)
        val tx = controller(s)
        tx.enable(TxChoice.Cq); runCurrent()
        s.armedFor = null                     // QSO logged, or the watchdog, at the server
        advanceTimeBy(1_100); runCurrent()
        assertEquals(EnableTxPhase.Idle, tx.state.value.phase)
        assertEquals(EnableTxController.SERVER_OFF, tx.state.value.error?.code)
        val n = s.calls.size
        advanceTimeBy(3_000); runCurrent()
        assertEquals("nothing more is sent", n, s.calls.size)
    }

    @Test fun aLostNetworkGivesUpAfterThreeSecondsAndTheServerStopsAfterFive() = runTest {
        val s = FakeServer(this)
        val tx = controller(s)
        tx.enable(TxChoice.Cq); runCurrent()
        s.reachable = false
        advanceTimeBy(1_500); runCurrent()
        assertTrue("trouble shown before giving up", tx.state.value.linkTrouble)
        assertEquals(EnableTxPhase.Armed, tx.state.value.phase)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(EnableTxPhase.Idle, tx.state.value.phase)
        assertEquals(EnableTxController.LINK_LOST, tx.state.value.error?.code)
        advanceTimeBy(3_000); runCurrent()
        s.leaseTick()
        assertEquals("the server's own lease ends it", null, s.armedFor)
    }

    @Test fun backgroundingDisarmsButOnlyWhatThisDeviceArmed() = runTest {
        val s = FakeServer(this)
        val tx = controller(s)
        tx.release("background"); runCurrent()
        assertFalse("never armed: nothing sent", s.calls.contains("off"))
        tx.enable(TxChoice.Cq); runCurrent()
        tx.release("background"); runCurrent()
        assertEquals(null, s.armedFor)
        assertEquals(EnableTxController.RELEASED, tx.state.value.error?.code)
    }

    @Test fun stoppingWhileTheArmIsOnItsWayDisarmsWhenItLands() = runTest {
        val s = FakeServer(this).apply { gate = CompletableDeferred() }
        val tx = controller(s)
        tx.enable(TxChoice.Cq); runCurrent()
        assertEquals(EnableTxPhase.Arming, tx.state.value.phase)
        tx.stop(); runCurrent()
        s.gate!!.complete(Unit); runCurrent()
        assertEquals("the late arm is undone", null, s.armedFor)
        assertEquals(EnableTxPhase.Idle, tx.state.value.phase)
        advanceTimeBy(3_000); runCurrent()
        assertFalse(s.calls.contains("beat"))
    }

    @Test fun statusSayingNotArmedForUsTwiceEndsItWithTheServersReason() = runTest {
        val s = FakeServer(this)
        val tx = controller(s)
        tx.enable(TxChoice.Cq); runCurrent()
        tx.onStatus(s.status())
        advanceTimeBy(3_500)
        val off = AutoTxStatus(false, null, "ft8", false, "", "", "QSO with EA8XYZ logged", AutoTxStatus.SEQUENCED, AutoTxStatus.LINES)
        // Heartbeats still answer here (the server's view lags), so only the polls end it.
        tx.onStatus(off)
        assertEquals(EnableTxPhase.Armed, tx.state.value.phase)
        tx.onStatus(off)
        assertEquals(EnableTxPhase.Idle, tx.state.value.phase)
        assertEquals("QSO with EA8XYZ logged", tx.state.value.error?.text)
    }

    @Test fun aReplyCarriesTheDecodeAsTheServerAsksForIt() {
        val d = SlotDecode("ft8", 1_760_000_000.0, -12, 0.2, 1234, "CQ EA8XYZ IL18", call = "EA8XYZ", grid = "IL18")
        val b = TxChoice.Reply(d).body("me")
        assertTrue(b.getBoolean("on"))
        assertEquals("me", b.getString("remote"))
        val r = b.getJSONObject("reply")
        assertEquals("CQ EA8XYZ IL18", r.getString("text"))
        assertEquals(1_760_000_000.0, r.getDouble("slot"), 0.0)
        assertEquals(-12, r.getInt("snr"))
        assertEquals(1234, r.getInt("df"))
        assertEquals("EA8XYZ", r.getString("call"))
        assertEquals("a line", TxChoice.Line("a line").body("me").getString("text"))
    }

    @Test fun choosingAgainWhileArmedKeepsOneHeartbeat() = runTest {
        val s = FakeServer(this)
        val tx = controller(s)
        tx.enable(TxChoice.Cq); runCurrent()
        tx.enable(TxChoice.Line("CQ CQ")); runCurrent()
        assertEquals(EnableTxPhase.Armed, tx.state.value.phase)
        advanceTimeBy(5_000); runCurrent()
        assertTrue("one heartbeat a second, not two", s.calls.count { it == "beat" } in 5..6)
    }

    @Test fun aReplyAnswersTheStationNotTheCqModifier() {
        fun sender(t: String) = SlotDecode("ft8", 0.0, 0, 0.0, 1000, t).sender
        assertEquals("OH8ZZ", sender("CQ DX OH8ZZ KP24"))
        assertEquals("EA8XYZ", sender("CQ POTA EA8XYZ IL18"))
        assertEquals("EA8XYZ", sender("CQ EA8XYZ IL18"))
        assertEquals("EA8XYZ", sender("GM4BRI EA8XYZ -12"))
        assertEquals("EA8/GM4BRI", sender("CQ EA8/GM4BRI"))
        assertEquals("3D2AG", sender("CQ 3D2AG RH91"))
        assertEquals("", sender("CQ DX"))
        assertEquals("", sender("TNX 73 GL"))
        assertEquals("from the decoder when it says", "K1ABC", SlotDecode("ft8", 0.0, 0, 0.0, 0, "CQ DX X", call = "K1ABC").sender)
        assertEquals("OH8ZZ", TxChoice.Reply(SlotDecode("ft8", 0.0, 0, 0.0, 1000, "CQ DX OH8ZZ KP24")).body("me")
            .getJSONObject("reply").getString("call"))
    }
}
