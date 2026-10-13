package com.openhamshack.remote.shack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.openhamshack.core.decode.Cty

/**
 * Band Activity's rules (shack/BandActivity.kt): the order rows come in,
 * how each stands out, where its country comes from, which can be
 * answered, and which columns fit a width - what the tablet, phone and TV
 * lists draw.
 */
class BandActivityTest {
    // A two-entity cty.dat: enough to tell the lookup was used, and by whom.
    private val cty = Cty.parse(
        "England:  14:  27:  EU:   52.77:     1.47:     0.0:  G:\n    G,M;\n" +
            "Canary Islands:  33:  36:  AF:   28.32:    15.85:     0.0:  EA8:\n    EA8;\n",
    )

    private fun d(text: String, slot: Double = 15.0, df: Int = 1000, country: String = "", tx: Boolean = false, call: String = "") =
        SlotDecode("ft8", slot, if (tx) 0 else -10, 0.1, df, text, call = call, country = country, tx = tx)

    private fun list(vararg ds: SlotDecode) = ds.fold(SlotDecodes()) { acc, x -> acc.add(x) }

    @Test fun `rows go oldest slot first, by frequency within a slot, our over at the head of its slot`() {
        val decodes = list(
            d("CQ G4XYZ IO91", slot = 30.0, df = 1500),
            d("CQ EA8BFK IL18", slot = 15.0, df = 900),
            d("EA8BFK M0TST IO75", slot = 30.0, df = 900, tx = true),
            d("G4XYZ EA8BFK IL18", slot = 30.0, df = 400),
        )
        val rows = BandActivity.rows(decodes, "M0TST", cty)
        assertEquals(listOf("CQ EA8BFK IL18", "EA8BFK M0TST IO75", "G4XYZ EA8BFK IL18", "CQ G4XYZ IO91"), rows.map { it.decode.text })
        assertEquals(listOf(true, true, false, false), rows.map { it.startsSlot })
    }

    @Test fun `ours is TX, a message naming us beats a CQ, a CQ is a CQ`() {
        assertEquals(Emphasis.TX, BandActivity.emphasis(d("CQ M0TST IO75", tx = true), "M0TST"))
        assertEquals(Emphasis.MINE, BandActivity.emphasis(d("M0TST EA8BFK -12"), "M0TST"))
        assertEquals(Emphasis.MINE, BandActivity.emphasis(d("M0TST/P EA8BFK -12"), "m0tst"))
        assertEquals(Emphasis.CQ, BandActivity.emphasis(d("CQ DX OH8ZZ KP24"), "M0TST"))
        assertEquals(Emphasis.NONE, BandActivity.emphasis(d("G4XYZ EA8BFK RR73"), "M0TST"))
        // Our own over naming the DX is not "to us": its text has our call in it.
        assertFalse(d("EA8BFK M0TST R-07", tx = true).mentions("M0TST"))
    }

    @Test fun `the server's country wins, else the sender is looked up, else nothing`() {
        // The server read the operator's own cty.dat: believed over ours.
        assertEquals("Somewhere Else", BandActivity.country(d("CQ G4XYZ IO91", country = "Somewhere Else"), cty))
        assertEquals("England", BandActivity.country(d("CQ G4XYZ IO91"), cty))
        assertEquals("Canary Islands", BandActivity.country(d("G4XYZ EA8BFK -05"), cty))
        assertEquals("Canary Islands", BandActivity.country(d("CQ EA8/G4XYZ"), cty))
        assertEquals("England", BandActivity.country(d("whatever", call = "M0ABC"), cty))
        assertEquals("", BandActivity.country(d("TNX FER QSO 73"), cty))
        assertEquals("", BandActivity.country(d("CQ VK2ABC QF56"), cty))     // not in this table
        assertEquals("", BandActivity.country(d("CQ G4XYZ IO91"), null))     // the table not loaded yet
        assertEquals("", BandActivity.country(d("CQ M0TST IO75", tx = true), cty))
    }

    @Test fun `only a station heard with a call can be answered`() {
        val rows = BandActivity.rows(list(d("CQ G4XYZ IO91"), d("CQ M0TST IO75", slot = 30.0, tx = true), d("TNX 73", slot = 30.0, df = 2000)),
            "M0TST", cty)
        assertEquals(listOf(true, false, false), rows.map { it.answerable })
    }

    @Test fun `a tx event is our over, and an older client's parse never sees it as a decode`() {
        val ev = EngineEvent.parse("""{"kind": "events", "mode": "ft8", "t": 1.0, "tx": {"slot": 45.0, "df": 1234, "text": "EA8BFK M0TST R-07"}}""")
        val dec = (ev as EngineEvent.Slot).decode
        assertTrue(dec.tx)
        assertEquals(Triple(45.0, 1234, "EA8BFK M0TST R-07"), Triple(dec.slot, dec.df, dec.text))
        assertEquals(EngineEvent.Other, EngineEvent.parse("""{"kind": "events", "mode": "ft8", "tx": {"df": 1}}"""))
        val heard = EngineEvent.parse("""{"kind": "events", "mode": "ft8", "slot": 30.0, "snr": -3, "df": 900, "text": "CQ EA8BFK IL18",
            "call": "EA8BFK", "country": "Canary Islands", "dxcc": "EA8", "continent": "AF"}""") as EngineEvent.Slot
        assertEquals("Canary Islands", heard.decode.country)
        assertFalse(heard.decode.tx)
    }

    @Test fun `columns drop DT, then Freq, then the time, then Country as the width shrinks`() {
        assertEquals(BandColumns.ALL, BandColumns.forWidth(70f))
        assertEquals(BandColumns(time = true, dt = false, freq = true, country = true), BandColumns.forWidth(54f))
        assertEquals(BandColumns(time = true, dt = false, freq = false, country = true), BandColumns.forWidth(48f))
        assertEquals(BandColumns(time = false, dt = false, freq = false, country = true), BandColumns.forWidth(40f))
        // A TV's docked list at 18 sp: still room for the country.
        assertEquals(BandColumns(time = false, dt = false, freq = false, country = true), BandColumns.forWidth(33.5f))
        assertEquals(BandColumns(time = false, dt = false, freq = false, country = false), BandColumns.forWidth(30f))
    }
}
