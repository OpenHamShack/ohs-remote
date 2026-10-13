package com.openhamshack.kind.stationflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.openhamshack.kind.stationflow.api.RadioState
import com.openhamshack.kind.stationflow.radio.FACES
import com.openhamshack.kind.stationflow.radio.Face
import com.openhamshack.kind.stationflow.radio.KEY_ACTIONS
import com.openhamshack.kind.stationflow.radio.KeyAction
import com.openhamshack.kind.stationflow.radio.MarkAnchor
import com.openhamshack.kind.stationflow.radio.MeterStyle
import com.openhamshack.kind.stationflow.radio.Px
import com.openhamshack.kind.stationflow.radio.AMP_GAP_K
import com.openhamshack.kind.stationflow.radio.allKeys
import com.openhamshack.kind.stationflow.radio.ampTextSize
import com.openhamshack.kind.stationflow.radio.ampWords
import com.openhamshack.kind.stationflow.radio.barMarks
import com.openhamshack.kind.stationflow.radio.barParts
import com.openhamshack.kind.stationflow.radio.compactOf
import com.openhamshack.kind.stationflow.radio.dialRect
import com.openhamshack.kind.stationflow.radio.faceFor
import com.openhamshack.kind.stationflow.radio.fitFace
import com.openhamshack.kind.stationflow.radio.faceKeys
import com.openhamshack.kind.stationflow.radio.keyRects
import com.openhamshack.kind.stationflow.radio.meterFraction
import com.openhamshack.kind.stationflow.radio.pageCount
import com.openhamshack.kind.stationflow.radio.readoutRoom
import com.openhamshack.kind.stationflow.radio.readoutScale
import com.openhamshack.kind.stationflow.radio.splitFrequency
import com.openhamshack.kind.stationflow.radio.stepDigitFromRight
import com.openhamshack.kind.stationflow.radio.FaceSpot
import com.openhamshack.kind.stationflow.radio.spotRect

class FacesTest {
    private val nine = FACES.getValue("ic-9700")

    @Test fun `each radio gets its own face, and anything else the plain layout`() {
        assertEquals("ic-9700", faceFor("ic-9700")!!.key)
        assertEquals("ic-7610", faceFor("IC-7610")!!.key)
        assertEquals("ic-705", faceFor("ic-705")!!.key)
        // The IC-7600 is in the module's table but has no face: never a 9700's by default.
        assertNull(faceFor("ic-7600"))
        assertNull(faceFor(null))
    }

    @Test fun `the model key comes through from the state`() {
        val s = RadioState.parse(fixture("tvapi-state-example.json"))
        assertEquals("ic-9700", s.modelKey)
        assertNotNull(faceFor(s.modelKey))
    }

    @Test fun `the frequency reads as the radio prints it`() {
        assertEquals("144.174.00" to "", splitFrequency(144_174_000, nine))
        assertEquals("1296.200.00" to "", splitFrequency(1_296_200_000, nine))
        assertEquals("14.074.00" to "", splitFrequency(14_074_000, FACES.getValue("ic-7610")))
        // Unknown is dashes in the same shape, never a zero frequency.
        assertEquals("---.---.--" to "", splitFrequency(null, nine))
        // The 1 Hz readout the manual shows as "146.520.00" with a small last digit.
        val fine = nine.copy(resolutionHz = 1, smallDigits = 1)
        assertEquals("146.520.00" to "0", splitFrequency(146_520_000, fine))
    }

    @Test fun `the step mark sits over the digit the step moves`() {
        assertEquals(0, stepDigitFromRight(10, nine))       // 10 Hz: the last digit
        assertEquals(1, stepDigitFromRight(100, nine))
        assertEquals(2, stepDigitFromRight(1_000, nine))    // 1 kHz: where the radio's own mark sits
        assertEquals(2, stepDigitFromRight(5_000, nine))
        assertEquals(3, stepDigitFromRight(12_500, nine))   // 12.5 kHz: its leading 1, the 10 kHz digit
        assertNull(stepDigitFromRight(1, nine))             // finer than the readout: no mark
    }

