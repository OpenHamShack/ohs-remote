package com.openhamshack.remote.shack

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Radio's listening areas (RADIO-LISTEN-CONTRACT §2), the steps they brought
 * (8.33 kHz airband, 9 kHz, 10 kHz, 200 kHz) and DAB's station list (§3),
 * against the band-plan data the app ships.
 */
class ListenAreasTest {
    private val vocab by lazy { PlanVocab.parse(JSONObject(File("src/main/assets/bandplan/vocab.json").readText())) }
    private fun all(lo: Double? = null, hi: Double? = null) = RadioBands.buttons(vocab, lo, hi, ListenAreas.ALL)
    private fun area(id: String) = ListenAreas.byId(id)!!

    // --- the band row --------------------------------------------------------

    @Test fun areasSitAmongTheBandsInFrequencyOrderTaggedAsAreas() {
        val names = all().map { it.name }
        assertEquals(listOf("lw", "mw", "160m"), names.take(3))
        // CB starts at 26.965 MHz: after 12 m (24.89), before 10 m (28.0).
        assertEquals(listOf("12m", "cb", "cb-uk", "10m"), names.subList(names.indexOf("12m"), names.indexOf("10m") + 1))
        assertEquals(listOf("4m", "fm-broadcast", "airband", "2m", "marine", "dab", "70cm", "lpd433", "pmr446", "23cm"),
            names.subList(names.indexOf("4m"), names.indexOf("23cm") + 1))
        // The order is by each button's lower edge throughout.
        val los = all().map { it.band.loHz }
        assertEquals(los.sorted(), los)
        // An area button carries its area; an amateur band's does not.
        assertEquals("airband", all().first { it.name == "airband" }.area?.id)
        assertNull(all().first { it.name == "2m" }.area)
    }

    @Test fun aReceiverLosesTheAreasItCannotReachAsItLosesBands() {
        // An HF receiver to 30 MHz: LW, MW and both CB plans, none of the VHF/UHF areas.
        val hf = all(1_000.0, 30_000_000.0).mapNotNull { it.area?.id }
        assertEquals(listOf("lw", "mw", "cb", "cb-uk"), hf)
        // A Pluto (70 MHz up): broadcast FM upward, no LW, MW or CB.
        val pluto = all(70_000_000.0, 6e9).mapNotNull { it.area?.id }
        assertEquals(listOf("fm-broadcast", "airband", "marine", "dab", "lpd433", "pmr446"), pluto)
        // Without areas (the default), the row is only the amateur bands, as before.
        assertTrue(RadioBands.buttons(vocab).none { it.area != null })
    }

    @Test fun thePressedAreaStaysLitInsideTheBandItSitsIn() {
        val b = all()
        // PMR446 is inside 70 cm, and the server names the band "70cm".
        assertEquals("pmr446", RadioBands.current(b, "70cm", 446_006_250.0, pressed = "pmr446")?.name)
        // Without a press, the band wins where they overlap.
        assertEquals("70cm", RadioBands.current(b, "70cm", 446_006_250.0)?.name)
        assertEquals("70cm", RadioBands.current(b, "", 446_006_250.0)?.name)
        // Tuned out of the pressed area, it no longer lights.
        assertEquals("70cm", RadioBands.current(b, "70cm", 432_200_000.0, pressed = "pmr446")?.name)
        // An area with no band around it lights by the dial alone.
        assertEquals("airband", RadioBands.current(b, "", 121_500_000.0)?.name)
    }

    @Test fun channelKeysStepThroughTheAreasToo() {
        val b = all()
        assertEquals("fm-broadcast", RadioBands.step(b, "4m", 70_200_000.0, 1)?.name)
        assertEquals("airband", RadioBands.step(b, "", 88_100_000.0, 1)?.name)
        assertEquals("2m", RadioBands.step(b, "", 121_500_000.0, 1)?.name)
        // From PMR446, pressed, inside 70 cm: on to 23 cm, and back to LPD433.
        assertEquals("23cm", RadioBands.step(b, "70cm", 446_006_250.0, 1, pressed = "pmr446")?.name)
        assertEquals("lpd433", RadioBands.step(b, "70cm", 446_006_250.0, -1, pressed = "pmr446")?.name)
        // The bottom of the row is LW now, and it stops there.
        assertNull(RadioBands.step(b, "", 198_000.0, -1))
    }

    // --- where a press goes -------------------------------------------------

