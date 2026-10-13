package com.openhamshack.remote.shack

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import com.openhamshack.remote.shack.FlexStandIn.Companion.resource
import com.openhamshack.remote.shack.FlexStandIn.Companion.until

/**
 * The radio's receive controls on Remote's Flex kind - noise reduction and
 * the blankers, AGC, the receive antenna - against the captured module:
 * what is offered for the radio the module has, and exactly what the radio
 * would be asked for.
 */
class FlexControlsTest {
    private var fake: FlexStandIn? = null

    private fun open(hello: String = resource("flex-hello-dsp.json")): Pair<FlexStandIn, FlexSession> {
        val f = FlexStandIn(hello).start()
        fake = f
        return f to f.open()
    }

    @After fun down() {
        fake?.stop()
    }

    @Test fun aRadioWithTheNewerDspOffersAllElevenInTheTablesOrder() {
        val st = FlexState.parse(JSONObject(resource("flex-hello-dsp.json")).getJSONObject("state"))
        val names = FlexDspTable.offered(st.worked).map { it.name }
        assertEquals(listOf("nr", "nb", "wnb", "anf", "apf", "nrl", "nrs", "rnn", "nrf", "anfl", "anft"), names)
        val s = st.worked!!
        assertEquals(FlexDsp(on = false, level = 50), s.dsp["nr"])
        // RNN and ANFT have no level: none is read, none is offered.
        assertNull(s.dsp["rnn"]!!.level)
        assertNull(s.dsp["anft"]!!.level)
        assertEquals(listOf("ANT1", "ANT2", "RX_A", "RX_B", "XVTA"), s.antList)
        assertEquals("ANT1", s.rxAnt)
        assertEquals("med", s.agcMode)
        assertEquals(65, s.agcThreshold)
    }

    @Test fun everyFunctionThatIsOnIsListedForTheStatusChipsInTheTablesOrder() {
        val st = FlexState.parse(JSONObject(resource("flex-hello-dsp.json")).getJSONObject("state"))
        val s = st.worked!!
        // As captured, everything is off: no chip.
        assertEquals(emptyList<String>(), FlexDspTable.onNow(s).map { it.name })
        // ANF and NB on, switched in that order: shown in the table's order, NB first.
        val on = s.copy(dsp = s.dsp + ("anf" to FlexDsp(true, 50)) + ("nb" to FlexDsp(true, 50)))
        assertEquals(listOf("nb", "anf"), FlexDspTable.onNow(on).map { it.name })
        // A function the build does not know is left out, as in offered().
        val odd = on.copy(dsp = on.dsp + ("zzz" to FlexDsp(true, null)))
        assertEquals(listOf("nb", "anf"), FlexDspTable.onNow(odd).map { it.name })
        assertEquals(emptyList<String>(), FlexDspTable.onNow(null).map { it.name })
    }

    @Test fun aRadioWithoutThemOffersOnlyThePublishedFive() {
        val st = FlexState.parse(JSONObject(resource("flex-hello-basic.json")).getJSONObject("state"))
        assertEquals(listOf("nr", "nb", "wnb", "anf", "apf"), FlexDspTable.offered(st.worked).map { it.name })
    }

    @Test fun aNewerFunctionTheRadioDoesNotOfferIsNeverAskedFor() {
        val (f, s) = open(resource("flex-hello-basic.json"))
        s.setDsp("nrl", enabled = true)
        s.setDsp("rnn", enabled = true)
        s.setDsp("nr", enabled = true)
        assertEquals(listOf("nr"), f.writes().map { it.getJSONObject("arguments").getString("name") })
    }

    @Test fun dspRequestsAreTheModulesActionWithOnlyWhatChanged() {
        val (f, s) = open()
        s.setDsp("nrl", enabled = true, level = 40)
        s.setDsp("nb", level = 70)
        s.setDsp("rnn", enabled = true, level = 30)   // RNN has no level: the switch only
        s.setDsp("anft", level = 20)                  // nothing left to send
        s.setDsp("wnb", level = 140)                  // clamped to the radio's 0-100
        val w = f.writes()
        assertEquals(listOf("slice.dsp"), w.map { it.getString("action") }.distinct())
        val args = w.map { it.getJSONObject("arguments").toString() }
        assertEquals(listOf(
            """{"slice_id":0,"name":"nrl","enabled":true,"level":40}""",
            """{"slice_id":0,"name":"nb","level":70}""",
            """{"slice_id":0,"name":"rnn","enabled":true}""",
            """{"slice_id":0,"name":"wnb","level":100}""",
        ).map { JSONObject(it).toString() }, args.map { JSONObject(it).toString() })
    }