    @Test fun `the meter puts S9 and S9+60 where the radio does`() {
        assertEquals(0f, meterFraction(0, 0.5f)!!, 1e-6f)
        assertEquals(0.5f, meterFraction(120, 0.5f)!!, 1e-6f)
        assertEquals(1f, meterFraction(241, 0.5f)!!, 1e-6f)
        assertEquals(1f, meterFraction(255, 0.5f)!!, 1e-6f)
        // No reading is no bar, not an empty one that reads as S0.
        assertNull(meterFraction(null, 0.5f))
    }

    @Test fun `no key on any face, page or phone layout can transmit`() {
        // Settings opens a sheet of receive settings (P.AMP/ATT, the scope, NR2, mute): Icom TV-API.md
        // sections 6, 7a and 8a, all Control level, none about transmit.
        val safe = setOf(KeyAction.Page, KeyAction.Mode, KeyAction.Filter, KeyAction.Band, KeyAction.Vfo,
            KeyAction.Tune, KeyAction.Settings, KeyAction.Decodes, KeyAction.Sstv)
        // KeyAction itself is the allow-list: a new member must be added here deliberately.
        assertEquals(safe, KeyAction.entries.toSet())
        allKeys().forEach { k ->
            assertTrue("${k.label} is ${k.action}", k.action in safe)
            assertTrue("${k.label} reads like a transmit key",
                listOf("TX", "PTT", "TRANSMIT", "TUNE", "POWER", "VOX").none { k.label.uppercase().contains(it) })
        }
    }

    private val both = RadioState(preamp = 0, att = 0, preamps = listOf("P.AMP"), attenuators = listOf(10))

    @Test fun `every action is on some page of every face`() {
        FACES.values.forEach { f ->
            val shown = (0 until pageCount(f, both)).flatMap { faceKeys(f, it, both) }.map { it.action }.toSet()
            KEY_ACTIONS.forEach { assertTrue("${f.key} lacks ${it.label}", it.action in shown) }
            assertEquals(f.keysPerPage, faceKeys(f, 0, both).size)
        }
    }

    @Test fun `SET is on the first page, and P_AMP, ATT and MUTE have moved into it`() {
        // Brian (10/10/2026): a SETTINGS key for the advanced controls, which leave the key bar.
        assertEquals(listOf("< 1 >", "MODE", "FIL", "BAND", "SET"), faceKeys(nine, 0, both).map { it.label })
        assertEquals(listOf("< 2 >", "A/B", "F-INP", "DECODE", "SSTV"), faceKeys(nine, 1, both).map { it.label })
        assertEquals(2, pageCount(nine, both))
        val labels = allKeys().map { it.label }.toSet()
        assertTrue(labels.toString(), listOf("P.AMP", "ATT", "MUTE").none { it in labels })
    }

    @Test fun `the mode line says what the front end is set to, only while on`() {
        assertEquals(emptyList<String>(), ampWords(both))
        assertEquals(listOf("P.AMP", "ATT"), ampWords(both.copy(preamp = 1, att = 10)))
        assertEquals(listOf("ATT"), ampWords(both.copy(preamp = null, att = 10)))
        val seven = RadioState(preamp = 2, preamps = listOf("P.AMP1", "P.AMP2"))
        assertEquals(listOf("P.AMP2"), ampWords(seven))
        // A 9700's external preamp: the badge names the whole setting. EXT alone used to draw nothing
        // (the internal level is 0), and both read as plain P.AMP (Brian, 10/10/2026).
        val modes = both.copy(preampModes = listOf("OFF", "P.AMP", "EXT", "P.AMP+EXT"))
        assertEquals(listOf("EXT"), ampWords(modes.copy(preamp = 0, preampMode = 2)))
        assertEquals(listOf("P.AMP+EXT", "ATT"), ampWords(modes.copy(preamp = 1, preampMode = 3, att = 10)))
        assertEquals(emptyList<String>(), ampWords(modes.copy(preamp = 0, preampMode = 0)))
    }

