package com.openhamshack.remote.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.openhamshack.remote.api.Receiver

class TvChannelTest {
    private fun rx(id: Int, mhz: Double?, enabled: Boolean = true, heard: Boolean = id == 1) = Receiver(
        id = id, tuner = "minitiouner", kind = "", enabled = enabled, locked = true, label = "", mer = null,
        marginDb = null, name = "", provider = "", modcod = null, video = null, frequencyMHz = mhz,
        symbolRateKs = 333.0, offsetMHz = 0.0, heard = heard,
    )

    @Test
    fun viewOnlyHasNoChannels() {
        // Without the PIN the box changes nothing, so the TV offers nothing to step to.
        assertTrue(tvChannels(listOf(rx(1, 437.0), rx(2, 146.5)), canTune = false).isEmpty())
    }

    @Test
    fun oneReceiverStepsThroughTheTabletsPresets() {
        val list = tvChannels(listOf(rx(1, 437.0)), canTune = true)
        assertEquals(RX_PRESETS.map { TvChannel.Tune(it) }, list)
        assertEquals("437.00", (list[1] as TvChannel.Tune).preset.label)
        // Terrestrial only: the QO-100 beacon is on the QO-100 screen now.
        assertEquals(3, list.size)
    }

    @Test
    fun severalReceiversComeFirstAndOnlyEnabledOnes() {
        val list = tvChannels(listOf(rx(1, 437.0), rx(2, 146.5), rx(3, null, enabled = false)), canTune = true)
        assertEquals(listOf(TvChannel.Hear(1, "minitiouner"), TvChannel.Hear(2, "minitiouner")), list.take(2))
        assertEquals(2 + RX_PRESETS.size, list.size)
    }

    @Test
    fun theFirstPressStepsFromWhatIsOnScreen() {
        val list = tvChannels(listOf(rx(1, 437.0)), canTune = true)
        assertEquals(1, tvChannelIndex(list, rx(1, 437.0)))
        assertEquals(2, tvStep(list.size, tvChannelIndex(list, rx(1, 437.0)), +1))
        // A typed frequency is on no channel: down starts at the top, up at the bottom.
        assertEquals(-1, tvChannelIndex(list, rx(1, 145.0)))
        assertEquals(0, tvStep(list.size, -1, +1))
        assertEquals(list.size - 1, tvStep(list.size, -1, -1))
    }

    @Test
    fun aHeardReceiverOffEveryPresetIsFoundByItsOwnEntry() {
        val list = tvChannels(listOf(rx(1, 437.0, heard = false), rx(2, 145.0, heard = true)), canTune = true)
        assertEquals(1, tvChannelIndex(list, rx(2, 145.0, heard = true)))
    }

    @Test
    fun stepsWrapBothWays() {
        assertEquals(0, tvStep(4, 3, +1))
        assertEquals(3, tvStep(4, 0, -1))
        assertEquals(-1, tvStep(0, 0, +1))
    }
}
