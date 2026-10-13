package com.openhamshack.remote.shack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Band Activity's sections (shack/Decodes.kt, shack/BandActivity.kt): what a
 * band change or a big retune does to the list, what a small one does not,
 * the cap, and Clear. Brian's Tab S9 went 40 m to 20 m and 40 m's Greece,
 * Italy and Ukraine stayed in 20 m's list with nothing to say so (13/10/2026).
 */
class ActivitySectionsTest {
    private fun d(text: String, slot: Double, mode: String = "ft8", df: Int = 1000) = SlotDecode(mode, slot, -10, 0.1, df, text)

    /** A list that has been told the dial, as EngineSession does on every state. */
    private fun at(vararg dials: Triple<String, Double, Double>) =
        dials.fold(SlotDecodes()) { acc, (band, hz, now) -> acc.retune(band, hz, "ft8", now) }

    private val FT8_40 = Triple("40m", 7_074_000.0, 100.0)

    @Test fun `the first dial labels the list without making a second section`() {
        val l = SlotDecodes().retune("40m", 7_074_000.0, "ft8", 100.0)
        assertEquals(1, l.sections.size)
        assertEquals(SectionHead("40m", 7_074_000.0, "ft8", 100.0), l.sections[0].head)
    }

    @Test fun `a band change starts a new section and the old rows stay above, dimmed`() {
        var l = at(FT8_40).add(d("CQ SV1ABC KM17", 105.0)).add(d("CQ I2XYZ JN45", 105.0, df = 1500))
        l = l.retune("20m", 14_074_000.0, "ft8", 130.0).add(d("CQ W1AW FN31", 135.0))
        assertEquals(2, l.sections.size)
        assertEquals(listOf("CQ SV1ABC KM17", "CQ I2XYZ JN45"), l.sections[0].items.map { it.text })
        assertEquals(listOf("CQ W1AW FN31"), l.sections[1].items.map { it.text })
        assertEquals("20m", l.sections[1].head!!.band)
        assertEquals(14_074_000.0, l.sections[1].head!!.dialHz, 0.0)
        // All of it is still there to scroll, in order.
        assertEquals(3, l.items.size)

        val shown = BandSections.items(l, "M0TST", null)
        assertEquals(listOf("H", "L", "L", "H", "L"), shown.map { if (it is BandItem.Heading) "H" else "L" })
        assertEquals(listOf(true, true, true, false, false), shown.map { it.dimmed })
        // The new section's first row starts a slot even if the slot number repeats.
        assertTrue((shown[4] as BandItem.Line).row.startsSlot)
    }

    @Test fun `a retune within the passband is the same section, however often it creeps`() {
        var l = at(Triple("20m", 14_074_000.0, 100.0)).add(d("CQ W1AW FN31", 105.0))
        // FT8's window is 2.8 kHz: 2 kHz steps, each from the last, never split it.
        for (k in 1..5) l = l.retune("20m", 14_074_000.0 + 2_000.0 * k, "ft8", 110.0 + k)
        assertEquals(1, l.sections.size)
        // Ten kHz from where it began, but the list judges each step from the last: still one section.
        assertEquals(1, l.sections.size)
        // A single jump of that size is another matter.
        l = l.retune("20m", 14_074_000.0, "ft8", 130.0)
        assertEquals(2, l.sections.size)
    }

    @Test fun `a dial jump past the passband splits even when no band is named, as on NB`() {
        var l = at(Triple("", 10_489_540_000.0, 100.0)).add(d("CQ A1AA", 105.0))
        l = l.retune("", 10_489_540_000.0 + 2_000.0, "ft8", 110.0)
        assertEquals(1, l.sections.size)
        l = l.retune("", 10_489_540_000.0 + 5_000.0, "ft8", 120.0)
        assertEquals(2, l.sections.size)
        assertEquals("", l.sections[1].head!!.band)
    }

    @Test fun `the passband is the mode's own, 3 kHz for one with no window`() {
        assertEquals(2800.0, SlotDecodes.passbandHz("ft8"), 0.0)
        assertEquals(60.0, SlotDecodes.passbandHz("psk31"), 0.0)
        assertEquals(3000.0, SlotDecodes.passbandHz("mystery"), 0.0)
    }

    @Test fun `at most two sections are kept, the oldest dropped`() {
        var l = at(FT8_40).add(d("A", 105.0))
        l = l.retune("20m", 14_074_000.0, "ft8", 130.0).add(d("B", 135.0))
        l = l.retune("15m", 21_074_000.0, "ft8", 160.0).add(d("C", 165.0))
        assertEquals(2, l.sections.size)
        assertEquals(listOf("B", "C"), l.items.map { it.text })
        assertEquals(listOf("20m", "15m"), l.sections.map { it.head!!.band })
    }