    @Test fun `every selectable spot is on the screen and none overlaps another`() {
        val spots = listOf(FaceSpot.Dial, FaceSpot.Mode, FaceSpot.Filter, FaceSpot.Amp, FaceSpot.Vfo, FaceSpot.Scope)
        (FACES.values + FACES.values.map { compactOf(it, 0.48f) } + fitted()).forEach { f ->
            val rects = spots.mapNotNull { s -> spotRect(f, s)?.let { s to it } }
            // Every face has all six: the 7610 its P.AMP/ATT tiles for Amp.
            assertEquals(f.key, spots.size, rects.size)
            rects.forEach { (s, r) -> assertInside(f, r); assertTrue("${f.key} $s", r.w > 0 && r.h > 0) }
            rects.forEachIndexed { i, (a, ra) ->
                rects.drop(i + 1).forEach { (b, rb) -> assertTrue("${f.key}: $a $ra overlaps $b $rb", !overlaps(ra, rb)) }
            }
            // The keys are spots too, at the key bar's rectangles.
            assertEquals(keyRects(f).first(), spotRect(f, FaceSpot.Key(0)))
        }
    }
    @Test fun `P_AMP and ATT on the mode line overlap nothing, even beside 1300_000_00`() {
        // The 9700 and 705 print them right of the filter (Basic Manual p. 1-5, item 21). Checked against
        // everything else in the band block, with the readout at its widest - 1300.000.00, the top of
        // the 9700's 23 cm band, which is shrunk to fit - and the tuning-step mark over its digits.
        val faces = FACES.values.filter { it.band.amp != null }
        assertEquals(setOf("ic-9700", "ic-705"), faces.map { it.key }.toSet())
        (faces + faces.map { compactOf(it, 0.48f) }).forEach { f ->
            val b = f.band
            val amp = b.amp!!
            fun width(s: String, size: Float) = s.sumOf { (if (it.isDigit()) 0.5625 else 0.27) * size.toDouble() }.toFloat()
            val (wide, _) = splitFrequency(1_300_000_000, f)
            val k = readoutScale(width(wide, b.freqSize), readoutRoom(f))
            val size = b.freqSize * k
            val left = b.freqRight - width(wide, b.freqSize) * k
            // From the top of the step mark (it sits 0.99 of the digits' size above their baseline) down.
            val digits = Px(left, b.freqBaseline - size * 0.99f, b.freqRight - left, size * 0.99f)
            // The 2 m readout at the face's full size reaches higher, so that is checked too.
            val full = Px(b.dial.x, b.freqBaseline - b.freqSize * 0.99f, readoutRoom(f), b.freqSize * 0.99f)
            val vfoW = textW("VFO A", b.labelSize)
            val vfo = Px(b.vfoRight - vfoW, b.vfoTop, vfoW, b.labelSize * 1.2f)
            val split = Px(b.vfoRight - textW("SPLIT", b.labelSize), b.vfoTop + b.labelSize * 1.4f,
                textW("SPLIT", b.labelSize), b.labelSize * 1.2f)
            listOf("1300.000.00" to digits, "a 2 m readout" to full, "meter" to b.meter, "mode" to b.mode,
                "filter" to b.filter, "VFO label" to vfo, "SPLIT" to split,
                "dial (the TV's focus ring)" to b.dial).forEach { (name, r) ->
                assertTrue("${f.key}: P.AMP/ATT $amp overlaps the $name $r", !overlaps(amp, r))
            }
            // Inside the band block, on the radio's screen, right of the filter.
            assertTrue(f.key, amp.x >= b.filter.right && amp.bottom <= f.main.h && amp.right <= f.main.w)
            assertInside(f, amp.shift(f.main.x, f.main.y))
            // And the longest pair any radio prints fits in it: the 9700's P.AMP+EXT with ATT, the 705's
            // P.AMP2 with ATT.
            val ts = ampTextSize(amp)
            val longest = if (f.key.startsWith("ic-9700")) "P.AMP+EXT" else "P.AMP2"
            val words = textW(longest, ts) + ts * AMP_GAP_K + textW("ATT", ts)
            assertTrue("${f.key}: $words px of words in ${amp.w}", words <= amp.w)
        }
    }

