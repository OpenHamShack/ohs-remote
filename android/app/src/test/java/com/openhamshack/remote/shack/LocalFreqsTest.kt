package com.openhamshack.remote.shack

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Radio's local frequencies (openhamshack-nb SERVER-API "Local frequencies",
 * fb64699) read as Remote reads them: defensively, in the server's order of
 * categories and nearest first, the chips mapped to its categories, the
 * NCDXF beacon on each frequency kept in step with the server's clock, and
 * only the fields Radio takes sent for the operator's own.
 */
class LocalFreqsTest {
    private val sample = JSONObject("""{
      "locator": "IO85", "radiusKm": 100, "generatedAt": "2026-10-23T12:00:00Z",
      "attributions": [{"source": "OurAirports", "text": "Airfield frequencies from OurAirports (public domain)",
                        "licence": "Public domain", "url": "https://ourairports.com/data/"}, {"source": "", "text": ""}],
      "entries": [
        {"id": "channels:1:446006250", "category": "pmr", "name": "PMR446 Ch 1", "freqHz": 446006250, "mode": "fm", "step": 12500, "source": "channels"},
        {"id": "ourairports:EGPH:118700000", "category": "airband", "name": "Edinburgh Airport", "sub": "TWR Edinburgh Tower",
         "freqHz": 118700000, "mode": "AM", "step": 8333, "distanceKm": 11.8, "bearingDeg": 284, "source": "ourairports"},
        {"id": "osm:1", "category": "marine", "name": "Leith Port", "freqHz": 156600000, "mode": "fm", "distanceKm": 4.2, "bearingDeg": 15},
        {"id": "itu-marine:16:156800000", "category": "marine", "name": "Marine Ch 16", "freqHz": 156800000, "mode": "fm", "step": 25000},
        {"id": "cb:19", "category": "cb", "name": "CB Ch 19", "freqHz": 27185000, "mode": "fm"},
        {"id": "mine:m1", "category": "mine", "name": "GB3GA", "freqHz": 145712500, "mode": "fm", "source": "mine",
         "userCategory": "repeater", "notes": "CTCSS 103.5", "locator": "IO75TU", "distanceKm": 0.0, "bearingDeg": 0},
        {"id": "x", "category": "radar", "name": "Something new", "freqHz": 1030000000, "distanceKm": 30},
        {"id": "e5", "category": "airband", "name": "", "freqHz": 120000000},
        {"id": "e6", "category": "airband", "name": "No frequency"},
        "not an object"
      ],
      "ncdxf": {"now": {"14100000": {"call": "VE8AT", "location": "Eureka"}},
                "at": "2026-10-23T12:00:00Z", "nextChangeS": 10.0, "slotSeconds": 10, "cycleSeconds": 180,
                "frequencies": [14100000, 18110000, 21150000, 24930000, 28200000],
                "schedule": [{"slot": 0, "call": "4U1UN", "location": "United Nations, New York"}, {"slot": 1, "call": "VE8AT"},
                  {"slot": 2, "call": "W6WX"}, {"slot": 3, "call": "KH6RS"}, {"slot": 4, "call": "ZL6B"}, {"slot": 5, "call": "VK6RBP"},
                  {"slot": 6, "call": "JA2IGY"}, {"slot": 7, "call": "RR9O"}, {"slot": 8, "call": "VR2B"}, {"slot": 9, "call": "4S7B"},
                  {"slot": 10, "call": "ZS6DN"}, {"slot": 11, "call": "5Z4B"}, {"slot": 12, "call": "4X6TU"}, {"slot": 13, "call": "OH2B"},
                  {"slot": 14, "call": "CS3B"}, {"slot": 15, "call": "LU4AA"}, {"slot": 16, "call": "OA4B"}, {"slot": 17, "call": "YV5B"}],
                "credit": "NCDXF/IARU International Beacon Project"}
    }""")
    /** 2026-10-23T12:00:00Z: a whole number of 3-minute cycles, so slot 0. */
    private val noon = java.time.Instant.parse("2026-10-23T12:00:00Z").toEpochMilli()

    @Test fun entriesAreReadDefensivelyInRadiosOrder() {
        val l = LocalFreqs.parse(sample, noon)
        assertEquals(100.0, l.radiusKm!!, 0.0)
        assertEquals("IO85", l.locator)
        assertEquals("", l.why)
        assertEquals(listOf("Airfield frequencies from OurAirports (public domain)"), l.attributions.map { it.text })
        assertEquals("Public domain", l.attributions[0].licence)
        // By category, nearest first inside one, the table's own after the positioned; an unknown category last.
        assertEquals(listOf("Edinburgh Airport", "Leith Port", "Marine Ch 16", "PMR446 Ch 1", "CB Ch 19", "GB3GA", "Something new"),
            l.entries.map { it.name })
        val tower = l.entries[0]
        assertEquals("am", tower.mode)
        assertEquals(284.0, tower.bearing!!, 0.0)
        // A channel table has no distance, which the screen shows as a dash, never 0 km.
        assertNull(l.entries[2].distanceKm)
        val mine = l.entries[5]
        assertTrue(mine.mine)
        assertEquals("repeater", mine.userCategory)
        assertEquals("CTCSS 103.5", mine.notes)
    }

