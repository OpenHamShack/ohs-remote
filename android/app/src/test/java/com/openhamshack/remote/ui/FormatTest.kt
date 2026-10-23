package com.openhamshack.remote.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {
    @Test
    fun clockIsTheConsoles() {
        assertEquals("0:00", clock(0))
        assertEquals("1:05", clock(65))
        assertEquals("1:00:01", clock(3601))
        assertEquals("0:00", clock(-3))
    }

    @Test
    fun aDownlinkShowsInMhzWithTheLocalesDecimalSeparatorAndNoGrouping() {
        assertEquals("10491.500\u00a0MHz", downlinkText(10491.5, "en-GB"))
        assertEquals("10491,500\u00a0MHz", downlinkText(10491.5, "de-DE"))
        assertEquals("10489,750\u00a0MHz", downlinkText(10489.75, "fr"))        // France groups numbers; never a frequency
        assertEquals("10494.763\u00a0MHz", downlinkText(10494.7625, "en"))     // to the kHz, half away from zero
    }

    @Test
    fun aNegativeDialReadsAsNoFrequencyRatherThanEndingTheApp() {
        // What crashed Brian's Fire TV: Flex's radio reported -100 Hz and the
        // readout threw while drawing.
        assertEquals("—", dialText(-100.0, "en-GB"))
        assertEquals("—", dialText(Double.NaN, "en-GB"))
        assertEquals("—", dialText(null, "en-GB"))
        assertEquals("30.00\u00a0kHz", dialText(30_000.0, "en-GB"))
        assertEquals("7100.00\u00a0kHz", dialText(7_100_000.0, "en-GB"))
        assertEquals("50.31300\u00a0MHz", dialText(50_313_000.0, "en-GB"))
    }

    @Test
    fun theReadoutSetsTheNumberAndItsUnitApart() {
        assertEquals("118.70000" to "MHz", dialParts(118_700_000.0, "en-GB"))
        assertEquals("118,70000" to "MHz", dialParts(118_700_000.0, "de-DE"))
        assertEquals("909.00" to "kHz", dialParts(909_000.0, "en-GB"))
        assertEquals("—" to "", dialParts(null, "en-GB"))
        assertEquals("118.650", edgeText(118_650_000.0, "en-GB"))
        assertEquals("7100", edgeText(7_100_000.0, "en-GB"))
        // The band picker's reach, short: an RSPdx's 1 kHz to 2 GHz.
        assertEquals("1\u00a0kHz", reachEnd(1_000.0, "en-GB"))
        assertEquals("2\u00a0GHz", reachEnd(2e9, "en-GB"))
        assertEquals("6.1\u00a0GHz", reachEnd(6.1e9, "en-GB"))
    }

    @Test
    fun stepsReadAsARadioNamesThem() {
        // The airband step is 25 kHz / 3; every airband radio calls it 8.33 kHz.
        assertEquals("8.33\u00a0kHz", stepText(8_333, "en-GB"))
        assertEquals("12.5\u00a0kHz", stepText(12_500, "en-GB"))
        assertEquals("9\u00a0kHz", stepText(9_000, "en-GB"))
        assertEquals("200\u00a0kHz", stepText(200_000, "en-GB"))
        assertEquals("10\u00a0Hz", stepText(10, "en-GB"))
    }

    @Test
    fun aTypedFrequencyPicksTheLnbAsTheConsoleDoes() {
        assertEquals(9750.0, rxOffsetFor(10491.5, 0.0, 9750.0), 0.0)        // QO-100 from terrestrial
        assertEquals(9739.0, rxOffsetFor(10491.5, 9739.0, 9750.0), 0.0)     // keeps a receiver's own LNB
        assertEquals(0.0, rxOffsetFor(437.0, 9750.0, 9750.0), 0.0)          // back to terrestrial
        assertEquals(0.0, rxOffsetFor(1255.0, null, 9750.0), 0.0)
    }

    @Test
    fun symbolRatesFollowTheBand() {
        assertEquals(SR_QO100, srRatesFor(9750.0))
        assertEquals(SR_TERRESTRIAL, srRatesFor(0.0))
        assertEquals(SR_TERRESTRIAL, srRatesFor(null))
    }

    @Test
    fun numbersAreNeverLocalised() {
        assertEquals("437.000", mhz(437.0))
        assertEquals("—", mhz(null))
        assertEquals("333", ks(333.0))
        assertEquals("9.8", db(9.75))
    }

    @Test
    fun aTypedFrequencyCarriesARateThatBelongsToItsBand() {
        // Onto the beacon: 1500, whatever was set.
        assertEquals(1500, rateForFrequency(10491.5, 9750.0, 333.0))
        assertNull(rateForFrequency(10491.5, 9750.0, 1500.0))
        // Onto the transponder from a terrestrial-only rate: the nearest QO-100 one.
        assertEquals(1500, rateForFrequency(10494.75, 9750.0, 2000.0))
        // A rate the band has is left alone.
        assertNull(rateForFrequency(10494.75, 9750.0, 333.0))
        // Off the LNB from a QO-100-only rate: the nearest terrestrial one.
        assertEquals(125, rateForFrequency(437.0, 0.0, 66.0))
        assertNull(rateForFrequency(437.0, 0.0, 333.0))
        assertNull(rateForFrequency(437.0, 0.0, null))
    }

    @Test fun anNbScreenStaysAwakeWhileAudioPlaysOrItIsKeyed() {
        assertTrue(keepAwake(audioRate = 12000, muted = false, keyed = false))
        // Muted, or no audio stream: the screensaver may come.
        assertFalse(keepAwake(12000, muted = true, keyed = false))
        assertFalse(keepAwake(0, muted = false, keyed = false))
        // Keyed from this phone, always: screen-off would unkey it.
        assertTrue(keepAwake(0, muted = true, keyed = true))
    }
}
