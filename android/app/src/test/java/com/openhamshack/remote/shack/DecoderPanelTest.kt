package com.openhamshack.remote.shack

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.openhamshack.remote.shack.DecoderPanel.NONE
import com.openhamshack.remote.shack.DecoderPanel.SLOTS
import com.openhamshack.remote.shack.DecoderPanel.SSTV
import com.openhamshack.remote.shack.DecoderPanel.TEXT

/**
 * Decoder panels opening and closing by themselves as the engine's mode
 * changes (OpenWebRX+'s behaviour), and the bookmarks they are reached by,
 * against the memories `python3 -m ohsnb --server` and `-m ohsradio --server`
 * (openhamshack-nb 8498fe9, simulators) answered through tools/fakeshack.py on
 * 9 October 2026.
 */
class DecoderPanelTest {
    private fun text(name: String) = javaClass.classLoader!!.getResourceAsStream("shack/$name")!!.bufferedReader().readText()

    // --- which panel a mode has ---------------------------------------------

    @Test fun everyModeTheEnginesOfferHasTheRightPanel() {
        // NB's api/spec mode choices, as recorded.
        val nb = JSONObject(text("nb-spec.json")).getJSONObject("mode").getJSONArray("choices")
        val got = (0 until nb.length()).associate { nb.getString(it) to DecoderPanel.of(nb.getString(it)) }
        for (m in listOf("ft8", "ft4", "q65", "js8")) assertEquals(m, SLOTS, got[m])
        for (m in listOf("cw", "rtty", "psk31", "psk63", "psk125")) assertEquals(m, TEXT, got[m])
        assertEquals(SSTV, got["sstv"])
        // Voice modes and FreeDV (whose decoder is the audio itself) have none.
        for (m in listOf("usb", "lsb", "am", "freedv")) assertEquals(m, NONE, got[m])
    }

    @Test fun aDecoderThisBuildDoesNotKnowIsLearntFromItsEvents() {
        val wspr = EngineEvent.parse("""{"kind": "events", "mode": "wspr", "slot": 1760000000.0, "snr": -20, "df": 1500, "text": "G4ABC IO80 37"}""")
        assertEquals(NONE, DecoderPanel.of("wspr"))
        val (mode, panel) = DecoderPanel.ofEvent(wspr)!!
        assertEquals(SLOTS, DecoderPanel.of("wspr", mapOf(mode to panel)))
        // Lines of a toast or the scope say nothing about a decoder.
        assertNull(DecoderPanel.ofEvent(EngineEvent.Toast("hello")))
        // A recorded CW event is a text decoder's.
        val cw = text("nb-events-cw.txt").lines().first { it.isNotBlank() }
        assertEquals("cw" to TEXT, DecoderPanel.ofEvent(EngineEvent.parse(cw)))
    }

    // --- opening and closing by themselves -----------------------------------

    @Test fun choosingADecoderOpensItsPanelAndAVoiceModeClosesIt() {
        var p = PanelState()
        p = p.decoder(DecoderPanel.of("ft8"), autoOpen = true)
        assertEquals(SLOTS, p.shown)
        p = p.decoder(DecoderPanel.of("cw"), autoOpen = true)
        assertEquals("FT8 to CW swaps the list for the text", TEXT, p.shown)
        p = p.decoder(DecoderPanel.of("usb"), autoOpen = true)
        assertEquals(NONE, p.shown)
        assertEquals(NONE, p.active)
    }

    @Test fun aPanelClosedByHandStaysClosedUntilTheDecoderChanges() {
        var p = PanelState().decoder(SLOTS, autoOpen = true).close()
        assertEquals(NONE, p.shown)
        // Every state message (a dial move, a meter) repeats the mode: not a reason to reopen.
        p = p.decoder(DecoderPanel.of("ft4"), autoOpen = true)
        assertEquals(NONE, p.shown)
        p = p.decoder(TEXT, autoOpen = true)
        assertEquals(TEXT, p.shown)
        // And OK row's "open" brings back the running decoder's panel.
        assertEquals(TEXT, p.close().open().shown)
    }

