package com.openhamshack.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.openhamshack.core.audio.JitterBuffer
import com.openhamshack.core.audio.ToneProbe
import com.openhamshack.core.audio.pcm16le
import kotlin.math.PI
import kotlin.math.sin

class JitterBufferTest {
    private fun chunk(n: Int, from: Int) = ShortArray(n) { (from + it).toShort() }

    @Test fun `nothing plays until the cushion is there`() {
        val b = JitterBuffer(startSamples = 100, maxSamples = 400)
        val out = ShortArray(50)
        b.put(chunk(60, 0))
        assertEquals(0, b.take(out))
        b.put(chunk(60, 60))
        assertEquals(50, b.take(out))
        assertEquals(0.toShort(), out[0])
        assertEquals(49.toShort(), out[49])
    }

    @Test fun `audio comes out in order across chunks`() {
        val b = JitterBuffer(10, 1000)
        b.put(chunk(7, 0)); b.put(chunk(7, 7)); b.put(chunk(7, 14))
        val out = ShortArray(21)
        assertEquals(21, b.take(out))
        assertTrue(out.withIndex().all { (i, v) -> v.toInt() == i })
    }

    @Test fun `a burst beyond the ceiling drops the oldest, down to the cushion`() {
        val b = JitterBuffer(startSamples = 100, maxSamples = 300)
        repeat(4) { b.put(chunk(100, it * 100)) }   // 400 > 300
        assertEquals(100, b.queued)
        assertEquals(300L, b.dropped)
        val out = ShortArray(100)
        assertEquals(100, b.take(out))
        assertEquals(300.toShort(), out[0])         // the newest audio survived
    }

    @Test fun `running dry waits for the cushion again`() {
        val b = JitterBuffer(50, 400)
        b.put(chunk(60, 0))
        val out = ShortArray(100)
        assertEquals(60, b.take(out))
        assertFalse(b.started)
        b.put(chunk(30, 0))
        assertEquals(0, b.take(out))
    }

    @Test fun `little-endian bytes to samples`() {
        val s = pcm16le(byteArrayOf(0x01, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x80.toByte(), 0xFF.toByte(), 0x7F))
        assertEquals(listOf<Short>(1, -1, Short.MIN_VALUE, Short.MAX_VALUE), s.toList())
    }

    @Test fun `the probe finds a tone`() {
        val rate = 12000
        val s = ShortArray(4096) { (10000 * sin(2 * PI * 1000 * it / rate)).toInt().toShort() }
        assertEquals(1000, ToneProbe.peakHz(s, s.size, rate))
        assertEquals(10000 / 32768.0 / Math.sqrt(2.0), ToneProbe.rms(s), 0.005)
    }
}
