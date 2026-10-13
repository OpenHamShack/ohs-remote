package com.openhamshack.remote.shack

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSTV on the NB/Radio kinds, against events in ohscore/modes/sstv.py's
 * shape as tools/fakeshack.py injects them (recorded 9 October 2026: a
 * Martin 1 colour-bar picture's start, lines 1, 2 and 256, and done).
 */
class SstvTest {
    private fun events() = javaClass.classLoader!!.getResourceAsStream("shack/nb-events-sstv.txt")!!.bufferedReader()
        .readLines().filter { it.isNotBlank() }.map { (EngineEvent.parse(it) as EngineEvent.Sstv).event }

    private fun line(name: String, n: Int, w: Int, rgb: Int) = SstvEvent.Line(name, n, 4,
        ByteArray(w * 3) { i -> ((rgb shr (16 - 8 * (i % 3))) and 0xFF).toByte() })

    @Test fun theEventsParse() {
        val e = events()
        val start = e[0] as SstvEvent.Start
        assertEquals("martinm1", start.fmt)
        assertEquals(320, start.width)
        assertEquals(256, start.height)
        assertEquals(320 * 3, (e[1] as SstvEvent.Line).rgb.size)
        assertTrue(e.last() is SstvEvent.Done)
        // Not SSTV's shape: nothing to paint.
        assertNull(SstvEvent.parse(JSONObject("""{"mode": "sstv", "state": "start", "width": 0, "height": 256}""")))
        assertNull(SstvEvent.parse(JSONObject("""{"mode": "sstv", "state": "vis"}""")))
    }

    @Test fun linesArePaintedWhereTheEngineNumbersThem() {
        val rx = SstvReceiver()
        events().forEach { rx.apply(it) }
        val p = rx.gallery.single()
        assertTrue(p.done)
        assertEquals(256, p.lines)
        // Line 1 starts with the white bar and ends with the black one;
        // line 3, never sent here, is still unpainted.
        assertEquals(0xFFFFFFFF.toInt(), p.pixels[0])
        assertEquals(0xFF000000.toInt(), p.pixels[319])
        assertEquals(SstvPicture.UNPAINTED, p.pixels[2 * 320])
        assertEquals(0xFFFFFFFF.toInt(), p.pixels[255 * 320])
    }

    @Test fun aBadRowOrLineIsNotPainted() {
        val p = SstvPicture("a", "robot36", 4, 4)
        assertTrue(p.paint(line("a", 1, 4, 0x102030)))
        assertEquals(0xFF102030.toInt(), p.pixels[0])
        assertFalse("row of the wrong width", p.paint(SstvEvent.Line("a", 2, 4, ByteArray(9))))
        assertFalse("line 0", p.paint(line("a", 0, 4, 0)))
        assertFalse("past the end", p.paint(line("a", 5, 4, 0)))
        assertEquals(1, p.lines)
    }

    @Test fun aPictureThatDidNotStartHereIsDropped() {
        val rx = SstvReceiver()
        assertFalse(rx.apply(line("mid", 3, 4, 0)))
        assertFalse(rx.apply(SstvEvent.Done("mid")))
        assertNull(rx.current)
        assertTrue(rx.gallery.isEmpty())
        // Lines of another picture than the current one are not painted into it.
        assertTrue(rx.apply(SstvEvent.Start("b", "pd120", 4, 4)))
        val v = rx.version
        rx.apply(line("other", 1, 4, 0xFFFFFF))
        assertEquals(v, rx.version)
    }

    @Test fun theGalleryKeepsTheNewestFew() {
        val rx = SstvReceiver(keep = 3)
        for (i in 1..5) {
            rx.apply(SstvEvent.Start("p$i", "martinm1", 4, 4))
            rx.apply(line("p$i", 1, 4, 0))
            rx.apply(SstvEvent.Done("p$i"))
        }
        assertEquals(listOf("p5", "p4", "p3"), rx.gallery.map { it.name })
    }

    @Test fun rowsAreRgbInEveryMode() {
        // The engine converts Robot's and PD's Y/R-Y/B-Y before sending
        // (sstv.py _emit_line after _yryby_to_rgb): a row is width x 3 bytes,
        // painted as R, G, B whatever the format.
        val p = SstvPicture("r", "robot36", 1, 1)
        p.paint(SstvEvent.Line("r", 1, 1, byteArrayOf(1, 2, 3)))
        assertEquals(0xFF010203.toInt(), p.pixels[0])
    }

    // --- the picture's shape, from its first line (the Fire TV's strip, 09/10/2026) ---

    @Test fun thePictureIsReservedAtItsFullSizeFromTheStart() {
        val rx = SstvReceiver()
        rx.apply(SstvEvent.Start("s1", "scottie_s1", 320, 256))
        for (n in 1..61) rx.apply(line("s1", n, 320, 0xFF0000))
        val p = rx.current!!
        assertEquals(320 * 256, p.pixels.size)
        assertEquals(61, p.lines)
        // Painted from the top; everything below line 61 waits, dark.
        assertEquals(0xFFFF0000.toInt(), p.pixels[60 * 320])
        assertEquals(SstvPicture.UNPAINTED, p.pixels[61 * 320])
        assertEquals(SstvPicture.UNPAINTED, p.pixels.last())
    }

    @Test fun thePictureIsFittedAtFiveToFourWhateverTheBox() {
        val p = SstvPicture("s1", "scottie_s1", 320, 256)
        // A wide panel (the TV's docked one): full height, centred across.
        val (l, t, w, h) = p.fit(800f, 360f)
        assertEquals(360f, h, 0.01f)
        assertEquals(450f, w, 0.01f)
        assertEquals(175f, l, 0.01f)
        assertEquals(0f, t, 0.01f)
        // A tall one (a phone held upright): full width, centred down.
        val tall = p.fit(300f, 900f)
        assertEquals(300f, tall[2], 0.01f)
        assertEquals(240f, tall[3], 0.01f)
        assertEquals(330f, tall[1], 0.01f)
        assertEquals(1.25f, tall[2] / tall[3], 0.001f)
        // Nothing to draw into: nothing drawn.
        assertEquals(0f, p.fit(0f, 100f)[2], 0f)
    }

    @Test fun aLateStartWithAGuessedModeStartsAFreshPicture() {
        // The decoder may start mid-picture once the server guesses a mode
        // without a VIS header: a start is a start, whatever came before.
        val rx = SstvReceiver()
        rx.apply(SstvEvent.Start("a", "martinm1", 320, 256))
        rx.apply(line("a", 1, 320, 0x00FF00))
        assertTrue(rx.apply(SstvEvent.Start("b", "robot36", 320, 240)))
        assertEquals(240, rx.current!!.height)
        assertEquals(SstvPicture.UNPAINTED, rx.current!!.pixels[0])
        assertFalse("a line of the old picture is not painted into the new", rx.apply(line("a", 2, 320, 0x00FF00)))
        assertEquals(0, rx.current!!.lines)
    }
}
