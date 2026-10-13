package com.openhamshack.core.audio

/**
 * The receive audio's queue between the network and the speaker, measured in
 * samples rather than packets (the radio's packets are not all one size).
 *
 * Two rules, both about latency rather than smoothness:
 *
 * - **Start late enough not to stutter.** Playback waits until [startSamples]
 *   are queued, so the first network hiccup does not become the first gap.
 * - **Never fall behind.** Above [maxSamples] the oldest audio is thrown away
 *   until [startSamples] remain. A TV on Wi-Fi gets a burst of late packets
 *   now and then; played in full, each burst would leave the audio that much
 *   further behind the S-meter for the rest of the session. Live receive audio
 *   that is a second late is worse than a click.
 *
 * Plain JVM, no Android, so the arithmetic is unit-tested.
 */
class JitterBuffer(val startSamples: Int, val maxSamples: Int) {
    private val chunks = ArrayDeque<ShortArray>()
    private var headOffset = 0
    var queued = 0
        private set
    /** Samples dropped to catch up, since this buffer was made. */
    var dropped = 0L
        private set
    var started = false
        private set

    @Synchronized
    fun put(samples: ShortArray) {
        if (samples.isEmpty()) return
        chunks.addLast(samples)
        queued += samples.size
        if (queued > maxSamples) {
            var excess = queued - startSamples
            while (excess > 0 && chunks.isNotEmpty()) {
                val head = chunks.first()
                val left = head.size - headOffset
                if (left <= excess) {
                    chunks.removeFirst(); headOffset = 0
                    queued -= left; excess -= left; dropped += left
                } else {
                    headOffset += excess
                    queued -= excess; dropped += excess; excess = 0
                }
            }
        }
        if (queued >= startSamples) started = true
    }

    /**
     * Up to [max] samples into [out], or none before playback has started.
     * Running dry stops playback until [startSamples] are queued again, so an
     * outage ends with the same cushion the session began with.
     */
    @Synchronized
    fun take(out: ShortArray, max: Int = out.size): Int {
        if (!started) return 0
        var n = 0
        while (n < max && chunks.isNotEmpty()) {
            val head = chunks.first()
            val count = minOf(max - n, head.size - headOffset)
            System.arraycopy(head, headOffset, out, n, count)
            n += count; headOffset += count; queued -= count
            if (headOffset == head.size) { chunks.removeFirst(); headOffset = 0 }
        }
        if (queued == 0) started = false
        return n
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
