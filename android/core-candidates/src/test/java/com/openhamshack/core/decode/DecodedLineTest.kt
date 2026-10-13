package com.openhamshack.core.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodedLineTest {
    @Test fun `characters append, the speed follows, a space keeps it`() {
        var line = DecodedLine()
        assertTrue(line.isEmpty)
        for (c in "CQ DE") line = line.add("cw", c.toString(), if (c == ' ') null else 18.0)
        assertEquals("CQ DE", line.text)
        assertEquals(18.0, line.wpm!!, 1e-9)
        // A space event has no speed (NB's and Icom's api/events): the last one stays.
        line = line.add("cw", " ", null)
        assertEquals(18.0, line.wpm!!, 1e-9)
    }

    @Test fun `a new mode starts afresh and the oldest text goes past the cap`() {
        var line = DecodedLine(cap = 5).add("cw", "ABCDEFG", 20.0)
        assertEquals("CDEFG", line.text)
        line = line.add("rtty", "RY", null)
        assertEquals("RY", line.text)
        assertNull(line.wpm)
    }

    @Test fun `only blanks is still empty, so the strip says it is listening`() {
        assertTrue(DecodedLine("cw", "   ", 20.0).isEmpty)
    }

    @Test fun `the speed shows as the NB strip always showed it`() {
        assertEquals("18.2", wpmText(18.24))
        assertEquals("20.0", wpmText(20.0))
    }
}
