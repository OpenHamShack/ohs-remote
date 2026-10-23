package com.openhamshack.remote.shack

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Tuning into a band's FM channels, against the band-plan data the app ships (assets/bandplan). */
class FmChannelsTest {
    private fun asset(path: String) = JSONObject(File("src/main/assets/bandplan/$path").readText())
    private val data by lazy {
        PlanData(PlanVocab.parse(asset("vocab.json")), PlanData.PLAN_IDS.associateWith { BandPlan.parse(asset("plans/$it.json")) })
    }
    private val uk = "rsgb-2026"
    private val modes = listOf("usb", "lsb", "cw", "am", "fm", "wfm", "freedv")

    @Test fun twoMetresFmSimplexAndRepeatersAreFmChannels() {
        assertTrue(FmChannels.inSection(data, uk, 145_237_500.0))   // FM simplex
        assertTrue(FmChannels.inSection(data, uk, 145_650_000.0))   // repeater outputs
        assertTrue(FmChannels.inSection(data, uk, 145_050_000.0))   // repeater inputs
        assertFalse(FmChannels.inSection(data, uk, 144_300_000.0))  // SSB
        assertFalse(FmChannels.inSection(data, uk, 145_825_000.0))  // satellites
        assertFalse(FmChannels.inSection(data, uk, null))
    }

    @Test fun tuningInFromSsbGoesToFmOnTheNearestChannel() {
        val e = FmChannels.entering(data, uk, 144_300_000.0, 145_204_000.0, "usb", modes)!!
        assertEquals(145_200_000.0, e.hz, 0.0)
        assertEquals("fm", e.mode)
    }

    @Test fun alreadyInFmOnlyTheChannelAndStep() {
        val e = FmChannels.entering(data, uk, 144_990_000.0 - 50_000, 145_000_000.0, "fm", modes)!!
        assertNull(e.mode)
        // Digital voice is left as it is.
        assertNull(FmChannels.entering(data, uk, 144_300_000.0, 145_237_500.0, "freedv", modes)!!.mode)
    }

    @Test fun movingWithinOrBetweenFmSectionsLeavesTheOperatorsChoice() {
        // SSB picked in FM simplex stays SSB tuning on, and on into the repeater outputs.
        assertNull(FmChannels.entering(data, uk, 145_400_000.0, 145_412_500.0, "usb", modes))
        assertNull(FmChannels.entering(data, uk, 145_587_500.0, 145_600_000.0, "usb", modes))
        // Leaving is not entering.
        assertNull(FmChannels.entering(data, uk, 145_200_000.0, 144_300_000.0, "fm", modes))
    }

    @Test fun noFmOnTheServerNoModeChange() {
        assertNull(FmChannels.entering(data, uk, 144_300_000.0, 145_237_500.0, "usb", listOf("usb", "cw"))!!.mode)
    }
}
