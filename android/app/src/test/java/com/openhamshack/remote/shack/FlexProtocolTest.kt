package com.openhamshack.remote.shack

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Flex's state and frames as the real module sends them: the fixtures in
 * resources/shack/flex were captured through tools/fakeshack.py from the
 * Flex module (dock360-apps flex line, a95e508) running in platform mode on
 * its own fakeflex.py, after a TV token had tuned slice 0 to 14.201 MHz.
 */
class FlexProtocolTest {
    private fun bytes(name: String) = javaClass.classLoader!!.getResourceAsStream("shack/flex/$name")!!.readBytes()
    private fun json(name: String) = JSONObject(String(bytes(name)))

    @Test fun stateGivesTheWorkedSliceItsPanadapterAndTheTables() {
        val o = json("flex-state.json")
        val st = FlexState.parse(o.getJSONObject("state"))
        assertTrue(st.connected)
        assertNull(st.error)
        val s = st.worked!!
        assertEquals(0, s.index)
        assertEquals(14_201_000L, s.hz)
        assertEquals("USB", s.mode)
        assertEquals("0x40000000", s.pan)
        val pan = st.pan!!
        assertEquals(14_100_000.0, pan.lowHz, 0.5)
        assertEquals(14_300_000.0, pan.highHz, 0.5)
        assertEquals(-130.0, pan.minDbm, 0.0)
        assertFalse(st.onAir || st.transmitting)
        assertTrue(st.followReceiver)

        val t = FlexTables.parse(o.getJSONObject("tables"))
        assertEquals("20", t.band(14_201_000L)?.name)
        // GEN and WWV are not bands to step through.
        assertTrue(t.bands.none { it.name == "GEN" || it.name == "WWV" })
        assertEquals(listOf(1, 10, 50, 100, 500, 1000, 2000, 3000), t.mode("USB")!!.steps)
        assertEquals(100, t.mode("USB")!!.defaultStep)
    }

    @Test fun theWorkedSliceIsTheActiveOneOnlyWhenItIsOurs() {
        fun slice(i: Int) = JSONObject().put("index", i).put("frequency", 7.1 + i).put("mode", "LSB").put("pan", 0x40000000L)
        val o = JSONObject().put("connected", true)
            .put("slices", JSONObject().put("1", slice(1)).put("3", slice(3)))
            .put("panadapters", JSONObject())
        // The radio's active flag on a slice SmartSDR owns (not in our list): the lowest of ours.
        assertEquals(1, FlexState.parse(JSONObject(o.toString()).put("active_slice", 0)).worked!!.index)
        assertEquals(3, FlexState.parse(JSONObject(o.toString()).put("active_slice", 3)).worked!!.index)
        assertNull(FlexState.parse(JSONObject().put("connected", false)).worked)
    }

    @Test fun aKeyedRadioShowsWhoeverKeyedIt() {
        val st = FlexState.parse(JSONObject().put("interlock", JSONObject().put("state", "TRANSMITTING")))
        assertTrue(st.transmitting)
        assertFalse(st.onAir)
    }

    @Test fun sweepFrameIsDbmPerBinOnItsPanadapter() {
        val f = FlexFrame.parse(bytes("flex-frame-1.bin")) as FlexFrame.Sweep
        assertEquals("0x40000000", f.pan)
        assertEquals(14_100_000.0, f.lowHz, 0.5)
        assertEquals(14_300_000.0, f.highHz, 0.5)
        assertEquals(1024, f.dbm.size)
        // Inside the panadapter's -130..-40 window, as the module converted it.
        assertTrue(f.dbm.all { it in -131f..-39f })
    }

    @Test fun waterfallRowIsTheRadiosIntensityByFrequency() {
        val f = FlexFrame.parse(bytes("flex-frame-2.bin")) as FlexFrame.WaterfallRow
        assertEquals("0x40000000", f.pan)
        assertEquals(1024, f.intensity.size)
        assertTrue(f.binHz > 0)
        assertTrue(f.lowHz > 14_000_000 && f.lowHz < 14_300_000)
    }

    @Test fun audioIsStereoPcmFoldedToMono() {
        val f = FlexFrame.parse(bytes("flex-frame-3.bin")) as FlexFrame.Audio
        assertEquals(2, f.channels)
        assertEquals(24000, f.rate)
        assertEquals(240, f.mono().size)
        // Each mono sample is the two channels' mean.
        val pcm = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort(100).putShort(300).putShort(-50).putShort(-150).array()
        assertArrayEquals(shortArrayOf(200, -100), FlexFrame.Audio(2, 24000, pcm).mono())
    }

    @Test fun levelsAreDbmBySlice() {
        val f = FlexFrame.parse(bytes("flex-frame-4.bin")) as FlexFrame.Levels
        assertNotNull(f.dbm[0])
        assertTrue(f.dbm[0]!! in -140f..0f)
    }

