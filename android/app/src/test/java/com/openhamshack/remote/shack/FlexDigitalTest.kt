package com.openhamshack.remote.shack

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.openhamshack.remote.shack.FlexStandIn.Companion.lines
import com.openhamshack.remote.shack.FlexStandIn.Companion.resource
import com.openhamshack.remote.shack.FlexStandIn.Companion.until

/**
 * Flex's decoders and SSTV on Remote, against what the real module sent
 * (flex 5b9a5d4 decoding fakeflex_rx's FT8 band and Robot 36 pictures):
 * the choice sent, the decodes listed, the picture painted - from its first
 * line for a late joiner - and the kept pictures fetched relative to the
 * app's base.
 */
class FlexDigitalTest {
    private var fake: FlexStandIn? = null

    private fun open(after: List<String> = emptyList()): Pair<FlexStandIn, FlexSession> {
        val f = FlexStandIn(withDecoder("off")).start()
        f.after += after
        fake = f
        return f to f.open()
    }

    @After fun down() {
        fake?.stop()
    }

    /**
     * The captured hello (taken while FT8 was decoding on 14.074 DIGU) with
     * the decoder as each test needs it, and the slice at [mhz] in [mode].
     */
    private fun withDecoder(mode: String, mhz: Double = 14.2, sideband: String = "USB"): String =
        JSONObject(resource("flex-hello-dsp.json")).apply {
            getJSONObject("state").getJSONObject("decoders").put("mode", mode)
            getJSONObject("state").getJSONObject("slices").getJSONObject("0").put("frequency", mhz).put("mode", sideband)
        }.toString()

    @Test fun theStatusOffersTheModulesModesInOrder() {
        val d = FlexDecoders.parse(JSONObject(resource("flex-decoders.json")))!!
        assertEquals(listOf("ft8", "ft4", "js8", "q65"), d.modes)
        assertEquals("ft8", d.mode)
        assertTrue(d.on)
        val off = FlexState.parse(JSONObject(withDecoder("off")).getJSONObject("state"))
        assertEquals(false, off.decoders!!.on)
        assertEquals(false, off.sstv!!.on)
    }

    @Test fun aPeriodsDecodesAreTheListsRows() {
        val period = JSONObject(lines("flex-decodes.jsonl").first())
        val rows = FlexDecodes.parse(period)
        assertEquals(5, rows.size)
        val cq = rows.first { it.text == "CQ EA8XYZ IL18" }
        assertEquals("ft8", cq.mode)
        assertEquals(620, cq.df)
        assertEquals(period.getDouble("time"), cq.slot, 0.0)
        assertTrue(cq.isCq)
        assertTrue(rows.first { it.text == "M0TST GM4BRI IO75" }.mentions("GM4BRI"))
        // An empty period is alive and adds nothing.
        assertTrue(FlexDecodes.parse(JSONObject(period.toString()).put("decodes", org.json.JSONArray())).isEmpty())
    }

    @Test fun choosingADecoderSendsItAndOpensItsPanel() {
        val (f, s) = open()
        s.setDecoder("wspr")            // not a mode the module offers: nothing sent
        s.setDecoder("ft8")
        val w = f.writes("api/decoders")
        assertEquals(listOf("""{"mode":"ft8"}"""), w.map { it.toString() })
        until("the panel") { s.ui.value.panel.shown == DecoderPanel.SLOTS }
        assertTrue(s.ui.value.decoders!!.on)
    }

    @Test fun decodesOnTheSocketFillTheList() {
        val (f, s) = open()
        lines("flex-decodes.jsonl").forEach { f.socket!!.send(it) }
        until("two periods") { s.ui.value.decodes.items.size == 10 }
        val items = s.ui.value.decodes.items
        // WSJT-X's order within a period: by audio frequency.
        assertEquals(listOf(620, 1100, 1500, 1870, 2310), items.take(5).map { it.df })
        assertTrue(s.ui.value.decodes.startsSlot(5))
    }

    @Test fun joiningWhileDecodingFetchesWhatWasHeard() {
        val f = FlexStandIn(withDecoder("ft8")).start()
        fake = f
        val s = f.open()
        until("the history") { s.ui.value.decodes.items.isNotEmpty() }
        assertTrue(f.got.any { it == "api/decodes" })
        assertEquals(DecoderPanel.SLOTS, s.ui.value.panel.shown)
    }

    @Test fun theCallingHintComesFromTheModulesSpots() {
        val tables = FlexTables.parse(JSONObject(resource("flex-hello-dsp.json")).getJSONObject("tables"))
        // On 40 m in USB, FT8 is at WSJT-X's 7.074 in DIGU.
        assertEquals(FlexCalling("FT8", 7_074_000, "DIGU"), FlexCalling.hint("ft8", 7_150_000, "USB", tables))
        assertEquals(FlexCalling("FT4", 14_080_000, "DIGU"), FlexCalling.hint("ft4", 14_200_000, "USB", tables))
        // Already there: nothing to say.
        assertNull(FlexCalling.hint("ft8", 14_074_000, "DIGU", tables))
        // The plan's SSTV centre on 80 m, in LSB below 10 MHz.
        assertEquals(FlexCalling("SSTV", 3_735_000, "LSB"), FlexCalling.hint("sstv", 3_700_000, "LSB", tables))
        // No spot for JS8 or Q65 in the module's tables.
        assertNull(FlexCalling.hint("js8", 14_200_000, "USB", tables))
    }

