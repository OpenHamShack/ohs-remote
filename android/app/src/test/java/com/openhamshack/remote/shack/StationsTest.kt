package com.openhamshack.remote.shack

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Radio's station lists and RDS (openhamshack-nb SERVER-API "Station lists"
 * and "Wide FM"): parsed defensively, the FM picker's genres and orders, a
 * station's badge colour fixed by its PI, and where the DAB picker's list
 * comes from.
 */
class StationsTest {
    private val fm = JSONObject("""{"scannedAt": "2026-11-02T03:00:41Z", "nextDue": "2026-11-09T03:00:00Z", "status": "ok", "statusText": "",
      "stations": [
        {"pi": "C201", "ps": "SIM ONE ", "pty": 10, "ptyName": "Pop music", "rt": null,
         "best": {"hz": 88600000, "level": -38.0, "stereo": true}, "alts": [{"hz": 91300000, "level": -47.0, "stereo": true}]},
        {"pi": null, "ps": null, "pty": null, "ptyName": null, "rt": null, "best": {"hz": 97700000, "level": -46.0, "stereo": false}, "alts": []},
        {"pi": "C3A1", "ps": "CLASSIC ", "pty": 14, "ptyName": "Serious classical", "rt": "Bach, cello suite 1",
         "best": {"hz": 101200000, "level": -40.0, "stereo": true}, "alts": []},
        {"pi": "C4B2", "ps": "ALPHA FM", "pty": 10, "ptyName": "Pop music", "best": {"hz": 104500000}},
        {"pi": "C5C3", "ps": "NO BEST"},
        "not an object"]}""")

    @Test fun theFmListIsReadDefensively() {
        val l = Stations.parseFm(fm)
        assertEquals(ListStatus.OK, l.header.status)
        assertEquals("2026-11-02T03:00:41Z", l.header.scannedAt)
        assertTrue(l.header.showsDates)
        // No best frequency: dropped. The PS is trimmed for showing, kept as sent.
        assertEquals(4, l.stations.size)
        assertEquals("SIM ONE", l.stations[0].name)
        assertEquals(listOf(91_300_000.0), l.stations[0].alts.map { it.hz })
        assertNull(l.stations[1].name)
        assertNull(l.stations[0].rt)
        assertEquals("Bach, cello suite 1", l.stations[2].rt)
    }

    @Test fun genresAreThoseHeardAndTilesSortAzOrByFrequency() {
        val l = Stations.parseFm(fm)
        assertEquals(listOf("Pop music", "Serious classical"), Stations.genres(l))
        // A-Z by name, a station without RDS after the named ones.
        assertEquals(listOf("ALPHA FM", "CLASSIC", "SIM ONE", null), Stations.tiles(l, null, FmSort.AZ).map { it.name })
        assertEquals(listOf(88.6, 97.7, 101.2, 104.5), Stations.tiles(l, null, FmSort.MHZ).map { it.best.hz / 1e6 })
        assertEquals(listOf("ALPHA FM", "SIM ONE"), Stations.tiles(l, "Pop music", FmSort.AZ).map { it.name })
    }

    @Test fun theStationOnTheAirIsFoundByPiOrFrequency() {
        val l = Stations.parseFm(fm)
        assertEquals("SIM ONE", Stations.playing(l, 91_300_000.0, null)?.name)
        assertEquals("CLASSIC", Stations.playing(l, 88_000_000.0, "C3A1")?.name)
        assertNull(Stations.playing(l, 95_000_000.0, null))
    }

    @Test fun aBadgeColourFollowsThePiWhateverTheFrequency() {
        val one = Stations.parseFm(fm).stations[0]
        assertEquals("C201", one.badgeKey)
        val moved = one.copy(best = FmHeard(91_300_000.0, null, true))
        assertEquals(StationBadge.colourIndex(one.badgeKey, 8), StationBadge.colourIndex(moved.badgeKey, 8))
        // One without RDS is known by its frequency.
        assertEquals("97700000", Stations.parseFm(fm).stations[1].badgeKey)
    }

    private val dab = JSONObject("""{"scannedAt": "2026-11-02T03:01:40Z", "nextDue": null, "status": "ok", "statusText": "",
      "ensembles": [{"block": "11D", "freqHz": 222064000, "id": "C1CE", "label": "Shack Test Mux",
                     "services": [{"sid": "C221", "label": "Shack MP2 Tone", "kind": "dab", "bitrate": 128},
                                  {"sid": "C3A7", "label": "Shack DAB+ Data", "kind": "dab+", "bitrate": 64}, {"label": "no id"}]},
                    {"freqHz": 1, "services": []}]}""")

    @Test fun theDabListIsGroupedByEnsemble() {
        val l = Stations.parseDab(dab)
        assertEquals(1, l.ensembles.size)
        assertEquals("11D", l.ensembles[0].block)
        assertEquals(listOf("C221", "C3A7"), l.ensembles[0].services.map { it.sid })
        assertNull(l.header.nextDue)
    }