    @Test fun `every region is on the radio's screen, at the radio's size and on a phone`() {
        val faces = FACES.values + FACES.values.map { compactOf(it, 0.48f) } + fitted()
        faces.forEach { f ->
            val regions = listOf(f.status, f.txBox, f.main, f.scopeHeader, f.spectrum, f.waterfall, f.ruler,
                f.keys, dialRect(f)) + keyRects(f) + listOfNotNull(f.tileColumn)
            regions.forEach { r -> assertInside(f, r) }
            // Top to bottom in the radio's order: bands, then the scope, then the keys.
            assertTrue(f.main.y < f.scopeHeader.y && f.scopeHeader.y < f.spectrum.y)
            assertTrue(f.spectrum.y < f.waterfall.y && f.waterfall.y < f.ruler.y && f.ruler.y < f.keys.y)
        }
    }

    @Test fun `the phone layout keeps the radio's top half and shows every key at once`() {
        val c = compactOf(nine, 0.48f)
        assertEquals(nine.main, c.main)
        assertTrue(c.spectrum.h > nine.spectrum.h)
        assertEquals(KEY_ACTIONS.map { it.action }, faceKeys(c, 0, both).map { it.action })
        assertEquals(1, pageCount(c, both))
    }

    @Test fun `the band block and the scope have the room the sub band had`() {
        // No sub band, by decision: nothing is left empty between the band and the scope where it was
        // (the 9700's sub block was 64 px). The 7610's own screen has 13 px there, which it keeps.
        val faces = FACES.values + FACES.values.map { compactOf(it, 0.48f) } + fitted()
        faces.forEach { f -> assertTrue("${f.key}: a gap above the scope", f.scopeHeader.y - f.main.bottom <= 16f) }
        // The 7610's single band runs from its tiles to the right edge, not across half the screen.
        val seven = FACES.getValue("ic-7610")
        assertEquals(seven.w, seven.main.right, 0.5f)
        assertTrue(seven.main.x >= seven.tileColumn!!.right)
        // Its frequency has the width the sub band left: right of the VFO/mode/filter line, not under it.
        assertTrue(dialRect(seven).x > seven.band.filter.right + seven.main.x)
    }

    @Test fun `the frequency's room is clear of the meter and badges on every face`() {
        val faces = FACES.values + FACES.values.map { compactOf(it, 0.48f) } + fitted()
        faces.forEach { f ->
            val b = f.band
            // Where the digits can be: the readout's width, from its baseline up the height of a figure
            // (Roboto's are 0.71 em; 0.75 for margin). The dial, the touch area, is taller than that and
            // on the IC-705 reaches up beside the filter badge, which is fine for a touch.
            val digits = Px(b.dial.x, b.freqBaseline - b.freqSize * 0.75f, readoutRoom(f), b.freqSize * 0.75f)
            listOf("meter" to b.meter, "mode" to b.mode, "filter" to b.filter).forEach { (name, r) ->
                assertTrue("${f.key}: the frequency's room $digits overlaps the $name $r", !overlaps(digits, r))
            }
            assertTrue(f.key, b.freqRight in b.dial.x..b.dial.right)
            assertTrue(f.key, readoutRoom(f) > 0f)
        }
    }

    @Test fun `a 23 cm frequency is shrunk to fit beside the 9700's meter, and 2 m and 70 cm are not`() {
        // The readout is measured with the real font when it is drawn; here a width model stands in for
        // it: Roboto Bold's tabular figures are 1152/2048 em and its full stop 0.27 em. The 1300 MHz
        // readout is the widest the 9700 can show (its 23 cm band ends there).
        fun width(s: String, size: Float) = s.sumOf { (if (it.isDigit()) 0.5625 else 0.27) * size.toDouble() }.toFloat()
        val b = nine.band
        val room = readoutRoom(nine)
        val (wide, _) = splitFrequency(1_300_000_000, nine)
        val natural = width(wide, b.freqSize)
        // At the face's own size it would not fit: this is the leading 1 Brian saw under the S meter.
        assertTrue(natural > room)
        val k = readoutScale(natural, room)
        assertTrue(k < 1f)
        val left = b.freqRight - natural * k
        assertTrue("left edge $left", left >= b.dial.x - 0.01f)
        assertTrue("runs under the meter", left >= b.meter.right)
        // The bands with three MHz digits keep the radio's own size.
        listOf(146_520_000L, 433_000_000L, 439_999_990L).forEach { hz ->
            val (big, _) = splitFrequency(hz, nine)
            assertEquals(1f, readoutScale(width(big, b.freqSize), room), 0f)
        }
    }

