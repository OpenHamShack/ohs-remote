package com.openhamshack.kind.stationflow.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import com.openhamshack.kind.stationflow.api.Heard

/**
 * The Heard list's frequency column follows I18N.md §4.1 (and so differs
 * from the dotted main dial on purpose): MHz with three decimals from
 * 30 MHz, kHz with one below, the locale's decimal separator, a no-break
 * space before the unit, and the audio offset signed in Hz.
 */
class HeardTuningTest {
    private val nbsp = " "

    private fun decode(rf: Long?, audio: Int?, tuneMode: String? = "USB-D") =
        Heard(Heard.Kind.Decode, key = "k", call = "GM4BRI", text = "CQ GM4BRI IO75", whenS = 0.0,
            rfHz = rf, audioHz = audio, tuneMode = tuneMode)

    @Test fun `a VHF decode reads in MHz to the kHz, the offset beneath it exact`() {
        assertEquals("144.176${nbsp}MHz" to "+1500${nbsp}Hz", heardTuning(decode(144_175_500, 1500), "en-GB"))
    }

    @Test fun `German uses a decimal comma and still never groups`() {
        assertEquals("1296,201${nbsp}MHz" to "+1200${nbsp}Hz", heardTuning(decode(1_296_200_000 + 1200, 1200), "de-DE"))
    }

    @Test fun `below 30 MHz it is kHz with one decimal`() {
        assertEquals("14075.5${nbsp}kHz", heardTuning(decode(14_075_500, 1500), "en").first)
    }

    @Test fun `APRS has no offset, so the mode a tune uses shows instead`() {
        assertEquals("144.800${nbsp}MHz" to "FM", heardTuning(decode(144_800_000, null, "FM"), "en"))
    }

    @Test fun `nothing known shows nothing`() {
        assertEquals("" to "", heardTuning(decode(null, null, null), "en"))
    }
}