    @Test fun theOnePressNrSwitchFlipsWhatTheRadioSaid() {
        val (f, s) = open()
        assertFalse(s.ui.value.nrOn)
        s.toggleNr()
        assertEquals(true, f.writes().single().getJSONObject("arguments").getBoolean("enabled"))
        // The module applies it and pushes its state; the next press turns it off.
        val on = JSONObject(f.hello).apply { getJSONObject("state").getJSONObject("slices").getJSONObject("0").put("nr", true) }
        f.socket!!.send(on.put("type", "state").toString())
        until("the state") { s.ui.value.nrOn }
        s.toggleNr()
        assertEquals(listOf(true, false), f.writes().map { it.getJSONObject("arguments").getBoolean("enabled") })
    }

    @Test fun aRefusalFromTheModuleIsSaidInItsWords() {
        val (f, s) = open()
        f.replies += MockResponse().setResponseCode(409).setBody(resource("flex-dsp-not-offered.json"))
        s.setDsp("nrl", enabled = true)
        until("the refusal") { f.said.isNotEmpty() }
        assertEquals("this radio does not offer NRL", f.said.single())
        assertTrue("crashed: ${f.crashes}", f.crashes.isEmpty())
    }

    @Test fun agcModeAndThresholdAreSentAsSliceAgc() {
        val (f, s) = open()
        s.setAgcMode("fast")
        s.setAgcMode("loud")            // not one of the radio's: nothing sent
        s.stepAgcThreshold(+1)          // 65 -> 70
        s.setAgcThreshold(130)          // clamped
        val w = f.writes()
        assertEquals(listOf("slice.agc"), w.map { it.getString("action") }.distinct())
        assertEquals(listOf("""{"slice_id":0,"mode":"fast"}""", """{"slice_id":0,"threshold":70}""", """{"slice_id":0,"threshold":100}""")
            .map { JSONObject(it).toString() }, w.map { it.getJSONObject("arguments").toString() })
    }

    @Test fun theAntennaIsReceiveOnlyFromTheRadiosListAndShownUntilTheRadioSaysOtherwise() {
        val (f, s) = open()
        s.setAntenna("ANT9")            // not in the radio's list
        s.setAntenna("RX_A")
        val w = f.writes()
        assertEquals(1, w.size)
        val args = w.single().getJSONObject("arguments")
        assertEquals("slice.antenna", w.single().getString("action"))
        assertEquals("RX_A", args.getString("rx"))
        assertFalse("never the transmit antenna", args.has("tx"))
        // The module's state still says ANT1 (the radio does not echo a
        // client's own change): the screen shows the choice...
        until("the held antenna") { s.ui.value.rxAnt == "RX_A" }
        f.socket!!.send(JSONObject(f.hello).put("type", "state").toString())
        Thread.sleep(200)
        assertEquals("RX_A", s.ui.value.rxAnt)
        // ...until the radio reports anything else (here somebody at the
        // radio chose ANT2): the radio wins.
        val ant2 = JSONObject(f.hello).apply { getJSONObject("state").getJSONObject("slices").getJSONObject("0").put("rx_ant", "ANT2") }
        f.socket!!.send(ant2.put("type", "state").toString())
        until("the radio's antenna") { s.ui.value.rxAnt == "ANT2" }
        assertNull(s.ui.value.heldAnt)
    }

    @Test fun theClientRefusesTheTransmitAntennaAndEveryKeyingAction() {
        val c = FlexStandIn().let { fake = it.start(); it.client() }
        for (action in listOf("transmit.key", "transmit.tune", "atu.start", "slice.tx", "transmit.power")) {
            try {
                runBlocking { c.command(action, JSONObject()) }
                fail("$action was sent")
            } catch (_: IllegalArgumentException) {
            }
        }
        try {
            runBlocking { c.command("slice.antenna", JSONObject().put("slice_id", 0).put("tx", "ANT2")) }
            fail("the transmit antenna was sent")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(fake!!.sent.isEmpty())
    }

    @Test fun aViewOnlyDeviceSaysSoAndSendsNothing() {
        val (f, s) = open()
        f.replies += MockResponse().setResponseCode(403).setBody("""{"error": "Control needed", "code": "level_too_low"}""")
        s.toggleNr()
        until("the 403") { s.ui.value.viewOnly }
        s.setAgcMode("slow")
        s.setAntenna("ANT2")
        until("said") { "view only" in f.said }
        assertEquals(1, f.writes().size)
    }
}