    @Test fun anAreaGoesToItsSpotInItsModeWithItsStep() {
        val all = listOf("usb", "lsb", "cw", "am", "fm", "wfm")
        assertEquals(AreaSpot(121_500_000.0, "am", Tuning.AIRBAND_833), ListenAreas.target(area("airband"), all))
        assertEquals(AreaSpot(198_000.0, "am", 9_000), ListenAreas.target(area("lw"), all))
        assertEquals(AreaSpot(156_800_000.0, "fm", 25_000), ListenAreas.target(area("marine"), all))
        assertEquals(AreaSpot(446_006_250.0, "fm", 12_500), ListenAreas.target(area("pmr446"), all))
        assertEquals(AreaSpot(27_185_000.0, "fm", 10_000), ListenAreas.target(area("cb"), all))
        assertEquals(AreaSpot(88_100_000.0, "wfm", 100_000), ListenAreas.target(area("fm-broadcast"), all))
        // Every spot is inside its area, and on its own raster.
        for (a in ListenAreas.ALL) {
            assertTrue(a.id, a.spotHz in a)
            if (a.stepHz != Tuning.AIRBAND_833 && a.id != ListenAreas.DAB) assertEquals(a.id, 0.0, (a.spotHz - a.originHz) % a.stepHz, 0.0)
        }
    }

    @Test fun broadcastFmFallsBackToFmOnAServerWithoutWideFm() {
        val noWfm = listOf("usb", "lsb", "am", "fm")
        assertEquals("fm", ListenAreas.target(area("fm-broadcast"), noWfm).mode)
        // A server that names no modes at all is not second-guessed.
        assertEquals("wfm", ListenAreas.target(area("fm-broadcast"), emptyList()).mode)
        // A mode with no fallback that the server lacks leaves the mode alone.
        assertNull(ListenAreas.target(area("airband"), listOf("usb", "fm")).mode)
        // DAB is a decoder, not a mode: the mode is never touched.
        assertNull(ListenAreas.target(area("dab"), noWfm).mode)
    }

    // --- the steps ------------------------------------------------------------

    @Test fun airbandStepsLandOnThe833GridInWholeHertz() {
        val s = Tuning.AIRBAND_833
        assertEquals(118_008_333.0, Tuning.stepped(118_000_000.0, s, 1), 0.0)
        assertEquals(118_016_667.0, Tuning.stepped(118_000_000.0, s, 2), 0.0)
        // Three presses are exactly one 25 kHz channel: no drift from rounding.
        assertEquals(118_025_000.0, Tuning.stepped(118_000_000.0, s, 3), 0.0)
        assertEquals(118_025_000.0, Tuning.stepped(118_016_667.0, s, 1), 0.0)
        assertEquals(118_000_000.0, Tuning.stepped(118_008_333.0, s, -1), 0.0)
        // A press at a time, a hundred times, is a hundred channels on.
        var f = 121_500_000.0
        repeat(100) { f = Tuning.stepped(f, s, 1) }
        assertEquals(Math.round(121_500_000.0 + 100 * 25_000.0 / 3).toDouble(), f, 0.0)
        // Off the grid, the first press goes to the channel either side.
        assertEquals(118_008_333.0, Tuning.stepped(118_010_000.0, s, -1), 0.0)
        assertEquals(118_016_667.0, Tuning.stepped(118_010_000.0, s, 1), 0.0)
    }

    @Test fun anAreasChannelsAreCountedFromItsFirstChannel() {
        // PMR446's channels are 12.5 kHz apart from 446.00625 MHz.
        val o = ListenAreas.origin(446_006_250.0, 12_500)
        assertEquals(446_006_250.0, o, 0.0)
        assertEquals(446_018_750.0, Tuning.stepped(446_006_250.0, 12_500, 1, origin = o), 0.0)
        // From 0 Hz it would land between two channels: what the origin is for.
        assertEquals(446_012_500.0, Tuning.stepped(446_006_250.0, 12_500, 1), 0.0)
        // UK CB channel 19 to 20.
        assertEquals(27_791_250.0, Tuning.stepped(27_781_250.0, 10_000, 1, origin = ListenAreas.origin(27_781_250.0, 10_000)), 0.0)
        // Another step in the area, or the same step outside any area, counts from 0 Hz.
        assertEquals(0.0, ListenAreas.origin(446_006_250.0, 1_000), 0.0)
        assertEquals(0.0, ListenAreas.origin(145_500_000.0, 12_500), 0.0)
    }

    @Test fun theNewStepsHoldAtTheEndsOfTheLadder() {
        assertEquals(listOf(10, 100, 1_000, 5_000, 8_333, 9_000, 10_000, 12_500, 25_000, 100_000, 200_000), Tuning.RADIO_STEPS)
        assertEquals(10, Tuning.nextStep(Kind.RADIO, 10, -1))
        assertEquals(200_000, Tuning.nextStep(Kind.RADIO, 200_000, 1))
        assertEquals(100_000, Tuning.nextStep(Kind.RADIO, 200_000, -1))
        assertEquals(9_000, Tuning.nextStep(Kind.RADIO, Tuning.AIRBAND_833, 1))
        // Every area's step is on the ladder, so the step control can show and move it.
        for (a in ListenAreas.ALL) assertTrue(a.id, a.stepHz in Tuning.RADIO_STEPS)
    }

    @Test fun wideFmDrawsA180KhzPassband() {
        val p = ModeAudio.of("wfm", null, null, null, null, null)!!
        assertEquals(-90_000.0, p.lo, 0.0)
        assertEquals(90_000.0, p.hi, 0.0)
        assertEquals(0.0, p.centre, 0.0)
    }

