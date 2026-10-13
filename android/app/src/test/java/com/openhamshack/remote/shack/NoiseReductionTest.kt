package com.openhamshack.remote.shack

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Noise reduction on the NB/Radio kinds: the switch is offered only where
 * the engine's spec has a boolean `nr` (the spec recorded on 9 October 2026
 * from `python3 -m ohsnb --server`, openhamshack-nb main 8498fe9, through
 * tools/fakeshack.py), and the state carries it.
 */
class NoiseReductionTest {
    private fun text(name: String) = javaClass.classLoader!!.getResourceAsStream("shack/$name")!!.bufferedReader().readText()
    private fun events(name: String) = text(name).lines().filter { it.isNotBlank() }.map { EngineEvent.parse(it) }

    // --- noise reduction -------------------------------------------------

    @Test fun theSpecSaysWhetherThereIsNoiseReduction() {
        val sp = EngineSpec.parse(JSONObject(text("nb-spec.json")))
        assertTrue(sp.hasNr)
        assertTrue(sp.nrHelp.startsWith("Noise reduction"))
        assertTrue("ft8" in sp.modes && "cw" in sp.modes)
        // An engine without the key (or with one that is not a switch) offers none.
        assertFalse(EngineSpec.parse(JSONObject("""{"mode": {"choices": ["usb"]}}""")).hasNr)
        assertFalse(EngineSpec.parse(JSONObject("""{"nr": {"type": "float"}}""")).hasNr)
    }

    @Test fun theStateCarriesNoiseReduction() {
        assertNull(EngineState().nr)
        val s = EngineState().merge(JSONObject("""{"nr": true}"""))
        assertEquals(true, s.nr)
        assertEquals(false, s.merge(JSONObject("""{"nr": false}""")).nr)
    }
}
