package com.openhamshack.remote.shack

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The decisions behind Radio's overhauled screen (Brian's approved mockups,
 * 23/10/2026): the band picker's sections and filters, the short row of
 * bands used last, what sits beside Mute, a drag along the scope, and the
 * receiver the header names.
 */
class RadioScreenTest {
    private val vocab by lazy { PlanVocab.parse(JSONObject(File("src/main/assets/bandplan/vocab.json").readText())) }
    private fun buttons(lo: Double? = null, hi: Double? = null) = RadioBands.buttons(vocab, lo, hi, ListenAreas.ALL)
    private fun button(name: String) = buttons().first { it.name == name }
    private fun state(json: String) = EngineState(JSONObject(json))

    // --- the band picker -----------------------------------------------------

    @Test fun thePickerSplitsAtThirtyMegahertzInFrequencyOrder() {
        val (low, high) = BandGrid.sections(buttons(), BandFilter.ALL)
        assertEquals(BandSection.LOW, low.first)
        assertEquals(listOf("lw", "mw", "160m"), low.second.take(3).map { it.name })
        // CB is HF; 10 m closes the first section and 6 m opens the second.
        assertEquals("10m", low.second.last().name)
        assertTrue(low.second.any { it.name == "cb" })
        assertEquals(listOf("6m", "4m", "fm-broadcast", "airband", "2m"), high.second.take(5).map { it.name })
        for (s in listOf(low, high)) assertEquals(s.second.sortedBy { it.band.loHz }, s.second)
    }

    @Test fun theFiltersKeepTheAmateurBandsOrTheListeningAreas() {
        val amateur = BandGrid.sections(buttons(), BandFilter.AMATEUR).flatMap { it.second }
        assertTrue(amateur.none { it.area != null })
        assertTrue(amateur.any { it.name == "20m" })
        val listen = BandGrid.sections(buttons(), BandFilter.LISTEN)
        assertEquals(listOf("lw", "mw", "cb", "cb-uk"), listen.first { it.first == BandSection.LOW }.second.map { it.name })
        assertEquals(listOf("fm-broadcast", "airband", "marine", "dab", "lpd433", "pmr446"),
            listen.first { it.first == BandSection.HIGH }.second.map { it.name })
    }

    @Test fun aSectionTheReceiverCannotReachIsLeftOut() {
        // A Pluto (70 MHz-6 GHz) has nothing below 30 MHz: one section only.
        val pluto = BandGrid.sections(buttons(70e6, 6e9), BandFilter.ALL)
        assertEquals(listOf(BandSection.HIGH), pluto.map { it.first })
    }

    // --- the bands used last ---------------------------------------------------

    @Test fun theRowShowsTheLastThreeBandsPressedLessTheLitOne() {
        var r = emptyList<String>()
        for (n in listOf("20m", "2m", "fm-broadcast", "20m", "airband")) r = RecentBands.push(r, n)
        assertEquals(listOf("airband", "20m", "fm-broadcast", "2m"), r)
        // Airband is lit: the row is the three before it, as the mockup's "2 m, FM bc, 20 m".
        assertEquals(listOf("20m", "fm-broadcast", "2m"), RecentBands.shown(r, buttons(), button("airband")).map { it.name })
        // On another band, the newest three.
        assertEquals(listOf("airband", "20m", "fm-broadcast"), RecentBands.shown(r, buttons(), button("40m")).map { it.name })
        // A band the receiver cannot reach is dropped, not shown dead.
        assertEquals(listOf("airband", "fm-broadcast", "2m"), RecentBands.shown(r, buttons(30e6, 2e9), null).map { it.name })
    }

    @Test fun theRowIsKeptAsOneLineAndADamagedValueIsNoRow() {
        val r = listOf("airband", "20m", "2m")
        assertEquals(r, RecentBands.decode(RecentBands.encode(r)))
        assertEquals(emptyList<String>(), RecentBands.decode(null))
        assertEquals(listOf("2m", "20m"), RecentBands.decode(" 2m,,20m,2m "))
    }

    // --- beside Mute -----------------------------------------------------------

