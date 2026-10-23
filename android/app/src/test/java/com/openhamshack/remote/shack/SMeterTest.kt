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

    @Test fun theSentenceIsWhyTextAndWhyIsOnlyItsCode() {
        val m = meter(""""rx": -60.0, "dbm": null, "s": null, "why": "no_calibration", "whyText": "This band has not been calibrated."""")
        assertEquals("This band has not been calibrated.", m.s.explanation)
        // Calibrated: whyText is "" and why null - nothing to explain.
        assertNull(meter("\"rx\": -60.0, \"dbm\": -110.4, \"s\": \"S4\", \"why\": null, \"whyText\": \"\"").s.explanation)
        // An earlier server put the sentence in why itself.
        assertEquals("Not calibrated yet.", meter(""""rx": -60.0, "why": "Not calibrated yet."""").s.explanation)
    }

    @Test fun aCalibrationLevelIsS9OrWhatIsTyped() {
        assertEquals(-93.0, SMeterCal.s9Dbm(145e6), 0.0)
        assertEquals(-73.0, SMeterCal.s9Dbm(14e6), 0.0)
        assertEquals(-85.0, SMeterCal.parseLevel("-85", 145e6)!!, 0.0)
        assertEquals(-85.5, SMeterCal.parseLevel(" -85,5 dBm", 145e6)!!, 0.0)
        assertEquals(-105.0, SMeterCal.parseLevel("S7", 145e6)!!, 0.0)
        assertEquals(-63.0, SMeterCal.parseLevel("s9+10", 14e6)!!, 0.0)
        // Out of the server's range, nonsense, or an S reading that cannot be.
        assertNull(SMeterCal.parseLevel("-200", 145e6))
        assertNull(SMeterCal.parseLevel("loud", 145e6))
        assertNull(SMeterCal.parseLevel("S7+10", 145e6))
    }
}
