package com.openhamshack.kind.stationflow

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.openhamshack.kind.stationflow.api.CwRead
import com.openhamshack.kind.stationflow.api.DvCall
import com.openhamshack.kind.stationflow.ui.HeardTab
import com.openhamshack.kind.stationflow.ui.heardTabs
import com.openhamshack.kind.stationflow.ui.openingTab
import com.openhamshack.kind.stationflow.api.RadioState
import com.openhamshack.kind.stationflow.api.ScopeCaps
import com.openhamshack.kind.stationflow.radio.agoSeconds
import com.openhamshack.kind.stationflow.radio.attChoices
import com.openhamshack.kind.stationflow.radio.filterChoices
import com.openhamshack.kind.stationflow.radio.hasFrontEnd
import com.openhamshack.kind.stationflow.radio.modeLabel
import com.openhamshack.kind.stationflow.radio.modeRows
import com.openhamshack.kind.stationflow.radio.nextIn
import com.openhamshack.kind.stationflow.radio.preampChoices
import com.openhamshack.kind.stationflow.radio.refLabel
import com.openhamshack.kind.stationflow.radio.refStep
import com.openhamshack.kind.stationflow.radio.showsDv
import com.openhamshack.kind.stationflow.radio.spanLabel
import com.openhamshack.kind.stationflow.radio.zoomSpan

/** The face's overlays and scope controls: what they offer, from the radio's state. */
class ChoicesTest {
    private val nine = RadioState.parse(fixture("tvapi-state-example.json"))

    @Test fun `the mode overlay is the radio's modes in its order, then the DATA ones`() {
        val st = RadioState(
            modes = listOf("LSB", "USB", "AM", "CW", "RTTY", "FM", "CWR", "RTTYR", "DV", "DD"),
            dataModes = listOf("LSB", "USB", "AM", "FM"), mode = "USB", dataMode = true,
        )
        val rows = modeRows(st)
        assertEquals(listOf("LSB", "USB", "CW", "CW-R", "RTTY", "RTTY-R", "AM"), rows[0].map { it.label })
        assertEquals(listOf("FM", "DV", "DD"), rows[1].map { it.label })
        assertEquals(listOf("LSB-D", "USB-D", "AM-D", "FM-D"), rows[2].map { it.label })
        // What POST api/mode takes: the CI-V name, and "-D" for DATA.
        assertEquals("CWR", rows[0][3].key)
        assertEquals("USB-D", rows[2][1].key)
        // The badge prints the radio's name for a CI-V mode, whichever the module sends.
        assertEquals("CW-R", modeLabel("CWR"))
        assertEquals("USB-D", modeLabel("USB-D"))
        // USB with DATA on is USB-D, not USB.
        assertEquals(listOf("USB-D"), rows.flatten().filter { it.selected }.map { it.label })
    }

    @Test fun `a mode the overlay does not know is still offered, and none the radio lacks`() {
        val st = RadioState(modes = listOf("USB", "PSK", "CW"), dataModes = emptyList(), mode = "PSK")
        val all = modeRows(st).flatten()
        assertEquals(listOf("USB", "CW", "PSK"), all.map { it.label })
        assertTrue(all.single { it.label == "PSK" }.selected)
        assertTrue(modeRows(RadioState()).isEmpty())
    }

    @Test fun `filter, preamp and attenuator choices come from what the radio reports`() {
        assertEquals(listOf(false, true, false), filterChoices(RadioState(filter = "FIL2")).map { it.selected })
        val st = RadioState(preampModes = listOf("OFF", "P.AMP", "EXT", "P.AMP+EXT"), preampMode = 1,
            preampModesRefused = setOf(2, 3), preamps = listOf("P.AMP"), attenuators = listOf(10), att = 10)
        val pre = preampChoices(st) { "EXT P.AMP off in radio menu" }
        assertEquals(listOf("mode:0", "mode:1", "mode:2", "mode:3"), pre.map { it.key })
        assertEquals(listOf(true, true, false, false), pre.map { it.enabled })
        assertEquals("EXT P.AMP off in radio menu", pre[2].note)
        assertNull(pre[1].note)
        assertTrue(pre[1].selected)
        // An older module: OFF and the levels.
        val old = preampChoices(RadioState(preamps = listOf("P.AMP1", "P.AMP2"), preamp = 2)) { null }
        assertEquals(listOf("level:0", "level:1", "level:2"), old.map { it.key })
        assertTrue(old[2].selected)
        assertEquals(listOf("OFF", "10dB"), attChoices(st).map { it.label })
        assertTrue(attChoices(st)[1].selected)
        // A radio with neither has no front-end control at all.
        assertTrue(preampChoices(RadioState()) { null }.isEmpty() && attChoices(RadioState()).isEmpty())
        assertFalse(hasFrontEnd(RadioState()))
        assertTrue(hasFrontEnd(st))
    }

