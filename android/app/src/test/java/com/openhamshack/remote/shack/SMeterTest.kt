package com.openhamshack.remote.shack

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The calibrated S-meter on the events socket's `meter` item (Radio's
 * server-side calibration, 23/10/2026): what is read from it, and whether
 * the screen shows a reading or says why there is none.
 */
class SMeterTest {
    private fun meter(json: String) = EngineEvent.parse("""{"kind":"meter",$json}""") as EngineEvent.Meter

    @Test fun aCalibratedMeterGivesItsReadingBesideTheBar() {
        val m = meter(""""rx":-62.5,"dbm":-112.9,"s":"S9+12"""")
        assertEquals(-62.5, m.rxDb!!, 0.0)
        assertEquals("S9+12", m.s.reading)
        assertEquals(-112.9, m.s.dbm!!, 0.0)
        // A reading is shown, so there is nothing to explain.
        assertNull(m.s.explanation)
        // A why sent beside a reading is not shown either.
        assertNull(meter(""""rx":-80,"s":"S7","why":"stale"""").s.explanation)
    }

    @Test fun anUncalibratedMeterKeepsTheBarAndSaysWhy() {
        val m = meter(""""rx":-80,"why":"This receiver has not been calibrated."""")
        assertNull(m.s.reading)
        assertEquals("This receiver has not been calibrated.", m.s.explanation)
        // Today's servers send rx alone: the bar, and nothing to say.
        val old = meter(""""rx":-80""")
        assertEquals(SMeter(), old.s)
        assertNull(old.s.explanation)
    }

    @Test fun oddValuesFallBackToTheBar() {
        // Not text, empty, null, too long or more than one line: no reading.
        assertNull(meter(""""rx":-80,"s":null""").s.reading)
        assertNull(meter(""""rx":-80,"s":"  """").s.reading)
        assertNull(meter(""""rx":-80,"s":"S9 plus a great deal"""").s.reading)
        assertNull(meter(""""rx":-80,"s":"S9\nS8"""").s.reading)
        // A number where text was expected is still a short reading: shown as sent.
        assertEquals("7", meter(""""rx":-80,"s":7""").s.reading)
        // dBm that is not a number, or not a level a receiver hears, is dropped.
        assertNull(meter(""""rx":-80,"dbm":"loud"""").s.dbm)
        assertNull(meter(""""rx":-80,"dbm":1e9""").s.dbm)
        assertNull(meter(""""rx":-80,"why":null""").s.explanation)
        assertNull(meter(""""rx":-80,"why":"   """").s.explanation)
        // A meter item with no rx still carries the reading.
        assertEquals("S5", meter(""""s":"S5"""").s.reading)
        assertNull(meter(""""s":"S5"""").rxDb)
    }

    @Test fun parsedFromItsOwnObjectToo() {
        assertEquals(SMeter("S3", -109.0, null), SMeter.parse(JSONObject("""{"s":" S3 ","dbm":-109}""")))
    }
}
