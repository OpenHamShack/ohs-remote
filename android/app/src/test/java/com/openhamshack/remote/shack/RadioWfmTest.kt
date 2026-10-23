package com.openhamshack.remote.shack

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Radio's wide FM as the server has it since openhamshack-nb d76e533
 * (SERVER-API "Wide FM"): a receiver too slow for it says so in
 * `modes.wfm`, and the pickers then do not offer it; the de-emphasis
 * setting; and the transmit state the "Receive only" strip is drawn from.
 */
class RadioWfmTest {
    private fun state(json: String) = EngineState(JSONObject(json))

    private val hl2 = """{"mode": "usb", "modes": {"usb": {"decode": true, "encode": true, "missing": ""},
        "wfm": {"decode": false, "encode": false, "missing": "Wide FM needs the receiver to run at 240 kS/s or more"}}}"""

    @Test fun aReceiverTooSlowForWideFmIsNotOfferedIt() {
        val s = state(hl2)
        assertEquals("Wide FM needs the receiver to run at 240 kS/s or more", s.modeMissing("wfm"))
        assertNull(s.modeMissing("usb"))
        val u = EngineUi(stepHz = 1000, state = s, modes = listOf("usb", "fm", "wfm"))
        assertEquals(listOf("usb", "fm"), u.offered)
        // A listening area that wants wfm falls back to fm there, as on a server with no wfm at all.
        val spot = ListenAreas.target(ListenAreas.byId("fm-broadcast")!!, u.offered)
        assertEquals("fm", spot.mode)
    }

    @Test fun aServerThatSaysNothingOfAModeStillOffersIt() {
        val u = EngineUi(stepHz = 1000, state = state("""{"mode": "fm"}"""), modes = listOf("fm", "wfm"))
        assertEquals(listOf("fm", "wfm"), u.offered)
        // An RSPdx: wfm decodes, never encodes; it is offered.
        val rsp = state("""{"modes": {"wfm": {"decode": true, "encode": false, "missing": ""}}}""")
        assertNull(rsp.modeMissing("wfm"))
    }

    @Test fun deemphasisAndTheTransmitStateAreRead() {
        val s = state("""{"mode": "wfm", "wfmDeemphasis": 75.0, "txBlocked": "WFM is for listening only: nothing is ever transmitted in it",
            "device": "soapy", "deviceStatus": {"kind": "soapy", "tx": "receive only"}}""")
        assertEquals(75.0, s.wfmDeemphasis!!, 0.0)
        assertEquals("WFM is for listening only: nothing is ever transmitted in it", s.txBlocked)
        assertTrue(s.receiveOnlyRadio)
        // The simulator transmits (when its licence allows); an older server says nothing of either.
        val sim = state("""{"device": "sim", "deviceStatus": {"kind": "sim"}, "txBlocked": null}""")
        assertFalse(sim.receiveOnlyRadio)
        assertEquals("", sim.txBlocked)
        assertNull(sim.wfmDeemphasis)
        assertTrue(state("""{"device": "sdrplay"}""").receiveOnlyRadio)
    }
}
