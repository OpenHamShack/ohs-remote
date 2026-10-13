package com.openhamshack.remote.shack

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Radio's band plan strip and band buttons, against the band-plan data the
 * app ships (assets/bandplan, ohs-libs' files copied as they are) and the
 * memories the Radio simulator answers (radio-memories.json).
 */
class RadioBandsTest {
    private fun asset(path: String) = JSONObject(File("src/main/assets/bandplan/$path").readText())
    private val data by lazy {
        PlanData(PlanVocab.parse(asset("vocab.json")), PlanData.PLAN_IDS.associateWith { BandPlan.parse(asset("plans/$it.json")) })
    }
    private val memories by lazy {
        Bookmark.parseList(JSONObject(javaClass.classLoader!!.getResourceAsStream("shack/radio-memories.json")!!.bufferedReader().readText()))
    }
    private fun band(name: String) = data.vocab.band(name)!!
    private fun button(name: String) = RadioBands.buttons(data.vocab).first { it.name == name }

    // --- the data ------------------------------------------------------------

    @Test fun everyPlanTheAppNamesIsShippedAndHasRows() {
        for (id in PlanData.PLAN_IDS) assertTrue(id, data.plans.getValue(id).segments.size > 50)
        // Every band with a button exists in the dataset, so none silently drops out.
        assertEquals(RadioBands.NAMES, RadioBands.buttons(data.vocab).map { it.name })
    }

    @Test fun digitalIsTheDatasetsClassNotAListHere() {
        assertTrue(data.vocab.isDigital("ft8"))
        assertTrue(data.vocab.isDigital("JS8"))
        assertTrue(data.vocab.isDigital("psk31"))
        assertFalse(data.vocab.isDigital("usb"))
        assertFalse(data.vocab.isDigital("cw"))
        // A mode the data does not know has no class (GUIDE 5): not digital.
        assertFalse(data.vocab.isDigital("kgstv"))
    }

    // --- the strip -----------------------------------------------------------

    @Test fun twentyMetresUnderRsgbReadsCwDataThenPhoneAndABeacon() {
        val strip = data.strip("rsgb-2026", 14_000_000.0, 14_350_000.0)
        assertEquals("cw", strip.first().kind)
        assertEquals(14_000_000L, strip.first().loHz)
        val kinds = strip.map { it.kind }
        assertTrue(kinds.toString(), "data" in kinds)
        assertTrue(kinds.toString(), "beacon" in kinds)
        // The strip never overlaps itself: innermost rows win, their parents shortened.
        strip.zipWithNext().forEach { (a, b) -> assertTrue("$a then $b", a.hiHz <= b.loHz) }
    }

    @Test fun aBandTheNationalPlanSkipsIsFilledFromItsRegion() {
        // RSGB 2026 has no 6 m rows; IARU R1 does. A blank strip there would read as "no plan".
        assertTrue(data.plans.getValue("rsgb-2026").segments.none { it.loHz >= 50_000_000 && it.hiHz <= 52_000_000 })
        assertTrue(data.strip("rsgb-2026", 50_000_000.0, 50_500_000.0).isNotEmpty())
    }

    @Test fun aServerThatNamesNoPlanGetsRegionOne() {
        assertEquals(data.strip("iaru-r1", 7_000_000.0, 7_200_000.0), data.strip(null, 7_000_000.0, 7_200_000.0))
        assertEquals(data.strip("iaru-r1", 7_000_000.0, 7_200_000.0), data.strip("no-such-plan", 7_000_000.0, 7_200_000.0))
    }

    @Test fun twoMetresSatelliteAndBeaconRowsAreCutOutOfTheirParents() {
        val strip = data.strip("iaru-r1", 144_000_000.0, 146_000_000.0)
        assertTrue(strip.any { it.kind == "satellite" })
        assertTrue(strip.any { it.kind == "beacon" })
        strip.zipWithNext().forEach { (a, b) -> assertTrue("$a then $b", a.hiHz <= b.loHz) }
    }

    @Test fun kindsFollowFlexsRules() {
        fun k(vararg m: String, kind: String = "") = RadioPlanStrip.kindOf(PlanSegment(0, 1, "", m.toSet(), kind))
        assertEquals("cw", k("cw"))
        assertEquals("data", k("cw", "digi"))
        assertEquals("all", k("cw", "digi", "ssb", "am"))
        assertEquals("phone", k("ssb", "am"))
        assertEquals("fm", k("fm", "dv"))
        assertEquals("image", k("image"))
        assertEquals("all", k())
        assertEquals("beacon", k("cw", kind = "beacon"))
        assertEquals("repeater", k("fm", kind = "output"))
    }

    @Test fun layoutKeepsANarrowSegmentVisibleAndLabelsOnlyWhereThereIsRoom() {
        val segs = listOf(PlanStripSegment(0, 990, "cw", 0, ""), PlanStripSegment(990, 991, "beacon", 0, ""))
        val p = RadioPlanStrip.layout(segs, 0.0, 1000.0, 1000f, 100f)
        assertEquals(2, p.size)
        assertTrue(p[0].label)
        assertEquals(1f, p[1].w, 0f)
        assertFalse(p[1].label)
        assertTrue(RadioPlanStrip.layout(segs, 2000.0, 3000.0, 1000f, 100f).isEmpty())
    }

    // --- the buttons ---------------------------------------------------------

    @Test fun buttonsAreLabelledAsARadiosBandKeys() {
        assertEquals(listOf("160", "80", "60", "40", "30", "20", "17", "15", "12", "10", "6", "4", "2", "70cm"),
            RadioBands.buttons(data.vocab).map { it.label })
    }

    @Test fun aReceiverThatSaysWhatItCoversLosesTheBandsItCannotReach() {
        // An HF-and-6 m receiver: no 4 m, 2 m or 70 cm.
        val hf = RadioBands.buttons(data.vocab, 1_000.0, 60_000_000.0).map { it.name }
        assertEquals(RadioBands.NAMES.take(11), hf)
        // A receiver that starts at 70 MHz (a Pluto): 4 m upward only.
        assertEquals(listOf("4m", "2m", "70cm"), RadioBands.buttons(data.vocab, 70_000_000.0, 6e9).map { it.name })
    }

    @Test fun theServersBandNameLightsTheButtonElseTheDial() {
        val b = RadioBands.buttons(data.vocab)
        assertEquals("20m", RadioBands.current(b, "20m", null)?.name)
        assertEquals("40m", RadioBands.current(b, "", 7_074_000.0)?.name)
        assertNull(RadioBands.current(b, "", 5_000_000.0))
    }

    @Test fun channelKeysWalkTheButtonsAndStopAtTheEnds() {
        val b = RadioBands.buttons(data.vocab)
        assertEquals("17m", RadioBands.step(b, "20m", 14_074_000.0, 1)?.name)
        assertEquals("30m", RadioBands.step(b, "20m", 14_074_000.0, -1)?.name)
        assertNull(RadioBands.step(b, "160m", 1_840_000.0, -1))
        assertNull(RadioBands.step(b, "70cm", 432_200_000.0, 1))
        // Between bands (a broadcast station at 6 MHz): the next band each way.
        assertEquals("40m", RadioBands.step(b, "", 6_000_000.0, 1)?.name)
        assertEquals("60m", RadioBands.step(b, "", 6_000_000.0, -1)?.name)
    }

    @Test fun aFirstVisitInADigitalModeGoesToTheFt8Dial() {
        val spot = RadioBands.target(band("20m"), null, "ft8", memories, data, "rsgb-2026")
        assertEquals(BandSpot(14_074_000.0, "ft8"), spot)
        // In another digital mode with no memory of its own there, still FT8's dial, in FT8.
        assertEquals(BandSpot(7_074_000.0, "ft8"), RadioBands.target(band("40m"), null, "psk31", memories, data, "rsgb-2026"))
    }

    @Test fun aFirstVisitOtherwiseGoesToTheSsbSegmentInTheConventionalSideband() {
        fun at(name: String, plan: String = "rsgb-2026") = RadioBands.target(band(name), null, "usb", memories, data, plan)
        // LSB 3 kHz inside the segment, so the whole signal is in it; USB on its start.
        assertEquals(BandSpot(3_603_000.0, "lsb"), at("80m"))
        assertEquals(BandSpot(7_053_000.0, "lsb"), at("40m"))
        assertEquals(BandSpot(14_125_000.0, "usb"), at("20m"))
        assertEquals(BandSpot(21_151_000.0, "usb"), at("15m"))
        assertEquals(BandSpot(28_225_000.0, "usb"), at("10m"))
        assertEquals(BandSpot(144_150_000.0, "usb"), at("2m"))
        assertEquals(BandSpot(432_000_000.0, "usb"), at("70cm"))
        // 60 m is USB everywhere, below 10 MHz or not.
        assertEquals("usb", at("60m").mode)
        // IARU R1: the widest SSB row, not 2 m's satellite downlinks nor 160 m's three-kHz window beside FT8.
        assertEquals(144_150_000.0, at("2m", "iaru-r1").hz, 0.0)
        assertEquals(1_846_000.0, at("160m", "iaru-r1").hz, 0.0)
        // 6 m from RSGB, which has no rows there: IARU R1's.
        assertEquals(BandSpot(50_200_000.0, "usb"), at("6m"))
    }

    @Test fun aBandWithNoSsbGoesToFt8() {
        assertEquals(BandSpot(10_136_000.0, "ft8"), RadioBands.target(band("30m"), null, "usb", memories, data, "rsgb-2026"))
        // With no FT8 memory from the server either: its CW segment, in CW.
        val cw = RadioBands.target(band("30m"), null, "usb", emptyList(), data, "rsgb-2026")
        assertEquals("cw", cw.mode)
        assertTrue(cw.hz in band("30m"))
    }

    @Test fun inFmTwoMetresAndSeventyCentimetresStayOnTheFmCallingChannel() {
        assertEquals(BandSpot(145_500_000.0, "fm"), RadioBands.target(band("2m"), null, "fm", memories, data, "rsgb-2026"))
        assertEquals(BandSpot(433_500_000.0, "fm"), RadioBands.target(band("70cm"), null, "fm", memories, data, "iaru-r1"))
        // Region 2 names its own: 146.520.
        assertEquals(BandSpot(146_520_000.0, "fm"), RadioBands.target(band("2m"), null, "fm", memories, data, "iaru-r2"))
        // Not in FM: SSB as usual.
        assertEquals("usb", RadioBands.target(band("2m"), null, "usb", memories, data, "rsgb-2026").mode)
        // An HF band in FM with no calling channel takes the SSB rule.
        assertEquals(BandSpot(14_125_000.0, "usb"), RadioBands.target(band("20m"), null, "fm", memories, data, "rsgb-2026"))
    }

    @Test fun ft4FromFt4WhereTheServerHasItsDialElseFt8() {
        val withFt4 = memories + Bookmark("FT4 20m", 14_080_000.0, "ft4", true)
        assertEquals(BandSpot(14_080_000.0, "ft4"), RadioBands.target(band("20m"), null, "ft4", withFt4, data, "rsgb-2026"))
        assertEquals(BandSpot(14_074_000.0, "ft8"), RadioBands.target(band("20m"), null, "ft4", memories, data, "rsgb-2026"))
    }

    @Test fun theSameBandPressedAgainStepsFt8SsbCw() {
        val b = band("20m")
        fun next(mode: String) = RadioBands.next(b, mode, memories, data, "rsgb-2026")!!
        assertEquals(BandSpot(14_125_000.0, "usb"), next("ft8"))
        assertEquals("cw", next("usb").mode)
        assertTrue(next("usb").hz in 14_000_000.0..14_070_000.0)
        assertEquals(BandSpot(14_074_000.0, "ft8"), next("cw"))
        // From anything else (FM, AM), FT8 first.
        assertEquals(BandSpot(14_074_000.0, "ft8"), next("am"))
        // 30 m has no SSB: FT8, then CW, then FT8.
        assertEquals("cw", RadioBands.next(band("30m"), "ft8", memories, data, "rsgb-2026")!!.mode)
        assertEquals("ft8", RadioBands.next(band("30m"), "cw", memories, data, "rsgb-2026")!!.mode)
    }

    @Test fun aRememberedSpotWinsOverEveryDefault() {
        val kept = BandSpot(14_230_000.0, "usb")
        assertEquals(kept, RadioBands.target(band("20m"), kept, "ft8", memories, data, "rsgb-2026"))
        // A remembered spot that is not in the band (a corrupted preference) is ignored.
        assertEquals(BandSpot(14_074_000.0, "ft8"),
            RadioBands.target(band("20m"), BandSpot(7_100_000.0, "lsb"), "ft8", memories, data, "rsgb-2026"))
    }

    @Test fun theDialIsRememberedAgainstItsBandAndNothingBetweenBands() {
        var spots = RadioBands.remember(emptyMap(), data.vocab, 14_074_000.0, "ft8")
        spots = RadioBands.remember(spots, data.vocab, 7_150_000.0, "lsb")
        spots = RadioBands.remember(spots, data.vocab, 6_000_000.0, "am")       // broadcast: not a band
        assertEquals(mapOf("20m" to BandSpot(14_074_000.0, "ft8"), "40m" to BandSpot(7_150_000.0, "lsb")), spots)
        // The same spot again is no change (nothing to write).
        assertTrue(RadioBands.remember(spots, data.vocab, 7_150_000.0, "lsb") === spots)
    }

    @Test fun spotsSurviveTheirPreferenceValueAndJunkIsDropped() {
        val spots = mapOf("20m" to BandSpot(14_074_000.0, "ft8"), "2m" to BandSpot(144_300_000.0, "usb"))
        assertEquals(spots, RadioBands.decode(RadioBands.encode(spots)))
        assertEquals(emptyMap<String, BandSpot>(), RadioBands.decode("not json"))
        assertEquals(emptyMap<String, BandSpot>(), RadioBands.decode(null))
        assertEquals(mapOf("20m" to BandSpot(14_074_000.0, "ft8")),
            RadioBands.decode("""{"20m":{"hz":14074000,"mode":"ft8"},"40m":{"hz":-1,"mode":"lsb"},"17m":{"mode":"usb"}}"""))
    }

    @Test fun statusCarriesTheReceiversRangeOnlyWhenItMakesSense() {
        fun st(extra: String) = EngineStatus.parse(JSONObject("""{"kind":"radio"$extra}"""))
        assertEquals(1_000.0 to 2e9, st(""","rxRange":[1000,2000000000]""").rxRange)
        assertNull(st("").rxRange)
        assertNull(st(""","rxRange":[2000000000,1000]""").rxRange)
        assertNull(st(""","rxRange":[1000]""").rxRange)
        assertNotNull(button("20m"))
    }
}
