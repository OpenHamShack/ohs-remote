package com.openhamshack.remote.shack

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenHamShack DATV's wire side against answers recorded on 9 October 2026
 * from `python3 -m ohsdatv --server` (openhamshack-datv main 2d33c18, the
 * simulator) through tools/fakeshack.py, and the tune rule the engine
 * forces on a client: the band goes with the frequency.
 */
class DatvTest {
    private fun json(name: String) = JSONObject(javaClass.classLoader!!.getResourceAsStream("shack/$name")!!.bufferedReader().readText())
    private val bands by lazy { DatvBands.parse(json("datv-bands.json")) }

    @Test fun statusOfTheSimulator() {
        val st = DatvStatus.parse(json("datv-status.json"))
        assertTrue(st.running)
        assertTrue(st.receiveOnly)
        assertTrue(st.locked)
        assertEquals("locked DVB-S2", st.lock)
        assertEquals("70cm", st.band)
        assertEquals(437_000_000.0, st.rxFreqHz!!, 0.0)
        assertEquals(333_000, st.rxSr)
        assertTrue("the simulator says so", st.simulator)
        assertEquals("SIM", st.service)
    }

    @Test fun unknownNumbersStayUnknown() {
        val st = DatvStatus.parse(JSONObject("""{"running": false, "rxFreq": null, "mer": null, "locked": "searching", "service": null}"""))
        assertNull(st.rxFreqHz)
        assertNull(st.mer)
        assertFalse(st.locked)
        assertEquals("", st.service)
        // No receiveOnly at all: not a server this build should treat as receive only.
        assertFalse(st.receiveOnly)
    }

    @Test fun bandsAndChannels() {
        assertEquals(listOf("2m", "70cm", "23cm", "13cm", "9cm", "6cm", "3cm"), bands.map { it.name })
        val b23 = bands.single { it.name == "23cm" }
        assertEquals(1_255_000_000.0, b23.defaultHz, 0.0)
        assertTrue(1_249_000_000.0 in b23)
        assertEquals("Repeater input (original)", b23.channels.single { it.freqHz == 1_249_000_000.0 }.label)
    }

    @Test fun aTuneNamesTheBandThatHoldsTheFrequency() {
        // The engine refused {"rxFreq": 1249000000} alone while on 70cm:
        // "1249.000 MHz is outside 70cm (430-440 MHz)".
        val t = datvTune(bands, 1_249_000_000.0, 333_000)!!
        assertEquals("23cm", t.getString("band"))
        assertEquals(1_249_000_000.0, t.getDouble("rxFreq"), 0.0)
        assertEquals(333_000, t.getInt("rxSr"))
        assertFalse("no rate given, none sent", datvTune(bands, 437_000_000.0)!!.has("rxSr"))
    }

    @Test fun aFrequencyInNoBandIsRefusedBeforeAsking() {
        assertNull(datvTune(bands, 50_000_000.0))
        assertNull(datvTune(emptyList(), 437_000_000.0))
    }

    @Test fun theChannelTheDialIsOn() {
        assertEquals("DATV centre of activity", datvChannelAt(bands, 437_000_000.0)?.label)
        assertEquals("DATV centre of activity", datvChannelAt(bands, 437_000_400.0)?.label)
        assertNull(datvChannelAt(bands, 437_100_000.0))
        assertNull(datvChannelAt(bands, null))
    }
}