    @Test fun shortOrUnknownFramesAreNotGuessed() {
        assertNull(FlexFrame.parse(ByteArray(0)))
        assertNull(FlexFrame.parse(byteArrayOf(9, 0, 0, 0)))
        assertNull(FlexFrame.parse(bytes("flex-frame-1.bin").copyOf(100)))
        assertNull(FlexFrame.parse(byteArrayOf(4, 3, 0, 0, 0, 0, 0, 0)))
    }

    // --- tuning ----------------------------------------------------------

    @Test fun tuningLandsOnTheStepsGrid() {
        assertEquals(14_202_000L, FlexTuning.tune(14_201_300L, 1000, +1))
        assertEquals(14_201_000L, FlexTuning.tune(14_201_300L, 1000, -1))
        assertEquals(14_203_000L, FlexTuning.tune(14_201_000L, 1000, +2))
        assertEquals(14_200_900L, FlexTuning.tune(14_201_000L, 100, -1))
        assertEquals(14_201_000L, FlexTuning.tune(14_201_000L, 100, 0))
    }

    @Test fun aSwipeMovesTheDialTheWayTheFingerGoes() {
        // 200 kHz across 2000 px: 100 Hz a pixel.
        // Right by a quarter of the width is 50 kHz up; left, 50 kHz down.
        assertEquals(7_150_000L, FlexTuning.dragged(7_100_000L, 500f, 2000, 200_000L, 100))
        assertEquals(7_050_000L, FlexTuning.dragged(7_100_000L, -500f, 2000, 200_000L, 100))
        // Onto the step's grid: 3.37 px right is 337 Hz, which a 100 Hz step lands at 300 above.
        assertEquals(7_100_300L, FlexTuning.dragged(7_100_000L, 3.37f, 2000, 200_000L, 100))
        // Less than half a step does nothing.
        assertEquals(7_100_000L, FlexTuning.dragged(7_100_000L, 0.4f, 2000, 200_000L, 100))
        // A scope not yet laid out, or with no span reported, leaves the dial alone.
        assertEquals(7_100_000L, FlexTuning.dragged(7_100_000L, -500f, 0, 200_000L, 100))
        assertEquals(7_100_000L, FlexTuning.dragged(7_100_000L, -500f, 2000, 0L, 100))
    }

    @Test fun aSlowSwipeTunesFinelyAndAFastOneCrossesTheBand() {
        // Slow and still: a tenth of the movement counts, either way.
        assertEquals(FlexTuning.FINE_GAIN, FlexTuning.dragGain(0f), 0f)
        assertEquals(FlexTuning.FINE_GAIN, FlexTuning.dragGain(-40f), 0f)
        // Quick: all of it, and no more however fast.
        assertEquals(1f, FlexTuning.dragGain(900f), 0f)
        assertEquals(1f, FlexTuning.dragGain(-5000f), 0f)
        // Between, a straight line: halfway in speed is halfway in gain.
        assertEquals(0.55f, FlexTuning.dragGain(480f), 0.001f)
        // 200 kHz over 2000 px, dragged slowly 140 px: 14 px counts, 1.4 kHz.
        val slow = 140f * FlexTuning.dragGain(30f)
        assertEquals(7_101_400L, FlexTuning.dragged(7_100_000L, slow, 2000, 200_000L, 100))
    }

    @Test fun stepsFollowTheModesLadder() {
        val usb = FlexMode("USB", "USB", listOf(10, 100, 1000), 100)
        assertEquals(1000, FlexTuning.nextStep(FlexTuning.steps(usb), 100, +1))
        assertEquals(1000, FlexTuning.nextStep(FlexTuning.steps(usb), 1000, +1))
        assertEquals(10, FlexTuning.nextStep(FlexTuning.steps(usb), 100, -1))
        // Off the ladder (a step from another mode): from the nearest rung.
        assertEquals(1000, FlexTuning.nextStep(FlexTuning.steps(usb), 500, +1))
        assertEquals(FlexTuning.FALLBACK_STEPS, FlexTuning.steps(FlexMode("X", "X", emptyList(), 100)))
    }

    @Test fun theReceiversRangeIsTheModulesGenRow() {
        // The captured table's GEN row: 30 kHz to 54 MHz.
        val t = FlexTables.parse(json("flex-state.json").getJSONObject("tables"))
        assertEquals(FlexCoverage(30_000, 54_000_000), t.coverage)
        // A module whose radio hears more says so, and that wins.
        fun gen(low: Any?, high: Any?) = JSONObject().put("bands", org.json.JSONArray().put(
            JSONObject().put("name", "GEN").put("amateur", false).put("low", low).put("high", high)))
        assertEquals(FlexCoverage(10_000, 77_000_000), FlexTables.parse(gen(10_000, 77_000_000)).coverage)
        // No row, or one that makes no sense: FlexRadio's documented 30 kHz - 54 MHz.
        assertEquals(FlexCoverage.FLEX_6000, FlexTables.parse(JSONObject().put("bands", org.json.JSONArray())).coverage)
        assertEquals(FlexCoverage.FLEX_6000, FlexTables.parse(gen(-5_000, 54_000_000)).coverage)
        assertEquals(FlexCoverage.FLEX_6000, FlexTables.parse(gen(54_000_000, 30_000)).coverage)
        assertEquals(FlexCoverage.FLEX_6000, FlexTables.parse(gen(null, null)).coverage)
        assertEquals(FlexCoverage.FLEX_6000, FlexTables().coverage)
    }

