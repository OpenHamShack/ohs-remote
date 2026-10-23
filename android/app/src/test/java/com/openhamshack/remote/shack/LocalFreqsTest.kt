package com.openhamshack.remote.shack

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Radio's local frequencies (RADIO-LISTEN-CONTRACT §5) read as Remote reads
 * them: defensively, nearest first, the NCDXF beacon on each band worked out
 * from the clock, and where OK goes.
 */
class LocalFreqsTest {
    private val sample = JSONObject("""{
      "radiusKm": 100, "locator": "IO85", "attributions": ["Airports: OurAirports", "  ", "Beacons: NCDXF"],
      "entries": [
        {"id": "e1", "category": "pmr", "name": "PMR446 channel 1", "freqHz": 446006250, "mode": "fm", "step": 12500},
        {"id": "e2", "category": "airport", "name": "Edinburgh Tower", "sub": "EGPH", "freqHz": 118700000, "mode": "AM", "step": 8333,
         "distanceKm": 11.8, "bearing": 284, "source": "OurAirports"},
        {"id": "e3", "category": "marine", "name": "Leith Port", "freqHz": 156600000, "mode": "fm", "distanceKm": 4.2, "bearing": 15},
        {"id": "e4", "category": "radar", "name": "Something new", "freqHz": 1030000000, "distanceKm": 30},
        {"id": "e5", "category": "airport", "name": "", "freqHz": 120000000},
        {"id": "e6", "category": "airport", "name": "No frequency"},
        "not an object"
      ],
      "ncdxf": {"bands": [{"band": "20m", "freqHz": 14100000}, {"band": "17m", "freqHz": 18110000}, {"band": "15m", "freqHz": 21150000},
                          {"band": "12m", "freqHz": 24930000}, {"band": "10m", "freqHz": 28200000}],
                "schedule": ["4U1UN", "VE8AT", "W6WX", "KH6RS", "ZL6B", "VK6RBP", "JA2IGY", "RR9O", "VR2B",
                             "4S7B", "ZS6DN", "5Z4B", "4X6TU", "OH2B", "CS3B", "LU4AA", "OA4B", "YV5B"],
                "locations": {"4U1UN": "New York", "YV5B": "Caracas"},
                "now": [{"band": "20m", "freqHz": 14100000, "call": "VE8AT"}]}
    }""")

    @Test fun entriesAreReadDefensivelyAndKeptNearestFirst() {
        val l = LocalFreqs.parse(sample)
        assertEquals(100.0, l.radiusKm!!, 0.0)
        assertEquals("IO85", l.locator)
        assertEquals(listOf("Airports: OurAirports", "Beacons: NCDXF"), l.attributions)
        // No name or no frequency: dropped. Those with a distance first, nearest first; then the rest.
        assertEquals(listOf("Leith Port", "Edinburgh Tower", "Something new", "PMR446 channel 1"), l.entries.map { it.name })
        val tower = l.entries[1]
        assertEquals("am", tower.mode)
        assertEquals(8333, tower.stepHz)
        assertNull(l.entries[2].mode)
    }

    @Test fun theChipsAreTheMockupsOrderThenAnythingNewAndAlwaysMine() {
        val l = LocalFreqs.parse(sample)
        assertEquals(listOf("airport", "marine", "pmr", "beacon", "mine", "radar"), LocalFreqs.categories(l))
        assertEquals(listOf("Edinburgh Tower"), LocalFreqs.filter(l, "airport").map { it.name })
        assertEquals(4, LocalFreqs.filter(l, null).size)
        // A server with no beacons and no entries still offers the operator's own.
        assertEquals(listOf("mine"), LocalFreqs.categories(LocalFreqs.parse(JSONObject("{}"))))
    }

    @Test fun theBeaconOnEachBandComesFromTheClock() {
        val b = LocalFreqs.parse(sample).beacons!!
        // 00:00:00 UTC: slot 0, 4U1UN on 20 m, YV5B (the last) on 17 m.
        val at0 = LocalFreqs.beaconsNow(b, 0L)
        assertEquals(listOf("4U1UN", "YV5B", "OA4B", "LU4AA", "CS3B"), at0.map { it.call })
        assertEquals("New York", at0[0].location)
        // Ten seconds on, 4U1UN has moved up to 17 m; a 3-minute cycle later it is back on 20 m.
        assertEquals("4U1UN", LocalFreqs.beaconsNow(b, 10_000L)[1].call)
        assertEquals("4U1UN", LocalFreqs.beaconsNow(b, 180_000L)[0].call)
        assertEquals(4_000L, LocalFreqs.msToNextSlot(126_000L))
        // Without the full schedule, the server's own word.
        assertEquals(listOf("VE8AT"), LocalFreqs.beaconsNow(b.copy(schedule = listOf("4U1UN")), 0L).map { it.call })
    }

    @Test fun okTunesWithTheModeAndStepItCan() {
        val tower = LocalFreqs.parse(sample).entries[1]
        assertEquals(LocalSpot(118_700_000.0, "am", 8333), LocalFreqs.target(tower, listOf("am", "fm")))
        // A mode the server does not offer is left alone; a step Remote has no button for is not set.
        assertEquals(LocalSpot(118_700_000.0, null, 8333), LocalFreqs.target(tower, listOf("usb")))
        assertEquals(null, LocalFreqs.target(tower.copy(stepHz = 7), emptyList()).stepHz)
        assertEquals(LocalSpot(14_100_000.0, "cw", null), LocalFreqs.target(BeaconBand("20m", 14_100_000.0), listOf("cw")))
    }

    @Test fun theOperatorsOwnAreSentWhole() {
        val b = LocalFreqs.mineBody(" Club net ", 145_500_000.0, "fm", 12_500)
        assertEquals("Club net", b.getString("name"))
        assertEquals(145_500_000L, b.getLong("freqHz"))
        assertEquals("fm", b.getString("mode"))
        assertEquals(12_500, b.getInt("step"))
        assertTrue(!LocalFreqs.mineBody("x", 1e6, "am", null).has("step"))
    }

    @Test fun bearingsReadAsCompassPoints() {
        assertEquals(Compass.W, LocalFreqs.compass(284.0))
        assertEquals(Compass.N, LocalFreqs.compass(15.0))
        assertEquals(Compass.N, LocalFreqs.compass(359.0))
        assertEquals(Compass.SE, LocalFreqs.compass(135.0))
        assertNull(LocalFreqs.compass(null))
    }
}