    // --- DAB -----------------------------------------------------------------

    @Test fun dabBlocksAreTheBandIIITable() {
        assertEquals(222_064_000.0, DabBlocks.byLabel("11d")!!.centreHz, 0.0)
        assertEquals("5A", DabBlocks.ALL.first().label)
        assertEquals("13F", DabBlocks.ALL.last().label)
        assertEquals("12B", DabBlocks.at(225_650_000.0)?.label)
        assertNull(DabBlocks.at(100_000_000.0))
        assertEquals("5A", DabBlocks.step(DabBlocks.ALL.first(), -1).label)
        assertEquals("13F", DabBlocks.step(DabBlocks.ALL.last(), 1).label)
        assertEquals("12A", DabBlocks.step(DabBlocks.DEFAULT, 1).label)
        // Every block is inside the DAB area, and the area's spot is the default block.
        val dab = ListenAreas.byId(ListenAreas.DAB)!!
        assertTrue(DabBlocks.ALL.all { it.centreHz in dab })
        assertEquals(DabBlocks.DEFAULT.centreHz, dab.spotHz, 0.0)
    }

    @Test fun aSessionIsStartedOnTheBlocksCentre() {
        val b = DabFeed.start(DabBlocks.DEFAULT)
        assertEquals("dab", b.getString("decoder"))
        assertEquals(222_064_000.0, b.getDouble("freqHz"), 0.0)
        assertEquals("11D", b.getJSONObject("params").getString("block"))
    }

    private fun poll(vararg results: String) = JSONObject("""{"id":"ds_dab1","state":"running","results":[${results.joinToString(",")}]}""")

    private val synced = """{"kind":"decoder","session":"ds_dab1","rev":2,"type":"dab","sync":true,
        "ensemble":{"id":"C1CE","label":"BBC National DAB "},
        "services":[{"sid":"C221","label":"BBC Radio 4","bitrate":128,"kind":"dab"},
                    {"sid":"C222","label":"BBC Radio 6","bitrate":80,"kind":"dab+","why":"DAB+ needs faad2."},
                    {"label":"no id"}],
        "playing":null}"""

    @Test fun theFeedAcquiresThenListsTheServicesAndWhatPlays() {
        var d = DabUi(DabBlocks.DEFAULT, session = "ds_dab1")
        d = DabFeed.apply(d, poll("""{"rev":1,"type":"dab","sync":false,"services":[]}"""))
        assertTrue(d.acquiring)
        assertEquals(1, d.since)
        d = DabFeed.apply(d, poll(synced))
        assertFalse(d.acquiring)
        assertEquals("BBC National DAB", d.ensemble)
        // A service with no id is dropped: nothing could choose it.
        assertEquals(listOf("C221", "C222"), d.services.map { it.sid })
        assertNull(d.playing)
        // The dab+ service without faad2 is listed with its why and cannot be chosen.
        assertTrue(d.services[0].playable)
        assertFalse(d.services[1].playable)
        assertEquals("DAB+ needs faad2.", d.services[1].why)
        assertTrue(DabFeed.canPlay(d, "C221"))
        assertFalse(DabFeed.canPlay(d, "C222"))
        assertFalse(DabFeed.canPlay(d, "C999"))
        // A result already read is not read again; a newer one says what plays.
        assertEquals(d, DabFeed.apply(d, poll(synced)))
        d = DabFeed.apply(d, poll(synced.replace("\"rev\":2", "\"rev\":3").replace("\"playing\":null", "\"playing\":\"C221\"")))
        assertEquals("C221", d.playing)
        // Nothing can be played before the session exists.
        assertFalse(DabFeed.canPlay(d.copy(session = null), "C221"))
    }

    @Test fun aBlockWithNothingSaysWhyInTheServersWords() {
        val d = DabFeed.apply(DabUi(DabBlocks.ALL.first(), session = "ds_dab1"),
            poll("""{"rev":4,"type":"dab","sync":false,"services":[],"why":"Nothing was found on block 5A."}"""))
        assertEquals("Nothing was found on block 5A.", d.problem)
        assertFalse(d.acquiring)
        // whyText wins where the server gives a code and a sentence.
        val coded = DabFeed.apply(DabUi(DabBlocks.ALL.first(), session = "ds_dab1"),
            poll("""{"rev":4,"type":"dab","sync":false,"why":"no_signal","whyText":"No signal on 5A."}"""))
        assertEquals("No signal on 5A.", coded.problem)
    }

    @Test fun everyAreaIsTaggedReceiveOnlyButLpd433InsideSeventyCentimetres() {
        val buttons = all()
        assertEquals(listOf("lpd433"), buttons.filter { it.area != null && !it.rxTag }.map { it.name })
        assertEquals(ListenAreas.ALL.size - 1, buttons.count { it.rxTag })
    }
}
