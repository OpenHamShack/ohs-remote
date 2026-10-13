package com.openhamshack.kind.stationflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.openhamshack.kind.stationflow.api.Band

class TuningTest {
    @Test fun `on the grid a press is one step`() {
        assertEquals(145_525_000L, stepFrequency(145_500_000, 25_000, +1))
        assertEquals(145_475_000L, stepFrequency(145_500_000, 25_000, -1))
        assertEquals(144_174_010L, stepFrequency(144_174_000, 10, +1))
    }

    @Test fun `off the grid the first press lands on the next grid line that way`() {
        assertEquals(145_525_000L, stepFrequency(145_512_000, 25_000, +1))
        assertEquals(145_500_000L, stepFrequency(145_512_000, 25_000, -1))
        assertEquals(145_475_000L, stepFrequency(145_512_000, 25_000, -2))
        assertEquals(144_175_000L, stepFrequency(144_174_321, 1_000, +1))
    }

    @Test fun `steps cycle and suit the mode`() {
        assertEquals(100L, nextStep(10))
        assertEquals(10L, nextStep(100_000))
        assertEquals(10L, nextStep(7))   // not a step: start again
        assertEquals("rewind/FF stop at the ends", 100_000L, shiftStep(100_000, +1))
        assertEquals(10L, shiftStep(10, -1))
        assertEquals(1_000L, shiftStep(100, +1))
        assertEquals("off the ladder: the next one that way", 5_000L, shiftStep(2_500, +1))
        assertEquals(1_000L, shiftStep(2_500, -1))
        assertEquals(12_500L, defaultStep("FM"))
        assertEquals(12_500L, defaultStep("FM-D"))
        assertEquals(100L, defaultStep("USB-D"))
    }

    @Test fun `quick modes are the radio's, DATA only where offered`() {
        val modes = listOf("LSB", "USB", "AM", "CW", "RTTY", "FM", "CWR", "RTTYR", "DV", "DD")
        assertEquals(listOf("FM", "USB", "LSB", "CW", "AM", "USB-D", "FM-D", "DV"), quickModes(modes, listOf("LSB", "USB", "AM", "FM")))
        assertEquals(listOf("USB", "LSB", "CW"), quickModes(listOf("USB", "LSB", "CW"), emptyList()))
        assertEquals("USB", cycle(listOf("FM", "USB", "LSB"), "FM"))
        assertEquals("FM", cycle(listOf("FM", "USB", "LSB"), "LSB"))
        assertEquals("LSB", cycle(listOf("FM", "USB", "LSB"), "FM", -1))
        assertEquals("FM", cycle(listOf("FM", "USB"), "RTTY"))
        assertNull(cycle(emptyList<String>(), "x"))
    }

    @Test fun `the band is the nearest band button`() {
        val bands = listOf(Band("2m", 144_300_000), Band("70cm", 432_200_000), Band("23cm", 1_296_200_000))
        assertEquals("2m", bandOf(bands, 145_500_000)!!.name)
        assertEquals("70cm", bandOf(bands, 438_000_000)!!.name)
        assertNull(bandOf(bands, null))
    }

    @Test fun `S-units from dB over S9`() {
        assertEquals("S9", sUnits(0.0))
        assertEquals("S9+20", sUnits(20.0))
        assertEquals("S7", sUnits(-12.0))
        assertEquals("S0", sUnits(-60.0))
        assertEquals("S–", sUnits(null))
    }

    @Test fun `frequencies read as a radio shows them`() {
        assertEquals("145.500.000", formatHz(145_500_000))
        assertEquals("1296.200.000", formatHz(1_296_200_000))
        assertEquals("---.---.---", formatHz(null))
        assertEquals("12.5 kHz", formatStep(12_500))
        assertEquals("10 Hz", formatStep(10))
        assertEquals("100 kHz", formatStep(100_000))
    }

    @Test fun `typed frequencies in MHz, kHz or Hz`() {
        assertEquals(145_500_000L, parseTypedFrequency("145.5"))
        assertEquals(145_500_000L, parseTypedFrequency("145,5"))
        assertEquals(1_296_200_000L, parseTypedFrequency("1296.2"))
        assertEquals(145_500_000L, parseTypedFrequency("145500"))
        assertEquals(145_500_000L, parseTypedFrequency("145500000"))
        assertNull(parseTypedFrequency("abc"))
        assertNull(parseTypedFrequency("-3"))
    }

    @Test fun `the waterfall keeps the newest sweep first, and restarts on a new span`() {
        val w = Waterfall(rows = 3)
        fun sweep(level: Int, start: Long = 0, end: Long = 100) =
            com.openhamshack.kind.stationflow.api.Spectrum(start, end, 160, IntArray(4) { level })
        w.add(sweep(0)); w.add(sweep(160))
        val out = IntArray(4 * 3)
        assertEquals(2, w.copyInto(out))
        assertEquals(Waterfall.colour(1f), out[0])          // newest on top
        assertEquals(Waterfall.colour(0f), out[4])
        w.add(sweep(160, 0, 200))                            // another span
        assertEquals(1, w.copyInto(out))
    }

    @Test fun `the waterfall moves with the dial, and forgets on a jump`() {
        val w = Waterfall(rows = 2)
        fun sweep(start: Long, peakAt: Int) =
            com.openhamshack.kind.stationflow.api.Spectrum(start, start + 400, 160, IntArray(5) { if (it == peakAt) 160 else 0 })
        w.add(sweep(1000, 3))                     // a signal at 1300 Hz
        w.add(sweep(1100, 2))                     // tuned up 100 Hz: it is now at point 2
        val out = IntArray(5 * 2)
        assertEquals(2, w.copyInto(out))
        val hot = Waterfall.colour(1f)
        assertEquals(hot, out[2])                 // newest row
        assertEquals(hot, out[5 + 2])             // older row, shifted to match
        w.add(sweep(432_000_000, 0))              // a band change: history gone
        assertEquals(1, w.copyInto(out))
    }
}
