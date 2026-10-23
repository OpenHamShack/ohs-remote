package com.openhamshack.remote.shack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The TV polish pass's decisions (Brian's TvPolish mockups, 23/10/2026). */
class RadioPolishTest {
    @Test fun theSMeterLightsOneSegmentAnSUnitThenOneATenOverS9() {
        assertEquals(7, SMeterScale.litFor("S7"))
        assertEquals(9, SMeterScale.litFor("S9"))
        assertEquals(11, SMeterScale.litFor("S9+20"))
        assertEquals(11, SMeterScale.litFor("S9+23"))
        assertEquals(15, SMeterScale.litFor("S9+90"))
        assertEquals(0, SMeterScale.litFor("S0"))
        assertNull(SMeterScale.litFor("-73 dBm"))
        assertNull(SMeterScale.litFor(null as String?))
        // Uncalibrated: the bar's own fraction.
        assertEquals(8, SMeterScale.litFor(0.5f))
        assertEquals(15, SMeterScale.litFor(2f))
    }

    @Test fun theScaleIsLabelledAsTheMockup() {
        assertEquals(listOf("1", "", "3", "", "5", "", "7", "", "9", "", "+20", "", "+40", "", "+60"),
            (0 until SMeterScale.SEGMENTS).map { SMeterScale.label(it) })
    }

    @Test fun theLastTwoDigitsOfAMegahertzReadingAreSetApart() {
        assertEquals("97.700" to "00", splitTail("97.70000", "MHz"))
        assertEquals("7100.00" to "", splitTail("7100.00", "kHz"))
        assertEquals("—" to "", splitTail("—", ""))
    }

    @Test fun aStationsBadgeIsItsInitialsInAColourOfItsOwn() {
        assertEquals("R4", StationBadge.initials("BBC Radio 4"))
        assertEquals("C1", StationBadge.initials("Clyde 1"))
        assertEquals("S1", StationBadge.initials("Signal 1"))
        assertEquals("CS", StationBadge.initials("Canal Street Radio"))
        assertEquals("6M", StationBadge.initials("BBC 6 Music"))
        assertEquals("BE", StationBadge.initials("The Beacon"))
        assertEquals("?", StationBadge.initials("  "))
        // Stable: the same id is the same colour every time; ids spread over the palette.
        assertEquals(StationBadge.colourIndex("C221", 8), StationBadge.colourIndex("C221", 8))
        assertEquals(true, (0 until 40).map { StationBadge.colourIndex("C2%02X".format(it), 8) }.toSet().size >= 6)
    }

    @Test fun thePolishedWaterfallRunsNavyToAmber() {
        assertEquals(0xFF060A12.toInt(), Scope.polished(0f))
        assertEquals(0xFFF4C063.toInt(), Scope.polished(1f))
        assertEquals(0xFF208CAA.toInt(), Scope.polished(0.5f))
    }
}