    @Test fun withAutoOpenOffOnlyAPanelOpenedByHandFollowsTheMode() {
        var p = PanelState().decoder(SLOTS, autoOpen = false)
        assertEquals(SLOTS, p.active)
        assertEquals(NONE, p.shown)
        p = p.open()
        assertEquals(SLOTS, p.shown)
        p = p.decoder(TEXT, autoOpen = false)
        assertEquals("kept open through a change of decoder", TEXT, p.shown)
        p = p.decoder(NONE, autoOpen = false).decoder(SLOTS, autoOpen = false)
        assertEquals("a voice mode closed it; the next decoder does not reopen it", NONE, p.shown)
        assertEquals(NONE, PanelState().open().shown)
    }

    @Test fun anSstvPictureStartingReopensThePictureOnlyWhenAutoOpenIsOn() {
        val closed = PanelState().decoder(SSTV, autoOpen = true).close()
        assertEquals(SSTV, closed.sstvStarted(autoOpen = true).shown)
        assertEquals(NONE, closed.sstvStarted(autoOpen = false).shown)
        // Not while another decoder runs (a stray picture event is not SSTV mode).
        assertEquals(TEXT, PanelState().decoder(TEXT, true).sstvStarted(true).shown)
    }

    // --- bookmarks -----------------------------------------------------------

    @Test fun nbBookmarksAreTheEnginesPresetsInFrequencyOrder() {
        val b = Bookmark.parseList(JSONObject(text("nb-memories.json")))
        assertEquals(12, b.size)
        assertEquals(b.sortedBy { it.hz }, b)
        val ft8 = b.single { it.name == "FT8" }
        assertEquals(10_489_540_000.0, ft8.hz, 0.0)
        assertEquals("ft8", ft8.mode)
        assertEquals(SLOTS, DecoderPanel.of(ft8.mode))
        assertTrue(b.all { it.builtin })
    }

    @Test fun radioBookmarksCarryEachBandsFt8AndSstvCentres() {
        val b = Bookmark.parseList(JSONObject(text("radio-memories.json")))
        val ft8_20 = b.single { it.name == "FT8 20m" }
        assertEquals(14_074_000.0, ft8_20.hz, 0.0)
        assertTrue(b.any { it.mode == "sstv" && it.hz == 14_230_000.0 })
        // A memory with no frequency or no name is left out, not drawn at 0 Hz.
        assertEquals(1, Bookmark.parseList(JSONObject("""{"memories": [{"name": "x"}, {"rf": 1.0}, {"name": "y", "rf": 7.0e6, "mode": "cw"}]}""")).size)
    }

    @Test fun theRowShowsTheBookmarksOnTheScopeElseTheNearest() {
        val b = Bookmark.parseList(JSONObject(text("radio-memories.json")))
        // 20 m on the scope: its bookmarks, in frequency order.
        val on = Bookmarks.inView(b, 14_000_000.0, 14_350_000.0, 14_074_000.0)
        assertTrue(on.isNotEmpty() && on.all { it.hz in 14_000_000.0..14_350_000.0 })
        assertEquals("FT8 20m", on[Bookmarks.nearest(on, 14_074_100.0)].name)
        // Between bands nothing is on the scope: the six nearest the dial instead.
        val near = Bookmarks.inView(b, 12_000_000.0, 12_100_000.0, 12_050_000.0)
        assertEquals(6, near.size)
        assertEquals(near.sortedBy { it.hz }, near)
        assertTrue(near.any { it.hz == 10_136_000.0 } || near.any { it.hz == 14_055_000.0 })
        assertEquals(-1, Bookmarks.nearest(emptyList(), 1.0))
    }

    @Test fun theBookmarkTheRadioIsOnIsKnown() {
        val b = Bookmark.parseList(JSONObject(text("nb-memories.json")))
        assertEquals("FT8", Bookmarks.current(b, 10_489_540_020.0, "ft8")?.name)
        assertNull("the same dial in another mode is not on it", Bookmarks.current(b, 10_489_540_000.0, "usb"))
        assertNull(Bookmarks.current(b, null, "ft8"))
    }
}