    private val caps = ScopeCaps(
        spansHz = listOf(5000, 10000, 20000, 50000, 100000, 200000, 500000, 1000000), modes = listOf("center", "fixed"),
        edges = 3, refMin = -20.0, refMax = 20.0, refStep = 0.5, speeds = listOf("fast", "mid", "slow"),
        vbw = listOf("narrow", "wide"), averaging = listOf(0, 2, 3, 4),
    )

    @Test fun `zoom walks the model's span ladder and stops at its ends`() {
        assertEquals(50000L, zoomSpan(caps, 100000, zoomIn = true))
        assertEquals(200000L, zoomSpan(caps, 100000, zoomIn = false))
        assertNull(zoomSpan(caps, 5000, zoomIn = true))
        assertNull(zoomSpan(caps, 1000000, zoomIn = false))
        // Off the ladder: the nearest step in that direction.
        assertEquals(50000L, zoomSpan(caps, 70000, zoomIn = true))
        assertEquals(100000L, zoomSpan(caps, 70000, zoomIn = false))
        assertNull(zoomSpan(caps.copy(spansHz = emptyList()), 100000, zoomIn = true))
    }

    @Test fun `the reference level moves a dB a press, on the model's grid and inside its range`() {
        assertEquals(1.0, refStep(caps, 0.0, up = true)!!, 1e-9)
        assertEquals(-1.0, refStep(caps, 0.0, up = false)!!, 1e-9)
        assertEquals(20.0, refStep(caps, 19.5, up = true)!!, 1e-9)
        assertNull(refStep(caps, 20.0, up = true))
        assertNull(refStep(caps, null, up = true))
        // Snapped to the step: 0.3 is not a level the radio has.
        assertEquals(1.5, refStep(caps, 0.3, up = true)!!, 1e-9)
        assertEquals("+1.5dB", refLabel(1.5))
        assertEquals("0.0dB", refLabel(0.0))
        assertEquals("−3.0dB", refLabel(-3.0))
        assertEquals("±2.5k", spanLabel(5000))
        assertEquals("mid", nextIn(caps.speeds, "fast"))
        assertEquals("fast", nextIn(caps.speeds, "slow"))
        assertEquals("slow", nextIn(caps.speeds, "fast", -1))
    }

    @Test fun `D-STAR shows only in DV and only from a module that reports it`() {
        val dv = RadioState.parse(JSONObject("""{"radios": {"r": {"mode": "DV", "dv": null, "dv_heard": []}}}"""))
        assertTrue(showsDv(dv))
        assertNull(dv.dv!!.last)
        assertFalse(showsDv(dv.copy(mode = "FM")))
        // A module without D-STAR at all: no D-STAR tab, even in DV.
        assertFalse(showsDv(RadioState.parse(JSONObject("""{"radios": {"r": {"mode": "DV"}}}"""))))
        assertEquals(90L, agoSeconds(1000.0, 1_090_000))
        assertNull(agoSeconds(null, 1_090_000))
        assertEquals("M0XYZ /M", DvCall("M0XYZ", suffix = "/M").callLine)
        assertEquals("M0XYZ /ID51", DvCall("M0XYZ", suffix = "ID51").callLine)
        assertEquals("M0XYZ", DvCall("M0XYZ").callLine)
    }

    @Test fun `the decode window has CW and D-STAR tabs only from a module that reads them, and opens on what the radio is doing`() {
        val st = RadioState.parse(fixture("icom-scope-nr2-dv-state.json"))
        assertNull(st.cw)
        assertEquals(listOf(HeardTab.Decodes, HeardTab.Stations, HeardTab.Aprs, HeardTab.Dv), heardTabs(st))
        assertEquals(HeardTab.Dv, openingTab(st))
        val cw = st.copy(mode = "CW", cw = CwRead(active = true, text = "CQ", wpm = 18.0, pitch = 600))
        assertEquals(HeardTab.Cw, openingTab(cw))
        assertTrue(HeardTab.Cw in heardTabs(cw))
        // In FM the window opens on the decodes as it always did.
        assertEquals(HeardTab.Decodes, openingTab(st.copy(mode = "FM")))
        // The contract example has the CW decoder (TV-API 9) and no D-STAR.
        assertEquals(listOf(HeardTab.Decodes, HeardTab.Stations, HeardTab.Aprs, HeardTab.Cw), heardTabs(nine))
        assertEquals(listOf(HeardTab.Decodes, HeardTab.Stations, HeardTab.Aprs), heardTabs(nine.copy(cw = null)))
        assertEquals(HeardTab.Decodes, openingTab(null))
    }

    @Test fun `the 1_23_0 state has none of the new controls`() {
        assertNull(nine.scopeCaps)
        assertNull(nine.nr2)
    }
}
