package com.openhamshack.remote.shack

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decoders' output on the NB/Radio kinds, against events recorded on 9
 * October 2026 from `python3 -m ohsnb --server` (openhamshack-nb main 8498fe9,
 * simulator) through tools/fakeshack.py: CW on the lower beacon and FT8 at
 * 10489.540 MHz.
 */
class DecodesTest {
    private fun text(name: String) = javaClass.classLoader!!.getResourceAsStream("shack/$name")!!.bufferedReader().readText()
    private fun events(name: String) = text(name).lines().filter { it.isNotBlank() }.map { EngineEvent.parse(it) }

    // --- a character decoder ----------------------------------------------

    @Test fun cwEventsAreDecodedText() {
        val e = events("nb-events-cw.txt")
        assertTrue(e.all { it is EngineEvent.Decoded })
        val d = e[1] as EngineEvent.Decoded
        assertEquals("cw", d.mode)
        assertEquals(700.0, d.freq!!, 0.0)
        assertEquals(19.2, d.wpm!!, 0.0)
    }

    @Test fun theLineRollsAndANewModeStartsAfresh() {
        var line = DecodedLine(cap = 10)
        for (c in "CQ CQ DE GM4BRI") line = line.add(EngineEvent.Decoded("cw", c.toString(), 700.0, if (c == 'G') 18.0 else null))
        assertEquals(" DE GM4BRI", line.text)
        assertEquals(10, line.text.length)
        assertEquals("the last speed given stays", 18.0, line.wpm!!, 0.0)
        line = line.add(EngineEvent.Decoded("rtty", "R", null, null))
        assertEquals("R", line.text)
        assertNull(line.wpm)
        assertTrue(DecodedLine().isEmpty)
    }

    // --- a slotted decoder ------------------------------------------------

    @Test fun ft8EventsAreWholeMessages() {
        val e = events("nb-events-ft8.txt")
        assertTrue(e.all { it is EngineEvent.Slot })
        val d = (e[1] as EngineEvent.Slot).decode
        assertEquals("ft8", d.mode)
        assertEquals(-2, d.snr)
        assertEquals(1095, d.df)
        assertEquals("CQ SV1QO KM18", d.text)
        assertTrue(d.isCq)
        assertFalse((e[0] as EngineEvent.Slot).decode.isCq)
    }

    @Test fun aDecoderErrorIsNotADecode() {
        assertEquals(EngineEvent.Other, EngineEvent.parse("""{"kind": "events", "mode": "ft8", "slot": 1.0, "error": "jt9 failed"}"""))
        assertEquals(EngineEvent.Other, EngineEvent.parse("""{"kind": "events", "mode": "sstv", "line": 3}"""))
    }

    @Test fun messagesNamingThisStationAreFound() {
        fun d(t: String) = SlotDecode("ft8", 0.0, 0, 0.0, 1000, t)
        assertTrue(d("GM4BRI F5KLM JN18").mentions("gm4bri"))
        assertTrue(d("F5KLM GM4BRI/P R-10").mentions("GM4BRI"))
        assertTrue(d("CQ EA8/GM4BRI IL18").mentions("GM4BRI"))
        assertTrue(d("<GM4BRI> F5KLM RR73").mentions("GM4BRI"))
        assertFalse(d("GM4BRIX F5KLM JN18").mentions("GM4BRI"))
        assertFalse(d("CQ GM4BRI IO75").mentions(""))
        assertTrue(d("CQ DX OH8ZZ KP24").isCq)
        assertFalse(d("CQDX OH8ZZ").isCq)
    }

    @Test fun decodesKeepSlotsTogetherInFrequencyOrderAndRoll() {
        var list = SlotDecodes(max = 4)
        for (ev in events("nb-events-ft8.txt")) list = list.add((ev as EngineEvent.Slot).decode)
        assertEquals(4, list.items.size)
        // The newest slot's decodes in audio-frequency order, the oldest
        // (784 Hz, and the whole first slot) dropped past the cap.
        assertEquals(listOf(1567, 1933, 2194, 2440), list.items.map { it.df })
        assertTrue(list.startsSlot(0))
        assertFalse(list.startsSlot(1))
        val two = SlotDecodes().add(SlotDecode("ft8", 15.0, 0, 0.0, 900, "A")).add(SlotDecode("ft8", 30.0, 0, 0.0, 100, "B"))
        assertTrue(two.startsSlot(1))
        // A change of mode starts the list again.
        assertEquals(listOf("C"), two.add(SlotDecode("ft4", 37.5, 0, 0.0, 100, "C")).items.map { it.text })
    }
}
