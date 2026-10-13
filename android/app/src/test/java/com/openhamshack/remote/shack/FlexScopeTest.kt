package com.openhamshack.remote.shack

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Flex's zoom, reference level, NR2 and band plan strip (contract
 * ~/build/nr2-zoom-scope/CONTRACT.md 1 and 3; flex/app/bandplan.py): what
 * the module reports, the steps the D-pad takes, and where the plan lands.
 */
class FlexScopeTest {
    private fun state(pan: String, extra: String = "") = FlexState.parse(JSONObject("""{
        "connected": true, "active_slice": 0, "pan_id": "0x40000000",
        "slices": {"0": {"index": 0, "letter": "A", "frequency": 14.2, "mode": "USB", "pan": 1073741824, "active": true, "in_use": true}},
        "panadapters": {"0x40000000": $pan} $extra}"""))

    @Test fun theModulesScopeSettingsAndNr2AreReadAndAnOlderModuleHasNone() {
        val s = state("""{"low_hz": 14100000, "high_hz": 14300000, "min_dbm": -130, "max_dbm": -40,
            "spans_hz": [5000, 10000, 20000, 50000, 100000, 200000], "span_hz": 200000, "ref_dbm": -40.0,
            "range_db": 90.0, "line_rate": 80}""", """, "nr2": {"available": true, "why": null, "on": true, "level": 60, "latency_ms": 21}""")
        val pan = s.pan!!
        assertTrue(pan.hasScopeSettings)
        assertEquals(listOf(5000L, 10000L, 20000L, 50000L, 100000L, 200000L), pan.spansHz)
        assertEquals(200000L, pan.spanSetHz)
        assertEquals(-40.0, pan.refDbm!!, 0.0)
        assertEquals(90.0, pan.rangeDb!!, 0.0)
        assertEquals(80, pan.lineRate)
        assertEquals(FlexNr2(available = true, why = null, on = true, level = 60), s.nr2)
        val old = state("""{"low_hz": 14100000, "high_hz": 14300000, "min_dbm": -130, "max_dbm": -40}""")
        assertFalse(old.pan!!.hasScopeSettings)
        assertNull(old.nr2)
    }

    @Test fun eachPanadapterGetsItsOwnWaterfallsRendering() {
        val s = state("""{"low_hz": 14100000, "high_hz": 14300000, "min_dbm": -130, "max_dbm": -40}""",
            """, "waterfalls": {"0x42000000": {"panadapter_id": 1073741824, "line_duration": 80, "auto_black": false,
                 "black_level": 20, "color_gain": 45}, "0x42000001": {"panadapter_id": 99, "auto_black": true}}""")
        val pan = s.pan!!
        assertEquals(false, pan.autoBlack)
        assertEquals(20, pan.blackLevel)
        assertEquals(45, pan.colorGain)
        // The module's own spelling of the id works as well as the number.
        val spelt = state("""{"low_hz": 1, "high_hz": 2}""", """, "waterfalls": {"w": {"panadapter_id": "0x40000000", "color_gain": 7}}""")
        assertEquals(7, spelt.pan!!.colorGain)
        assertNull(state("""{"low_hz": 1, "high_hz": 2}""").pan!!.colorGain)
    }

    @Test fun zoomWalksTheModulesLadderAndStopsAtItsEnds() {
        val ladder = listOf(5000L, 10000L, 20000L, 50000L, 100000L, 200000L)
        assertEquals(100000L, FlexScopeControl.zoom(ladder, 200000, zoomIn = true))
        assertNull(FlexScopeControl.zoom(ladder, 200000, zoomIn = false))
        assertNull(FlexScopeControl.zoom(ladder, 5000, zoomIn = true))
        // A span set elsewhere (SmartSDR) that is not on the ladder: the nearest rung that way.
        assertEquals(50000L, FlexScopeControl.zoom(ladder, 73000, zoomIn = true))
        assertEquals(100000L, FlexScopeControl.zoom(ladder, 73000, zoomIn = false))
        assertNull(FlexScopeControl.zoom(emptyList(), 73000, zoomIn = true))
    }

    @Test fun theReferenceLevelMovesFiveDbAPressInsideTheRadiosScale() {
        assertEquals(-35.0, FlexScopeControl.ref(-40.0, up = true)!!, 0.0)
        assertEquals(-45.0, FlexScopeControl.ref(-40.0, up = false)!!, 0.0)
        assertEquals(20.0, FlexScopeControl.ref(17.0, up = true)!!, 0.0)
        assertNull(FlexScopeControl.ref(20.0, up = true))
        assertNull(FlexScopeControl.ref(-180.0, up = false))
    }