    @Test fun goingToTheCallingFrequencyTunesAndSetsTheSideband() {
        val f = FlexStandIn(withDecoder("ft8")).start()
        fake = f
        val s = f.open()
        until("the hint") { s.ui.value.calling != null }
        assertEquals(FlexCalling("FT8", 14_074_000, "DIGU"), s.ui.value.calling)   // the hello's slice is on 14.2 USB
        s.goToCalling()
        val w = f.writes()
        assertEquals(listOf("slice.tune", "slice.mode"), w.map { it.getString("action") }.filter { it != "pan.frequency" })
        assertEquals(14.074, w.first().getJSONObject("arguments").getDouble("frequency"), 1e-9)
        assertEquals("DIGU", w.first { it.getString("action") == "slice.mode" }.getJSONObject("arguments").getString("mode"))
    }

    @Test fun sstvPaintsFromTheModulesEventsAndAPictureOpensThePanel() {
        val (f, s) = open()
        s.setSstv(true)
        assertEquals(listOf("""{"on":true}"""), f.writes("api/sstv").map { it.toString() })
        // The module now says SSTV is on: the panel opens; a picture arrives line by line.
        val on = JSONObject(f.hello).apply { getJSONObject("state").getJSONObject("sstv").put("on", true) }
        f.socket!!.send(on.put("type", "state").toString())
        until("the panel") { s.ui.value.panel.shown == DecoderPanel.SSTV }
        val frames = lines("flex-sstv.jsonl")
        frames.dropLast(1).forEach { f.socket!!.send(it) }
        until("three lines") { synchronized(s.sstv) { s.sstv.current?.lines } == 3 }
        val p = synchronized(s.sstv) { s.sstv.current!! }
        assertEquals("robot36", p.fmt)
        assertEquals(320 to 240, p.width to p.height)
        assertTrue(p.pixels[0] != SstvPicture.UNPAINTED)
        assertEquals(SstvPicture.UNPAINTED, p.pixels[320 * 3])
        f.socket!!.send(frames.last())
        until("done") { synchronized(s.sstv) { s.sstv.gallery.size } == 1 }
        // A finished picture sends for the module's list.
        until("the kept pictures") { s.ui.value.images.size == 2 }
    }

    @Test fun aLateJoinerGetsThePictureFromItsFirstLine() {
        // The module's replay straight after hello: start (partial), then every line so far.
        val replay = lines("flex-sstv-join.jsonl").drop(1)
        val (_, s) = open(replay)
        until("the replay") { synchronized(s.sstv) { s.sstv.current?.lines } == 2 }
        val p = synchronized(s.sstv) { s.sstv.current!! }
        assertTrue(p.name.endsWith("-partial.png"))
        assertEquals(1, s.ui.value.sstvStarts)
    }

    @Test fun anAbortedPictureIsNotKept() {
        val r = SstvReceiver()
        r.apply(SstvEvent.parse(JSONObject(lines("flex-sstv.jsonl").first()))!!)
        val name = r.current!!.name
        r.apply(SstvEvent.parse(JSONObject().put("type", "sstv").put("state", "done").put("name", name).put("aborted", true))!!)
        assertNull(r.current)
        assertTrue(r.gallery.isEmpty())
    }

    @Test fun keptPicturesAreFetchedRelativeToTheBase() {
        val (f, s) = open()
        val list = runBlocking { f.client().sstvImages() }
        assertEquals(2, list.size)
        assertEquals("api/sstv/images/20261010-074303-robot36.png", list.first().url)
        assertTrue(list[1].partial)
        val png = runBlocking { s.picture(list.first()) }
        assertNotNull(png)
        assertArrayEquals(FlexStandIn.PNG, png)
        assertTrue(f.got.contains("api/sstv/images/20261010-074303-robot36.png"))
        // Cached: asked once.
        runBlocking { s.picture(list.first()) }
        assertEquals(1, f.got.count { it == "api/sstv/images/20261010-074303-robot36.png" })
    }

    @Test fun anImageListNeverLeavesTheAppsBase() {
        val o = JSONObject("""{"images": [
            {"name": "a", "url": "https://elsewhere.example/a.png"},
            {"name": "b", "url": "/api/sstv/images/b.png"},
            {"name": "c", "url": "api/sstv/images/../../c.png"},
            {"name": "d", "url": "api/sstv/images/d.png", "width": 320, "height": 256}]}""")
        assertEquals(listOf("d"), FlexSstvImage.list(o).map { it.name })
    }

    @Test fun aModuleWithNoDecoderSaysWhy() {
        val (f, s) = open()
        f.replies += MockResponse().setResponseCode(503).setBody("""{"error": "This copy of Flex has no decoder.", "code": "decoders_unavailable"}""")
        s.setDecoder("ft4")
        until("said") { f.said.isNotEmpty() }
        assertEquals("This copy of Flex has no decoder.", f.said.single())
        assertTrue("crashed: ${f.crashes}", f.crashes.isEmpty())
    }
}