    // Text widths for the meter's labels, from Roboto's advance widths (em): its digits are 1152/2048,
    // and the rest near enough for a layout test. The meter draws with the system sans-serif, measured
    // at draw time; this stands in for it as the 23 cm test's model does for the readout.
    private fun textW(s: String, size: Float) = s.sumOf { c ->
        when {
            c.isDigit() -> 0.5625; c == '+' -> 0.57; c == '%' -> 0.73; c == '\u2022' -> 0.35; else -> 0.62
        } * size.toDouble()
    }.toFloat()

    /** The printed meter, left of its label to the bar's end, in the face's pixels. */
    private fun printedMeter(f: Face, tx: Boolean = false): Pair<Float, Float> {
        val p = barParts(f, f.band.meter.shift(f.main.x, f.main.y))
        return p.labelRight - textW(if (tx) "Po" else "S", p.size) to p.barX + p.barW
    }

    @Test fun `the 9700's meter and badges are centred between the screen's edge and the frequency box`() {
        // Brian, 10 October 2026, on the Fire TV: the meter sat against the box with room to its left.
        listOf(nine, compactOf(nine, 0.48f)).forEach { f ->
            val room = f.main.x to dialRect(f).x
            val mid = (room.first + room.second) / 2
            val (left, right) = printedMeter(f)
            assertEquals("meter $left..$right in $room", mid, (left + right) / 2, 3f)
            val b = f.band
            assertEquals("badges", mid, f.main.x + (b.mode.x + b.filter.right) / 2, 3f)
            // Longer: it fills the room rather than the right-hand two thirds of it.
            assertTrue("meter $left..$right", right - left >= 0.9f * (room.second - room.first))
            // While transmitting the wider "Po" stays on the screen and clear of the box.
            val (txLeft, _) = printedMeter(f, tx = true)
            assertTrue("Po at $txLeft", txLeft >= f.main.x - 0.5f)
            assertTrue(right < room.second)
        }
    }

    @Test fun `the 9700's meter reads 1 3 5 7 9 and then +20 +40 +60`() {
        assertEquals(listOf("1", "3", "5", "7", "9", "+20", "+40", "+60"), barMarks(nine, tx = false).map { it.label })
        assertEquals(listOf(false, false, false, false, false, true, true, true), barMarks(nine, tx = false).map { it.red })
    }

    @Test fun `a bar meter's printed scale fits over its bar without one label touching the next`() {
        val bars = FACES.values.filter { it.meterStyle == MeterStyle.Bar }
        assertTrue(bars.size >= 2)
        (bars + bars.map { compactOf(it, 0.48f) }).forEach { f ->
            listOf(false, true).forEach { tx ->
                val p = barParts(f)
                val spans = barMarks(f, tx).map { m ->
                    val w = textW(m.label, p.size * m.sizeK)
                    val x = p.barX + m.at * p.barW
                    Triple(m.label, if (m.anchor == MarkAnchor.Right) x - w else x - w / 2, if (m.anchor == MarkAnchor.Right) x else x + w / 2)
                }
                // The 9700's own a clear pixel apart; the IC-705's full scale is the radio's, whose "+40" and
                // "+60dB" all but touch at 480 px (as on the radio), so only held to not overlapping.
                val gap = if (f.key == "ic-9700") 1f else 0f
                spans.zipWithNext().forEach { (a, b) ->
                    assertTrue("${f.key} tx=$tx: ${a.first} ends at ${a.third}, ${b.first} starts at ${b.second}",
                        b.second - a.third >= gap)
                }
                // Nothing printed past the bar's end, towards the frequency.
                assertTrue("${f.key} tx=$tx: ${spans.last()}", spans.last().third <= p.barX + p.barW + 0.01f)
                assertTrue("${f.key} tx=$tx: ${spans.first()}", spans.first().second >= f.band.meter.x)
            }
        }
    }