    @Test fun aReceiveOnlyAreaReplacesTheTransmitButton() {
        val sim = state("""{"mode": "am", "device": "sim", "deviceStatus": {"kind": "sim"}}""")
        val air = TxSlot.of(canTransmit = true, lit = button("airband"), state = sim)
        assertFalse(air.ptt)
        assertEquals(RxOnlyWhy.AREA, air.why)
        assertEquals("airband", air.area?.name)
        // On 2 m the button is there; the licence's refusal is the button's own label, not the strip.
        assertTrue(TxSlot.of(true, button("2m"), state("""{"mode": "fm", "txBlocked": "The station's country is not known"}""")).ptt)
        // LPD433 is inside 70 cm: no strip.
        assertTrue(TxSlot.of(true, button("lpd433"), sim).ptt)
    }

    @Test fun theServersSentenceWinsForAReceiveOnlyRadioAndForWideFm() {
        val rsp = state("""{"mode": "am", "device": "soapy", "deviceStatus": {"tx": "receive only"}, "txBlocked": "This radio can only receive"}""")
        val slot = TxSlot.of(true, button("airband"), rsp)
        assertEquals(RxOnlyWhy.RADIO, slot.why)
        assertEquals("This radio can only receive", slot.sentence)
        assertEquals("airband", slot.area?.name)
        val wfm = TxSlot.of(true, button("fm-broadcast"),
            state("""{"mode": "wfm", "txBlocked": "WFM is for listening only: nothing is ever transmitted in it"}"""))
        assertEquals(RxOnlyWhy.MODE, wfm.why)
        assertEquals("WFM is for listening only: nothing is ever transmitted in it", wfm.sentence)
    }

    @Test fun aDabSessionHoldingTheReceiverNamesDabNotTheModeLeftBehind() {
        val held = state("""{"mode": "wfm", "txBlocked": "WFM is for listening only", "rxOwner": {"by": "dab", "session": "s1", "block": "11D",
            "text": "The receiver is playing DAB from block 11D"}}""")
        assertEquals(TxSlot(false, RxOnlyWhy.AREA, area = button("dab")), TxSlot.of(true, button("dab"), held))
    }

    @Test fun aDeviceThatCannotTransmitShowsTheStripEverywhere() {
        val slot = TxSlot.of(canTransmit = false, lit = button("20m"), state = state("""{"mode": "usb"}"""))
        assertEquals(TxSlot(false, RxOnlyWhy.DEVICE), slot)
    }

    // --- dragging the scope ----------------------------------------------------

    @Test fun aDragOnTheWideScopeCarriesTheDialAndLandsOnAChannel() {
        // 2 MHz shown; a fifth of the width to the right is 400 kHz up, on the 12.5 kHz raster.
        assertEquals(145_900_000.0, Tuning.dragged(145_500_000.0, 0.2, 2e6, 12_500, grab = false), 0.0)
        assertEquals(145_506_250.0 + 0.0, Tuning.dragged(145_500_000.0, 0.0031, 2e6, 12_500, grab = false), 6_250.0)
        // Zoomed, the finger drags the spectrum: to the right is down.
        assertEquals(145_100_000.0, Tuning.dragged(145_500_000.0, 0.2, 2e6, 12_500, grab = true), 0.0)
        // Airband channels in whole Hz, and the dial's limits hold.
        assertEquals(118_008_333.0, Tuning.dragged(118_000_000.0, 0.0001, 1e8, Tuning.AIRBAND_833, grab = false), 0.0)
        assertEquals(1e6, Tuning.dragged(1.2e6, -0.5, 2e6, 1_000, grab = false, min = 1e6), 0.0)
    }

    @Test fun pmrChannelsAreCountedFromTheirFirst() {
        assertEquals(446_018_750.0, Tuning.nearest(446_020_000.0, 12_500, 446_006_250.0), 0.0)
    }

    // --- the header ------------------------------------------------------------

    @Test fun theReceiverIsNamedFromTheEnginesDevice() {
        assertEquals(Receiver.SDRPLAY, Receiver.of("sdrplay"))
        assertEquals(Receiver.SIMULATOR, Receiver.of("sim"))
        assertNull(Receiver.of(""))
        assertNull(Receiver.of("something-new"))
    }
}
