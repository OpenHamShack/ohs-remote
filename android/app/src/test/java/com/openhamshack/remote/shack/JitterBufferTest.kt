package com.openhamshack.remote.shack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The receive queue's arithmetic in frames, and the player's sizes, for
 * mono and for Radio's interleaved stereo. A stereo frame here is (L, R) =
 * (n, -n), so a sample landing in the wrong channel shows as a sign.
 */
class JitterBufferTest {
    private fun mono(n: Int, from: Int) = ShortArray(n) { (from + it).toShort() }
    private fun stereo(frames: Int, from: Int) = ShortArray(frames * 2) { i ->
        val v = from + i / 2
        (if (i % 2 == 0) v else -v).toShort()
    }

    @Test fun `mono counts as before`() {
        val b = JitterBuffer(startFrames = 100, maxFrames = 400)
        val out = ShortArray(50)
        b.put(mono(60, 0))
        assertEquals(0, b.take(out))
        b.put(mono(60, 60))
        assertEquals(50, b.take(out))
        assertEquals(49.toShort(), out[49])
        assertEquals(70, b.queued)
    }

    @Test fun `a stereo cushion is frames, not shorts`() {
        // 150 ms at 48 kHz is 7200 frames: 14400 shorts must not start it early.
        val b = JitterBuffer(startFrames = 7200, maxFrames = 24000, channels = 2)
        b.put(stereo(3600, 0))
        assertEquals(3600, b.queued)
        assertFalse(b.started)
        b.put(stereo(3600, 3600))
        assertTrue(b.started)
        assertEquals(7200, b.queued)
    }

    @Test fun `take returns frames and keeps left and right`() {
        val b = JitterBuffer(10, 1000, channels = 2)
        b.put(stereo(7, 1)); b.put(stereo(7, 8))
        val out = ShortArray(20)          // room for 10 frames
        assertEquals(10, b.take(out))
        for (f in 0 until 10) {
            assertEquals((f + 1).toShort(), out[2 * f])
            assertEquals((-(f + 1)).toShort(), out[2 * f + 1])
        }
        assertEquals(4, b.queued)
        // A limit in frames, smaller than the array.
        assertEquals(3, b.take(out, maxFrames = 3))
        assertEquals(11.toShort(), out[0])
        assertEquals((-11).toShort(), out[1])
    }

    @Test fun `catching up drops whole frames, newest kept`() {
        val b = JitterBuffer(startFrames = 100, maxFrames = 300, channels = 2)
        // 130-frame chunks: the drop lands mid-chunk, where a short count would tear a frame.
        repeat(3) { b.put(stereo(130, 1 + it * 130)) }   // 390 > 300
        assertEquals(100, b.queued)
        assertEquals(290L, b.dropped)
        val out = ShortArray(200)
        assertEquals(100, b.take(out))
        assertEquals(291.toShort(), out[0])
        assertEquals((-291).toShort(), out[1])
        assertEquals(390.toShort(), out[198])
        assertEquals((-390).toShort(), out[199])
    }

    @Test fun `a torn frame is refused`() {
        val b = JitterBuffer(10, 100, channels = 2)
        try {
            b.put(ShortArray(3))
            fail("three shorts are not stereo frames")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, b.queued)
    }

    @Test fun `the player's sizes count frames`() {
        assertEquals(960, PcmPlayer.blockFrames(48000))   // 20 ms
        assertEquals(240, PcmPlayer.blockFrames(12000))
        // 40 ms of track: mono as it always was, stereo twice the bytes for the same time.
        assertEquals(12000 * 2 * 40 / 1000, PcmPlayer.trackBytes(12000, 1, 40))
        assertEquals(3840, PcmPlayer.trackBytes(48000, 1, 40))
        assertEquals(7680, PcmPlayer.trackBytes(48000, 2, 40))
    }

    @Test fun `the downmix for mono consumers averages each frame`() {
        val lr = shortArrayOf(100, -100, Short.MAX_VALUE, Short.MAX_VALUE, 10, 20, 7, 7)
        // Three of four frames asked for; full scale on both sides does not wrap.
        assertEquals(listOf<Short>(0, Short.MAX_VALUE, 15), PcmPlayer.downmix(lr, 3, 2).toList())
    }
}