    /**
     * Rooms the faces are fitted to: a Fire TV's 960 x 540 dp less the face margins, a phone on its side
     * (2.2:1), a 16:10 tablet, a 4:3 tablet, and a 7610 face on a 16:9 TV (it is 5:3 itself).
     */
    private val rooms = listOf((960f - 40f) / (540f - 24f), 2.2f, 1.6f, 4f / 3f, 16f / 9f)
    private fun fitted(): List<Face> = FACES.values.flatMap { f -> rooms.map { fitFace(f, it) } }

    @Test fun `a fitted face has exactly the room's shape, so it fills it with no border`() {
        FACES.values.forEach { f ->
            rooms.forEach { aspect ->
                val g = fitFace(f, aspect)
                assertEquals("${f.key} in $aspect", aspect, g.w / g.h, aspect * 0.006f)
                // Never smaller than the radio in either direction: room is added, nothing squeezed.
                assertTrue(g.w >= f.w && g.h >= f.h)
            }
        }
        // A TV's room is within a few percent of the 9700's own shape: barely changed.
        val tv = fitFace(nine, rooms[0])
        assertTrue(tv.w - nine.w < nine.w * 0.03f)
    }

    @Test fun `the room goes to the scope and waterfall, and text keeps the radio's size`() {
        val wide = fitFace(nine, 2.2f)
        assertTrue(wide.spectrum.w > nine.spectrum.w && wide.waterfall.w > nine.waterfall.w && wide.keys.w > nine.keys.w)
        // The frequency moves right with the edge; the badges and meter stay where the radio has them.
        assertEquals(wide.w - nine.w, wide.band.freqRight - nine.band.freqRight, 0.01f)
        assertEquals(nine.band.mode, wide.band.mode)
        assertEquals(nine.band.meter, wide.band.meter)
        assertEquals(nine.band.freqSize, wide.band.freqSize, 0f)
        assertEquals(readoutRoom(nine), readoutRoom(wide), 0.01f)
        val tall = fitFace(nine, 4f / 3f)
        assertTrue(tall.spectrum.h > nine.spectrum.h && tall.waterfall.h > nine.waterfall.h)
        assertEquals(nine.main, tall.main)
        // The waterfall gets the larger share: it is history, which is what extra height is best spent on.
        assertTrue(tall.waterfall.h - nine.waterfall.h > tall.spectrum.h - nine.spectrum.h)
        // Still the radio's paged key bar, at the bottom.
        assertEquals(nine.keysPerPage, tall.keysPerPage)
        assertEquals(tall.h, tall.keys.bottom + (nine.h - nine.keys.bottom), 0.01f)
        // The 7610's VFO label is left of its mode badge and stays there; the 9700's follows the frequency.
        val seven = FACES.getValue("ic-7610")
        assertEquals(seven.band.vfoRight, fitFace(seven, 2.2f).band.vfoRight, 0f)
        assertEquals(nine.band.vfoRight + (wide.w - nine.w), wide.band.vfoRight, 0.01f)
    }

    @Test fun `only a touch screen held upright gets the compact face`() {
        assertTrue(fitFace(nine, 0.5f, allowCompact = true).keyRows == 2)
        assertTrue(fitFace(nine, 0.5f).keyRows == 1)
        // Garbage in (a room not measured yet) is the face as it is.
        assertEquals(nine, fitFace(nine, Float.NaN))
        assertEquals(nine, fitFace(nine, 0f))
    }

    private fun overlaps(a: Px, b: Px) = a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom

    private fun assertInside(f: Face, r: Px) {
        val e = 0.5f
        assertTrue("${f.key}: $r is off a ${f.w}x${f.h} screen",
            r.x >= -e && r.y >= -e && r.right <= f.w + e && r.bottom <= f.h + e)
    }
}