    @Test fun tuningStopsAtTheBottomOfTheReceiverInsteadOfGoingNegative() {
        val c = FlexCoverage.FLEX_6000
        // Brian's crash: 100 Hz steps down from just above the floor.
        assertEquals(FlexTuning.Limit(30_000, null, send = true), FlexTuning.limit(30_100, FlexTuning.tune(30_100, 100, -1), c))
        // At the floor: stays, says so, and sends nothing more however long Left is held.
        val held = FlexTuning.limit(30_000, FlexTuning.tune(30_000, 100, -1), c)
        assertEquals(FlexTuning.Limit(30_000, FlexTuning.Edge.FLOOR, send = false), held)
        // A big step from 0.5 MHz lands on the floor, not on -0.5 MHz, and is sent once.
        assertEquals(FlexTuning.Limit(30_000, FlexTuning.Edge.FLOOR, send = true),
            FlexTuning.limit(500_000, FlexTuning.tune(500_000, 1_000_000, -1), c))
        // Nor does it wrap to the top.
        assertTrue(FlexTuning.limit(30_000, -1_000_000_000L, c).hz == 30_000L)
    }

    @Test fun tuningStopsAtTheTopOfTheReceiverToo() {
        val c = FlexCoverage.FLEX_6000
        assertEquals(FlexTuning.Limit(54_000_000, FlexTuning.Edge.CEILING, send = true),
            FlexTuning.limit(53_999_000, FlexTuning.tune(53_999_000, 1000, +2), c))
        assertEquals(FlexTuning.Limit(54_000_000, FlexTuning.Edge.CEILING, send = false),
            FlexTuning.limit(54_000_000, 54_000_100, c))
        // Inside the range nothing is said.
        assertEquals(FlexTuning.Limit(14_200_100, null, send = true), FlexTuning.limit(14_200_000, 14_200_100, c))
    }

    @Test fun aDialAlreadyOutsideTheRangeComesBackInEitherWay() {
        // fakeflex took -100 Hz before the clamp existed; a real radio might be
        // left anywhere by another program. Any press brings it to the edge.
        val c = FlexCoverage.FLEX_6000
        assertEquals(FlexTuning.Limit(30_000, FlexTuning.Edge.FLOOR, send = true), FlexTuning.limit(-100, -200, c))
        assertEquals(FlexTuning.Limit(30_000, FlexTuning.Edge.FLOOR, send = true), FlexTuning.limit(-100, 0, c))
    }

    @Test fun theDisplayFollowsOnlyNearItsEdges() {
        val pan = FlexPan("0x40000000", 14_100_000.0, 14_300_000.0, -130.0, -40.0)
        assertNull(FlexTuning.recentre(14_200_000L, pan))
        assertNull(FlexTuning.recentre(14_125_000L, pan))
        assertEquals(14_120_000.0, FlexTuning.recentre(14_120_000L, pan)!!, 0.0)
        assertEquals(14_290_000.0, FlexTuning.recentre(14_290_000L, pan)!!, 0.0)
        assertNull(FlexTuning.recentre(14_120_000L, null))
    }

    // --- the display -------------------------------------------------------

    @Test fun waterfallRowsArePlacedByFrequencyNotStretched() {
        // A tile covering only the upper half of the panadapter.
        val row = FlexFrame.WaterfallRow("p", 200.0, 10.0, FloatArray(10) { if (it == 5) 120f else 90f })
        val cols = FlexScope.resample(row, 100.0, 300.0, 20)
        for (c in 0 until 10) assertTrue("column $c is off the tile", cols[c].isNaN())
        // 250 Hz is bin 5: column 15 of 20.
        assertEquals(120f, cols[15], 0f)
        assertEquals(90f, cols[12], 0f)
    }

    @Test fun aRetunedDisplayStartsItsWaterfallAgain() {
        val sc = FlexScope(width = 4, height = 3)
        sc.sweep(FlexFrame.Sweep("p", 100.0, 500.0, floatArrayOf(-120f, -100f, -120f, -120f)))
        sc.row(FlexFrame.WaterfallRow("p", 100.0, 100.0, floatArrayOf(90f, 120f, 90f, 90f)))
        assertTrue(sc.pixels.take(4).any { it != Scope.BACKGROUND })
        sc.sweep(FlexFrame.Sweep("p", 1100.0, 1500.0, floatArrayOf(-120f, -100f, -120f, -120f)))
        assertTrue(sc.pixels.all { it == Scope.BACKGROUND })
        assertEquals(0.25f, sc.xOf(1200.0)!!, 0.001f)
    }
}