    @Test fun theServersListIsUsedUnlessItHasNone() {
        val l = Stations.parseDab(dab)
        assertEquals(DabSource.SERVER, Stations.dabSource(l, missing = false))
        // Never scanned, or a radio that cannot: Remote's own block scan.
        val never = l.copy(header = l.header.copy(status = ListStatus.NEVER))
        assertEquals(DabSource.LOCAL_SCAN, Stations.dabSource(never, missing = false))
        assertEquals(DabSource.LOCAL_SCAN, Stations.dabSource(l.copy(header = l.header.copy(status = ListStatus.UNSUPPORTED)), false))
        // An older Radio with no such list (404).
        assertEquals(DabSource.LOCAL_SCAN, Stations.dabSource(null, missing = true))
        // Busy, aborted or scanning: the last list stays the server's.
        assertEquals(DabSource.SERVER, Stations.dabSource(l.copy(header = l.header.copy(status = ListStatus.ABORTED)), false))
        val aborted = ListHeader("2026-11-02T03:00:41Z", null, ListStatus.ABORTED, "Someone wanted the radio")
        assertFalse(aborted.showsDates)
    }

    @Test fun rdsAndTheScanAreReadFromState() {
        val st = EngineState(JSONObject("""{"rds": {"pi": "C201", "ps": "SIM ONE ", "rt": "Sim One: 1 kHz left", "pty": 10,
            "ptyName": "Pop music", "ta": false, "tp": true, "stereo": true, "ts": 1792747301.5},
            "bandScan": {"band": "fm", "percent": 45, "phase": "listening", "freqHz": 95150000, "text": "FM: 95.15 MHz (5 of 11)"},
            "stationsAt": {"fm": "2026-11-02T03:00:41Z", "dab": null}}"""))
        assertEquals("SIM ONE", st.rds!!.name)
        assertTrue(st.rds!!.tp)
        assertEquals(45, st.bandScan!!.percent)
        assertEquals("FM: 95.15 MHz (5 of 11)", st.bandScan!!.text)
        assertEquals(mapOf("fm" to "2026-11-02T03:00:41Z", "dab" to null), st.stationsAt)
        // Null outside wfm or after a retune; {} when no scan runs.
        assertNull(EngineState(JSONObject("""{"rds": null, "bandScan": {}}""")).rds)
        assertNull(EngineState(JSONObject("""{"rds": null, "bandScan": {}}""")).bandScan)
        assertEquals("dab", Stations.stationsEvent(JSONObject("""{"kind": "stations", "band": "dab", "scannedAt": "x", "count": 3}""")))
        assertEquals("""{"band":"both"}""", Stations.scanBody(Stations.BOTH).toString())
    }

    @Test fun aPaddedPsIsTrimmedForShowingSortingAndTheBadge() {
        val l = Stations.parseFm(JSONObject("""{"status": "ok", "stations": [
            {"pi": "C203", "ps": "BBC R3  ", "best": {"hz": 90300000}},
            {"pi": "C1A1", "ps": " ALPHA  ", "best": {"hz": 104500000}},
            {"pi": "C111", "ps": "        ", "best": {"hz": 95000000}}]}"""))
        assertEquals("BBC R3", l.stations[0].ps)
        assertEquals(listOf("ALPHA", "BBC R3", null), Stations.tiles(l, null, FmSort.AZ).map { it.name })
        assertEquals("R3", StationBadge.initials(l.stations[0].name!!))
        assertNull(l.stations[2].name)
        val rds = Rds.parse(JSONObject("""{"pi": "C203", "ps": "BBC R3  ", "ta": true, "tp": false}"""))!!
        assertEquals("BBC R3", rds.ps)
    }

    @Test fun taIsShownOnlyOnAStationThatCarriesTraffic() {
        fun traffic(ta: Boolean, tp: Boolean) = Rds.parse(JSONObject().put("pi", "C203").put("ta", ta).put("tp", tp))!!.traffic
        // Real BBC Radio 3: ta true, tp false (another programme's announcement, EON). No marker.
        assertNull(traffic(ta = true, tp = false))
        assertEquals(Traffic.TA, traffic(ta = true, tp = true))
        assertEquals(Traffic.TP, traffic(ta = false, tp = true))
        assertNull(traffic(ta = false, tp = false))
    }

    @Test fun twentyStationsNineWithoutPsListNamedFirstThenByFrequency() {
        val rows = (0 until 20).joinToString(",") { i ->
            val hz = 88_000_000 + i * 1_000_000
            if (i % 2 == 0 && i < 18) """{"pi": null, "ps": null, "best": {"hz": $hz}}"""
            else """{"pi": "C$i", "ps": "${('Z' - i)}STN    ", "best": {"hz": $hz}}"""
        }
        val l = Stations.parseFm(JSONObject("""{"status": "ok", "stations": [$rows]}"""))
        val az = Stations.tiles(l, null, FmSort.AZ)
        assertEquals(9, az.count { it.name == null })
        // The eleven named ones A-Z, then the nine without a PS by frequency.
        assertEquals(az.take(11).mapNotNull { it.name }.sorted(), az.take(11).map { it.name })
        assertEquals(az.drop(11).map { it.best.hz }.sorted(), az.drop(11).map { it.best.hz })
        assertTrue(az.drop(11).all { it.name == null })
        assertEquals(l.stations.map { it.best.hz }.sorted(), Stations.tiles(l, null, FmSort.MHZ).map { it.best.hz })
        // A badge for one without a name says its whole MHz, so 100.x, 103.x and 107.x read apart.
        assertEquals(listOf("88", "90", "92"), az.drop(11).take(3).map { it.badgeText })
        assertNull(az.first().badgeText)
    }
}