    @Test fun `tuning about before anything is heard leaves no empty headings`() {
        var l = at(FT8_40)
        l = l.retune("20m", 14_074_000.0, "ft8", 110.0).retune("15m", 21_074_000.0, "ft8", 120.0)
        assertEquals(1, l.sections.size)
        assertEquals("15m", l.sections[0].head!!.band)
        // Likewise a band change after the list was cleared: one section, the newest heading.
        val heard = l.add(d("A", 125.0)).retune("10m", 28_074_000.0, "ft8", 140.0).retune("12m", 24_915_000.0, "ft8", 150.0)
        assertEquals(2, heard.sections.size)
        assertEquals("12m", heard.sections[1].head!!.band)
        assertTrue(heard.sections[1].items.isEmpty())
    }

    @Test fun `a heading keeps its time while the engine repeats the same dial`() {
        var l = at(FT8_40).add(d("A", 105.0)).retune("20m", 14_074_000.0, "ft8", 130.0)
        l = l.retune("20m", 14_074_000.0, "ft8", 145.0).retune("20m", 14_074_000.0, "ft8", 160.0)
        assertEquals(130.0, l.sections[1].head!!.since, 0.0)
    }

    @Test fun `a decode of a slot that began before the retune belongs to the band before`() {
        var l = at(FT8_40).add(d("A", 105.0)).retune("20m", 14_074_000.0, "ft8", 130.0)
        // The engine decodes the 120 s slot after it has ended, once we have left.
        l = l.add(d("LATE 40 m", 120.0)).add(d("NEW 20 m", 135.0))
        assertEquals(listOf("A", "LATE 40 m"), l.sections[0].items.map { it.text })
        assertEquals(listOf("NEW 20 m"), l.sections[1].items.map { it.text })
    }

    @Test fun `a new mode starts the list again under the heading it had, with the new mode`() {
        val l = at(FT8_40).add(d("A", 105.0)).add(d("B", 120.0, mode = "ft4"))
        assertEquals(listOf("B"), l.items.map { it.text })
        assertEquals("ft4", l.sections.single().head!!.mode)
        assertEquals("40m", l.sections.single().head!!.band)
    }

    @Test fun `the row cap counts across sections and drops the oldest first`() {
        var l = SlotDecodes(max = 3).retune("40m", 7_074_000.0, "ft8", 100.0)
        l = l.add(d("A", 105.0)).add(d("B", 105.0, df = 2000)).retune("20m", 14_074_000.0, "ft8", 130.0)
        l = l.add(d("C", 135.0)).add(d("D", 135.0, df = 2000))
        assertEquals(listOf("B", "C", "D"), l.items.map { it.text })
        assertEquals(2, l.sections.size)
    }

    @Test fun `Clear empties the list and keeps the heading, and nothing else changes`() {
        var l = at(FT8_40).add(d("A", 105.0)).retune("20m", 14_074_000.0, "ft8", 130.0).add(d("B", 135.0))
        val cleared = l.cleared()
        assertTrue(cleared.items.isEmpty())
        assertEquals(1, cleared.sections.size)
        assertEquals("20m", cleared.sections[0].head!!.band)
        // The original is untouched (a list is a value).
        assertEquals(2, l.items.size)
        // The next decode lands under the heading, and a small retune does not split it.
        l = cleared.retune("20m", 14_075_000.0, "ft8", 150.0).add(d("C", 165.0))
        assertEquals(listOf("C"), l.items.map { it.text })
        assertEquals(1, l.sections.size)
        // A band change after Clear still starts a section once something is heard.
        l = l.retune("15m", 21_074_000.0, "ft8", 170.0)
        assertEquals(2, l.sections.size)
    }

    @Test fun `Clear on an empty list, or one never told its dial, is harmless`() {
        assertTrue(SlotDecodes().cleared().items.isEmpty())
        val flex = SlotDecodes().add(d("A", 105.0)).cleared()
        assertNull(flex.sections.single().head)
        assertTrue(BandSections.items(flex, "", null).isEmpty())
    }

    @Test fun `a list never told its dial behaves as it always did, with no headings`() {
        val l = SlotDecodes().add(d("A", 15.0)).add(d("B", 30.0))
        assertEquals(1, l.sections.size)
        val shown = BandSections.items(l, "", null)
        assertTrue(shown.all { it is BandItem.Line && !it.dimmed })
        assertEquals(listOf(true, true), shown.map { (it as BandItem.Line).row.startsSlot })
    }

    @Test fun `a band reads with its unit apart`() {
        assertEquals("20 m", BandSections.bandText("20m"))
        assertEquals("70 cm", BandSections.bandText("70cm"))
        assertEquals("", BandSections.bandText(""))
        assertFalse(BandSections.bandText("2m").contains("2m"))
    }
}
