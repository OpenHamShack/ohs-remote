package com.openhamshack.remote.shack

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DAB as Radio serves it (openhamshack-nb SERVER-API "DAB", dbb3e44): the
 * start with the remembered station, the session's `meta` feed with codes
 * and sentences and programme text, the catalogue's missingText, and who
 * holds the receiver.
 */
class DabApiTest {
    private val b11d = DabBlocks.byLabel("11D")!!

    private val session = JSONObject("""{"id": "ds_3f2a", "decoder": "dab", "panel": "meta", "holdsReceiver": true, "block": "11D",
      "results": [],
      "meta": {"sync": true, "complete": true,
        "ensemble": {"id": "C1CE", "label": "Shack Test Mux", "shortLabel": "ShackTes"},
        "services": [{"sid": "C221", "label": "Shack MP2 Tone", "shortLabel": "ShackMP2", "bitrate": 128, "kind": "dab",
                      "why": null, "whyText": "", "needs": []},
                     {"sid": "C3A7", "label": "Shack DAB+ Data", "shortLabel": "ShackDAB", "bitrate": 64, "kind": "dab+",
                      "why": "needs_codec:faad2", "whyText": "Needs faad2: install it to hear DAB+ stations.", "needs": ["faad2"]}],
        "playing": "C221", "why": null, "whyText": "", "audio": {"codec": "mp2"}, "block": "11D",
        "dls": "Shack MP2 Tone - now: 1 kHz left, 1.5 kHz right"}}""")

    @Test fun aStartPlaysTheStationLastChosenThere() {
        val b = DabFeed.start(b11d, "C221")
        assertEquals(222_064_000.0, b.getDouble("freqHz"), 0.0)
        assertEquals("11D", b.getJSONObject("params").getString("block"))
        assertEquals("C221", b.getJSONObject("params").getString("service"))
        // None remembered: the ensemble is listed and nothing plays.
        assertFalse(DabFeed.start(b11d, null).getJSONObject("params").has("service"))
    }

    @Test fun theSessionsMetaIsReadWithItsSentencesAndProgrammeText() {
        val d = DabFeed.apply(DabUi(b11d, session = "ds_3f2a"), session)
        assertTrue(d.sync)
        assertEquals(true, d.complete)
        assertEquals("Shack Test Mux", d.ensemble)
        assertEquals("C221", d.playing)
        assertEquals("Shack MP2 Tone - now: 1 kHz left, 1.5 kHz right", d.dls)
        assertTrue(d.services[0].playable)
        // The DAB+ station without faad2: dimmed, with its sentence rather than its code.
        assertFalse(d.services[1].playable)
        assertEquals("Needs faad2: install it to hear DAB+ stations.", d.services[1].why)
        assertEquals("needs_codec:faad2", d.services[1].code)
        assertEquals("", d.problem)
    }

    @Test fun radiosEventItemsCarryTheFeedInFields() {
        val poll = JSONObject("""{"results": [{"rev": 5, "type": "meta", "fields": {"sync": false, "why": "no_signal",
            "whyText": "No DAB signal on this block.", "services": []}}]}""")
        val d = DabFeed.apply(DabUi(b11d, session = "s"), poll)
        assertEquals(5, d.since)
        assertEquals("No DAB signal on this block.", d.problem)
        assertNull(d.dls)
    }

    @Test fun aScanWaitsForTheListToBeWhole() {
        val partial = DabUi(b11d, session = "s", sync = true, services = listOf(DabService("C221", "x", "dab", 128, "")), complete = false)
        assertEquals(DabScan.Verdict.WAIT, DabScan.verdict(partial, 500))
        assertEquals(DabScan.Verdict.KEEP, DabScan.verdict(partial.copy(complete = true), 900))
        // Never whole within the wait: kept with what it has.
        assertEquals(DabScan.Verdict.KEEP, DabScan.verdict(partial.copy(complete = null), 100))
    }

    @Test fun theCatalogueSaysWhatCannotPlayOnce() {
        val cat = JSONObject("""{"decoders": [{"id": "rtty", "partial": false},
            {"id": "dab", "available": true, "partial": true, "missing": ["faad2"], "missingText": "DAB+ needs faad2."}]}""")
        assertEquals("DAB+ needs faad2.", DabFeed.missingText(cat))
        assertEquals("", DabFeed.missingText(JSONObject("""{"decoders": [{"id": "dab", "partial": false, "missingText": ""}]}""")))
    }

    @Test fun whoHoldsTheReceiverAndWhatAnOlderControllerSays() {
        val st = EngineState(JSONObject("""{"rxOwner": {"by": "dab", "session": "ds_3f2a", "block": "11D", "freqHz": 222064000,
            "prior": {"vfo": 145500000, "mode": "fm"}, "text": "The receiver is playing DAB from block 11D: stop the DAB station to tune or change mode"}}"""))
        val o = st.rxOwner!!
        assertEquals("ds_3f2a", o.session)
        assertEquals("11D", o.block)
        assertTrue(o.text.startsWith("The receiver is playing DAB"))
        assertNull(EngineState(JSONObject("""{"rxOwner": {}}""")).rxOwner)
        assertNull(EngineState(JSONObject("{}")).rxOwner)
        val old = EngineStatus.parse(JSONObject("""{"kind": "radio", "hostLibs": {"supported": false, "root": null, "libraries": {},
            "why": "Update the controller to use SDRplay radios and DAB+"}}"""))
        assertFalse(old.hostLibs!!.supported)
        assertEquals("Update the controller to use SDRplay radios and DAB+", old.hostLibs!!.why)
        assertNull(EngineStatus.parse(JSONObject("""{"kind": "radio", "hostLibs": {}}""")).hostLibs)
    }
}
