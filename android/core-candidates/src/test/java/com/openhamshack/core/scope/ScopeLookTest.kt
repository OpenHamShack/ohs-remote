package com.openhamshack.core.scope

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeLookTest {
    private val radio: (Float) -> Int = { v -> 0xFF000000.toInt() or (v * 255).toInt() }

    @Test fun `the default look is the radio's own colours, untouched`() {
        val t = ScopeLook().table(radio)
        assertEquals(256, t.size)
        for (i in 0..255) assertEquals(radio(i / 255f), t[i])
    }

    @Test fun `contrast and brightness move levels before colouring, and clamp`() {
        val look = ScopeLook(contrast = 2f, brightness = 0.1f)
        assertEquals(0.1f, look.level(0f), 1e-6f)
        assertEquals(0.5f, look.level(0.2f), 1e-6f)
        assertEquals(1f, look.level(0.9f), 0f)
        assertEquals(0f, ScopeLook(brightness = -0.3f).level(0.1f), 0f)
        // A brighter look lifts the floor towards colour.
        assertNotEquals(ScopeLook().table(radio)[0], ScopeLook(brightness = 0.2f).table(radio)[0])
    }

    @Test fun `every palette runs dark to bright, and they differ`() {
        val tables = Palette.entries.map { p -> ScopeLook(palette = p).table(radio) }
        fun luma(c: Int) = ((c shr 16) and 0xFF) * 3 + ((c shr 8) and 0xFF) * 6 + (c and 0xFF)
        tables.forEach { t -> assertTrue(luma(t[255]) > luma(t[0])) }
        assertEquals(Palette.entries.size, tables.map { it.toList() }.toSet().size)
    }

    @Test fun `steps stay on their ladders and stop at the ends`() {
        val l = ScopeLook()
        assertEquals(1.25f, l.stepContrast(+1).contrast, 0f)
        assertEquals(0.5f, l.stepContrast(-10).contrast, 0f)
        assertEquals(0.3f, l.stepBrightness(+10).brightness, 0f)
        assertEquals(2, l.stepAveraging(+1).averaging)
        assertEquals(16, l.stepAveraging(+10).averaging)
        // Palettes wrap: the list is short and a remote has no "first" key.
        assertEquals(Palette.Radio, l.nextPalette(Palette.entries.size).palette)
        assertEquals(Palette.Green, l.nextPalette(-1).palette)
    }

    @Test fun `a look survives being stored, and a damaged one reads as the default`() {
        val l = ScopeLook(Palette.Fire, contrast = 1.5f, brightness = -0.1f, fill = false, averaging = 8)
        assertEquals(l, ScopeLook.decode(l.encode()))
        assertEquals(ScopeLook(), ScopeLook.decode(null))
        assertEquals(ScopeLook(), ScopeLook.decode("rubbish"))
        assertEquals(ScopeLook(palette = Palette.Grey), ScopeLook.decode("palette=Grey;contrast=banana;avg=999"))
    }

    @Test fun `averaging smooths towards each sweep, and a new width starts again`() {
        val a = TraceAverage()
        assertArrayEquals(floatArrayOf(1f, 0f), a.apply(floatArrayOf(1f, 0f), 3), 0f)
        assertArrayEquals(floatArrayOf(0.75f, 0.25f), a.apply(floatArrayOf(0f, 1f), 3), 1e-6f)
        // Off: each sweep as it came.
        assertArrayEquals(floatArrayOf(0f, 1f), a.apply(floatArrayOf(0f, 1f), 0), 0f)
        assertArrayEquals(floatArrayOf(5f, 5f, 5f), a.apply(floatArrayOf(5f, 5f, 5f), 3), 0f)
    }
}