    @Test fun theChipsAreRadiosCategoriesAndPmrIsThreeOfThem() {
        val l = LocalFreqs.parse(sample, noon)
        assertEquals(listOf("PMR446 Ch 1", "CB Ch 19"), LocalFreqs.filter(l, LocalChip.PMR).map { it.name })
        assertEquals(listOf("Edinburgh Airport"), LocalFreqs.filter(l, LocalChip.AIRPORTS).map { it.name })
        // Area control (openAIP) is empty until Brian sets a key: the chip stays, with nothing under it.
        assertTrue(LocalFreqs.filter(l, LocalChip.AREA).isEmpty())
        assertEquals(7, LocalFreqs.filter(l, LocalChip.ALL).size)
    }

    @Test fun noLocatorIsSaid() {
        val l = LocalFreqs.parse(JSONObject("""{"locator": "", "why": "Set the station's locator", "entries": []}"""))
        assertEquals("Set the station's locator", l.why)
        assertNull(l.beacons)
    }

    @Test fun theBeaconOnEachFrequencyKeepsTheServersTime() {
        // The device's clock 7 s behind the server's when the list was read.
        val b = LocalFreqs.parse(sample, noon - 7_000).beacons!!
        assertEquals(7_000L, b.skewMillis)
        assertEquals("NCDXF/IARU International Beacon Project", b.credit)
        // Server time 12:00:00 is slot 0: 4U1UN on 14.100, YV5B (the last) on 18.110.
        val at0 = LocalFreqs.beaconsNow(b, noon - 7_000)
        assertEquals(listOf("4U1UN", "YV5B", "OA4B", "LU4AA", "CS3B"), at0.map { it.call })
        assertEquals("United Nations, New York", at0[0].location)
        // 10 s of device time on is 12:00:10 by the server: 4U1UN has moved up to 18.110.
        assertEquals("4U1UN", LocalFreqs.beaconsNow(b, noon + 3_000)[1].call)
        assertEquals(10_000L, LocalFreqs.msToNextSlot(b, noon - 7_000))
        // Without the whole schedule, the server's own word.
        assertEquals(listOf("VE8AT"), LocalFreqs.beaconsNow(b.copy(schedule = emptyList()), noon).map { it.call })
    }

    @Test fun okTunesWithTheModeAndStepItCan() {
        val tower = LocalFreqs.parse(sample, noon).entries[0]
        assertEquals(LocalSpot(118_700_000.0, "am", 8333), LocalFreqs.target(tower, listOf("am", "fm")))
        assertEquals(LocalSpot(118_700_000.0, null, 8333), LocalFreqs.target(tower, listOf("usb")))
        assertNull(LocalFreqs.target(tower.copy(stepHz = 7), emptyList()).stepHz)
        assertEquals(LocalSpot(14_100_000.0, "cw", null), LocalFreqs.target(BeaconBand(14_100_000.0), listOf("cw")))
    }

    @Test fun onlyTheFieldsRadioTakesAreSent() {
        val b = LocalFreqs.mineBody(" Club net ", 145_500_000.0, "fm", category = "repeater", notes = "")
        assertEquals(setOf("name", "freqHz", "mode", "category"), b.keys().asSequence().toSet())
        assertEquals("Club net", b.getString("name"))
        assertEquals(145_500_000L, b.getLong("freqHz"))
        // A change sends only what changed.
        val was = LocalFreqs.parse(sample, noon).entries[5]
        assertEquals("""{"name":"GB3GA net"}""", LocalFreqs.mineChanges(was, "GB3GA net", was.freqHz).toString())
        assertFalse(LocalFreqs.mineChanges(was, "GB3GA", was.freqHz).keys().hasNext())
    }

    @Test fun theRangeChipWalksTheRadii() {
        assertEquals(200, LocalFreqs.nextRadius(100.0))
        assertEquals(25, LocalFreqs.nextRadius(400.0))
        assertEquals(25, LocalFreqs.nextRadius(null))
        assertEquals(50, LocalFreqs.nextRadius(30.0))
    }

    @Test fun bearingsReadAsCompassPoints() {
        assertEquals(Compass.W, LocalFreqs.compass(284.0))
        assertEquals(Compass.N, LocalFreqs.compass(359.0))
        assertNull(LocalFreqs.compass(null))
    }
}
