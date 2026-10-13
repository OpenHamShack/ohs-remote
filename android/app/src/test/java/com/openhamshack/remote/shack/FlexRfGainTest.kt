package com.openhamshack.remote.shack

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.openhamshack.remote.shack.FlexStandIn.Companion.until

/** RF gain (preamp / attenuator) on Remote's Flex kind: the radio's ladder or the table per model, and what the radio is asked for. */
class FlexRfGainTest {
    private var fake: FlexStandIn? = null

    @After fun down() {
        fake?.stop()
    }

    @Test fun theLadderIsTheModelsWithTheRadiosOwnValueOnIt() {
        assertEquals(listOf(-8, 0, 8, 16, 24, 32), FlexRfGain.steps("FLEX-6400"))
        assertEquals(listOf(-8, 0, 8, 16, 24, 32), FlexRfGain.steps("flex-6400m", 16))
        assertEquals(listOf(-10, 0, 10, 20, 30), FlexRfGain.steps("FLEX-8600"))
        // A value the table does not have (the radio knows better) is kept, so it can be shown and left.
        assertEquals(listOf(-10, 0, 6, 10, 20, 30), FlexRfGain.steps("FLEX-6600", 6))
    }

    @Test fun theRadiosOwnLadderBeatsTheTable() {
        // A FLEX-6400 whose radio says -10..30: the radio knows, the table guessed.
        assertEquals(listOf(-10, 0, 10, 20, 30), FlexRfGain.steps("FLEX-6400", 0, listOf(-10, 0, 10, 20, 30)))
        assertEquals(listOf(-10, -5, 0, 10), FlexRfGain.steps("FLEX-6400", -5, listOf(-10, 0, 10)))
        // Nothing usable from the radio: the table, as for an older module.
        assertEquals(listOf(-8, 0, 8, 16, 24, 32), FlexRfGain.steps("FLEX-6400", 0, emptyList()))
    }

    @Test fun theModulesStepsAreReadFromTheListOrWorkedOut() {
        assertEquals(listOf(-8, 0, 8, 16, 24, 32), FlexPan.rfSteps(JSONObject("""{"low":-8,"high":32,"step":8,"markers":[],"list":[-8,0,8,16,24,32]}""")))
        assertEquals(listOf(-10, 0, 10, 20, 30), FlexPan.rfSteps(JSONObject("""{"low":-10,"high":30,"step":10,"markers":[0,20]}""")))
        assertNull(FlexPan.rfSteps(JSONObject("""{"low":30,"high":-10,"step":10}""")))
        assertNull(FlexPan.rfSteps(JSONObject("""{"low":-10,"high":30,"step":0}""")))
        assertNull(FlexPan.rfSteps(null))
    }

    @Test fun steppingStopsAtEitherEnd() {
        val s = FlexRfGain.steps("FLEX-6400")
        assertEquals(8, FlexRfGain.next(s, 0, +1))
        assertEquals(-8, FlexRfGain.next(s, 0, -1))
        assertEquals(-8, FlexRfGain.next(s, -8, -1))
        assertEquals(32, FlexRfGain.next(s, 32, +1))
    }

    @Test fun negativeIsTheAttenuatorAndPositiveThePreamp() {
        assertEquals(FlexRfGain.Kind.ATT, FlexRfGain.kind(-8))
        assertEquals(FlexRfGain.Kind.OFF, FlexRfGain.kind(0))
        assertEquals(FlexRfGain.Kind.PREAMP, FlexRfGain.kind(16))
    }

    @Test fun aStepIsSentAsSliceRfGainAndHeldUntilTheRadioSaysOtherwise() {
        val f = FlexStandIn().start().also { fake = it }
        val s = f.open()
        assertEquals("FLEX-6400", s.ui.value.state!!.radioModel)
        assertEquals(0, s.ui.value.rfGain)
        s.setRfGain(12)                 // not a FLEX-6400 step: nothing sent
        s.stepRfGain(+1)                // 0 -> 8
        val w = f.writes()
        assertEquals(1, w.size)
        assertEquals("slice.rf_gain", w.single().getString("action"))
        assertEquals(JSONObject("""{"slice_id":0,"value":8}""").toString(), w.single().getJSONObject("arguments").toString())
        // The module does not apply it to its state; the screen shows the choice...
        until("the held gain") { s.ui.value.rfGain == 8 }
        // ...until the radio reports something else.
        val att = JSONObject(f.hello).apply { getJSONObject("state").getJSONObject("slices").getJSONObject("0").put("rf_gain", -8) }
        f.socket!!.send(att.put("type", "state").toString())
        until("the radio's gain") { s.ui.value.rfGain == -8 }
        assertNull(s.ui.value.heldRf)
    }

    /** The fixture's hello with the module's `rf_gain_steps` on its panadapter (null for a radio that will not say). */
    private fun helloWithSteps(steps: Any?): String = JSONObject(FlexStandIn.resource("flex-hello-dsp.json")).apply {
        getJSONObject("state").getJSONObject("panadapters").getJSONObject("0x40000000").put("rf_gain_steps", steps ?: JSONObject.NULL)
    }.toString()

    @Test fun aModuleThatPublishesTheLadderIsSentPanRfGainOnItsSteps() {
        val f = FlexStandIn(hello = helloWithSteps(JSONObject("""{"low":-10,"high":30,"step":10,"markers":[0,20],"list":[-10,0,10,20,30]}"""))).start().also { fake = it }
        val s = f.open()
        val pan = s.ui.value.state!!.pan!!
        assertTrue(pan.panRfGain)
        // The radio's -10..30 in tens, though the table says a 6400 takes eights.
        assertEquals(listOf(-10, 0, 10, 20, 30), s.ui.value.rfSteps)
        s.setRfGain(8)                  // a table step, not this radio's: nothing sent
        s.stepRfGain(+1)                // 0 -> 10
        val w = f.writes()
        assertEquals(1, w.size)
        assertEquals("pan.rf_gain", w.single().getString("action"))
        assertEquals(JSONObject("""{"pan":"0x40000000","value":10}""").toString(), w.single().getJSONObject("arguments").toString())
        until("the held gain") { s.ui.value.rfGain == 10 }
    }

    @Test fun aModuleWhoseRadioWouldNotSayKeepsTheTableButTakesPanRfGain() {
        val f = FlexStandIn(hello = helloWithSteps(null)).start().also { fake = it }
        val s = f.open()
        assertTrue(s.ui.value.state!!.pan!!.panRfGain)
        assertNull(s.ui.value.state!!.pan!!.rfSteps)
        assertEquals(listOf(-8, 0, 8, 16, 24, 32), s.ui.value.rfSteps)
        s.stepRfGain(-1)
        assertEquals("pan.rf_gain", f.writes().single().getString("action"))
    }

    @Test fun anOlderModuleSaysNothingOfStepsOrPanRfGain() {
        val f = FlexStandIn().start().also { fake = it }
        val s = f.open()
        assertFalse(s.ui.value.state!!.pan!!.panRfGain)
        assertNull(s.ui.value.state!!.pan!!.rfSteps)
    }
}
