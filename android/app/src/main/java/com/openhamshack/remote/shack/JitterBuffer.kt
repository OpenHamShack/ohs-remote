package com.openhamshack.remote.shack

/**
 * The receive audio's queue between the network and the speaker, measured in
 * frames rather than packets (the radio's packets are not all one size). A
 * frame is one sample per channel: one short for mono, an interleaved L,R
 * pair for stereo. Every count here - the cushion, the ceiling, what is
 * queued, what was dropped, what [take] returns - is in frames, so 150 ms is
 * 150 ms whether the stream has one channel or two; counting shorts instead
 * would halve the cushion of a stereo stream, and dropping a single short
 * would swap left and right for the rest of the session.
 *
 * Two rules, both about latency rather than smoothness:
 *
 * - **Start late enough not to stutter.** Playback waits until [startFrames]
 *   are queued, so the first network hiccup does not become the first gap.
 * - **Never fall behind.** Above [maxFrames] the oldest audio is thrown away
 *   until [startFrames] remain. A TV on Wi-Fi gets a burst of late packets
 *   now and then; played in full, each burst would leave the audio that much
 *   further behind the S-meter for the rest of the session. Live receive audio
 *   that is a second late is worse than a click.
 *
 * Plain JVM, no Android, so the arithmetic is unit-tested.
 */
class JitterBuffer(val startFrames: Int, val maxFrames: Int, val channels: Int = 1) {
    init { require(channels in 1..2) }

    private val chunks = ArrayDeque<ShortArray>()
    /** Into the head chunk, in shorts; always a whole number of frames. */
    private var headOffset = 0
    /** Frames waiting. */
    var queued = 0
        private set
    /** Frames dropped to catch up, since this buffer was made. */
    var dropped = 0L
        private set
    var started = false
        private set

    /**
     * Interleaved samples, a whole number of frames: a torn frame would
     * shift every later sample into the other channel, so one is refused
     * outright rather than half-played (the session only ever hands over
     * whole frames; see EngineSession's carry).
     */
    @Synchronized
    fun put(samples: ShortArray) {
        if (samples.isEmpty()) return
        require(samples.size % channels == 0) { "${samples.size} samples is not whole $channels-channel frames" }
        chunks.addLast(samples)
        queued += samples.size / channels
        if (queued > maxFrames) {
            var excess = queued - startFrames
            while (excess > 0 && chunks.isNotEmpty()) {
                val head = chunks.first()
                val left = (head.size - headOffset) / channels
                if (left <= excess) {
                    chunks.removeFirst(); headOffset = 0
                    queued -= left; excess -= left; dropped += left
                } else {
                    headOffset += excess * channels
                    queued -= excess; dropped += excess.toLong(); excess = 0
                }
            }
        }
        if (queued >= startFrames) started = true
    }

    /**
     * Up to [maxFrames] frames into [out] (interleaved), or none before
     * playback has started; returns frames, so the samples written are that
     * times [channels]. Running dry stops playback until [startFrames] are
     * queued again, so an outage ends with the same cushion the session
     * began with.
     */
    @Synchronized
    fun take(out: ShortArray, maxFrames: Int = out.size / channels): Int {
        if (!started) return 0
        val max = minOf(maxFrames, out.size / channels) * channels
        var n = 0
        while (n < max && chunks.isNotEmpty()) {
            val head = chunks.first()
            val count = minOf(max - n, head.size - headOffset)
            System.arraycopy(head, headOffset, out, n, count)
            n += count; headOffset += count; queued -= count / channels
            if (headOffset == head.size) { chunks.removeFirst(); headOffset = 0 }
        }
        if (queued == 0) started = false
        return n / channels
    }

    @Synchronized
    fun clear() {
        chunks.clear(); headOffset = 0; queued = 0; started = false
    }
}

/** Little-endian signed 16-bit PCM, as the module sends it, to samples. */
fun pcm16le(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): ShortArray {
    val n = length / 2
    return ShortArray(n) { i ->
        val lo = bytes[offset + 2 * i].toInt() and 0xFF
        val hi = bytes[offset + 2 * i + 1].toInt()
        ((hi shl 8) or lo).toShort()
    }
}