    @Test fun theTraceSitsOnTheRadiosScaleSoTheReferenceLevelMovesIt() {
        assertEquals(1f, FlexScopeControl.traceY(-40f, -130.0, -40.0, -120f), 0f)
        assertEquals(0.5f, FlexScopeControl.traceY(-85f, -130.0, -40.0, -120f), 1e-6f)
        // Raise the reference by 20 dB and the same signal sits lower.
        assertTrue(FlexScopeControl.traceY(-85f, -110.0, -20.0, -120f) < 0.5f)
        // No usable scale: as before, 55 dB above the trace's own floor.
        assertEquals(0.5f, FlexScopeControl.traceY(-97.5f, null, null, -120f), 1e-6f)
    }

    @Test fun theBandPlanIsReadFromTheTables() {
        val t = FlexTables.parse(JSONObject("""{"segments": [
            {"low": 14000000, "high": 14070000, "kind": "cw", "color": "#3060ff", "note": "CW", "bandwidth": 200},
            {"low": 14070000, "high": 14099000, "kind": "data", "color": "#c03030"},
            {"low": 14100000, "high": 14099000, "kind": "phone", "color": "#ff8000"},
            {"low": 14101000, "high": 14350000, "kind": "phone", "color": "nonsense"}]}"""))
        // The backwards one is dropped, not drawn; a colour it cannot read is grey, not a crash.
        assertEquals(listOf("cw", "data", "phone"), t.segments.map { it.kind })
        assertEquals(0xFF3060FF.toInt(), t.segments[0].colour)
        assertEquals(0xFF808080.toInt(), t.segments[2].colour)
        assertTrue(FlexTables.parse(JSONObject("{}")).segments.isEmpty())
    }

    @Test fun theBandPlanStripMapsTheVisibleSpanToPixels() {
        val cw = FlexSegment(14_000_000, 14_070_000, "cw", 0)
        val data = FlexSegment(14_070_000, 14_099_000, "data", 0)
        val beacon = FlexSegment(14_099_000, 14_101_000, "beacon", 0)
        val phone = FlexSegment(14_101_000, 14_350_000, "phone", 0)
        val far = FlexSegment(21_000_000, 21_070_000, "cw", 0)
        // 14.050-14.150 MHz across 1000 px: 10 px a kHz.
        val pieces = FlexPlanStrip.layout(listOf(cw, data, beacon, phone, far), 14_050_000.0, 14_150_000.0, 1000f, 60f)
        assertEquals(listOf("cw", "data", "beacon", "phone"), pieces.map { it.kind })
        // Clipped to the view at both ends, so a label sits in the middle of what is visible.
        assertEquals(0f, pieces[0].x, 1e-3f); assertEquals(200f, pieces[0].w, 1e-3f)
        assertEquals(200f, pieces[1].x, 1e-3f); assertEquals(290f, pieces[1].w, 1e-3f)
        assertEquals(510f, pieces[3].x, 1e-3f); assertEquals(490f, pieces[3].w, 1e-3f)
        // The 2 kHz beacon segment is 20 px: drawn, but no room for its word.
        assertEquals(20f, pieces[2].w, 1e-3f)
        assertFalse(pieces[2].label)
        assertTrue(pieces[0].label)
        // Zoomed right out it is still at least a pixel, never gone.
        val wide = FlexPlanStrip.layout(listOf(beacon), 13_000_000.0, 15_000_000.0, 480f, 60f)
        assertEquals(1f, wide.single().w, 0f)
        // Nothing to draw with no span or no width.
        assertTrue(FlexPlanStrip.layout(listOf(cw), 14_100_000.0, 14_100_000.0, 1000f, 60f).isEmpty())
    }

    @Test fun scopeAndNr2PostTheContractsBodiesAndNothingElse() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val client = FlexClient(server.url("/shack-flex/").toString(), { "shackdev_T" }, OkHttpClient())
            server.enqueue(MockResponse().setBody("""{"ok": true}"""))
            server.enqueue(MockResponse().setBody("""{"ok": true}"""))
            client.scope(JSONObject().put("ref_dbm", -35.0))
            client.nr2(on = true)
            val a = server.takeRequest()
            assertEquals("/shack-flex/api/scope", a.path)
            assertEquals("""{"ref_dbm":-35}""", JSONObject(a.body.readUtf8()).toString())
            val b = server.takeRequest()
            assertEquals("/shack-flex/api/nr2", b.path)
            assertEquals("""{"on":true}""", b.body.readUtf8())
            try {
                client.scope(JSONObject().put("tx_power", 100))
                fail("a non-display setting was sent")
            } catch (_: IllegalArgumentException) {
            }
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
