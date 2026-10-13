package com.openhamshack.core.scope

/*
 * How a scope and waterfall look on this device: the colour map, contrast
 * and brightness of the waterfall, the trace filled or drawn as a line, and
 * how much the trace is averaged. All client-side - nothing here reaches the
 * radio or anybody else's screen - and kept per radio on this device
 * (ScopeLookStore). Pure Kotlin, tested on the JVM (ScopeLookTest).
 *
 * Brian (10 October 2026) asked for waterfall and scope customisation on
 * both the Icom and Flex screens. What the radio itself can set (reference
 * level, span, sweep speed) is the kinds' own, server-backed and shown only
 * when the module reports it; this is the half that needs no server.
 */

/** The colour maps on offer. [Radio] is the kind's own (Icom's or Flex's), as before this existed. */
enum class Palette { Radio, Rainbow, Grey, Fire, Ocean, Green }

data class ScopeLook(
    val palette: Palette = Palette.Radio,
    /** Waterfall gain: a level is multiplied by this. 1 leaves it as the radio sent it. */
    val contrast: Float = 1f,
    /** Waterfall offset, added after [contrast]: positive lifts the noise floor towards colour. */
    val brightness: Float = 0f,
    /** The trace filled below (Icom's default "Fill") or a line alone. */
    val fill: Boolean = true,
    /** Sweeps averaged into the trace: 0 is none; n is an exponential average over about n sweeps. */
    val averaging: Int = 0,
) {
    /** A level 0..1 as the waterfall should colour it. */
    fun level(v: Float): Float = (v * contrast + brightness).coerceIn(0f, 1f)

    /**
     * A 256-entry colour table for this look: entry i is the colour of level
     * i/255, contrast and brightness applied, [radio] being the kind's own
     * map. Built once when the look changes; a waterfall then colours each
     * point with one array read.
     */
    fun table(radio: (Float) -> Int): IntArray = IntArray(256) { i -> colour(palette, level(i / 255f), radio) }

    /** One step along [CONTRASTS] / [BRIGHTNESSES] / [AVERAGING] (clamped at the ends), or the next palette. */
    fun stepContrast(by: Int) = copy(contrast = stepIn(CONTRASTS, contrast, by))
    fun stepBrightness(by: Int) = copy(brightness = stepIn(BRIGHTNESSES, brightness, by))
    fun stepAveraging(by: Int) = copy(averaging = stepIn(AVERAGING.map { it.toFloat() }, averaging.toFloat(), by).toInt())
    fun nextPalette(by: Int = 1) = copy(palette = Palette.entries[Math.floorMod(palette.ordinal + by, Palette.entries.size)])

    /** Stored as one short line: "palette=Fire;contrast=1.5;brightness=0.1;fill=1;avg=4". */
    fun encode(): String = "palette=${palette.name};contrast=$contrast;brightness=$brightness;fill=${if (fill) 1 else 0};avg=$averaging"

    companion object {
        val CONTRASTS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f)
        val BRIGHTNESSES = listOf(-0.3f, -0.2f, -0.1f, 0f, 0.1f, 0.2f, 0.3f)
        val AVERAGING = listOf(0, 2, 4, 8, 16)

        /** The stored line back to a look; anything unreadable is the default for that field, never a crash. */
        fun decode(s: String?): ScopeLook {
            if (s.isNullOrBlank()) return ScopeLook()
            val m = s.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val d = ScopeLook()
            return ScopeLook(
                palette = m["palette"]?.let { n -> Palette.entries.firstOrNull { it.name == n } } ?: d.palette,
                contrast = m["contrast"]?.toFloatOrNull()?.takeIf { it in 0.1f..10f } ?: d.contrast,
                brightness = m["brightness"]?.toFloatOrNull()?.takeIf { it in -1f..1f } ?: d.brightness,
                fill = m["fill"]?.let { it == "1" } ?: d.fill,
                averaging = m["avg"]?.toIntOrNull()?.takeIf { it in 0..64 } ?: d.averaging,
            )
        }

        private fun stepIn(list: List<Float>, v: Float, by: Int): Float {
            val i = list.indices.minByOrNull { kotlin.math.abs(list[it] - v) } ?: return v
            return list[(i + by).coerceIn(0, list.size - 1)]
        }

        /** Level 0..1 to ARGB in [p]; [radio] is the kind's own map, used for [Palette.Radio]. */
        fun colour(p: Palette, v: Float, radio: (Float) -> Int): Int = when (p) {
            Palette.Radio -> radio(v)
            Palette.Rainbow -> ramp(v, floatArrayOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f),
                intArrayOf(0x000000, 0x0000C0, 0x00C0C0, 0x00C000, 0xE0E000, 0xFF0000))
            Palette.Grey -> ramp(v, floatArrayOf(0f, 1f), intArrayOf(0x000000, 0xFFFFFF))
            Palette.Fire -> ramp(v, floatArrayOf(0f, 0.3f, 0.6f, 0.85f, 1f),
                intArrayOf(0x000000, 0x600000, 0xE04000, 0xFFC000, 0xFFFFE0))
            Palette.Ocean -> ramp(v, floatArrayOf(0f, 0.35f, 0.7f, 1f), intArrayOf(0x000010, 0x003070, 0x10A0D0, 0xE0FFFF))
            Palette.Green -> ramp(v, floatArrayOf(0f, 0.5f, 1f), intArrayOf(0x000000, 0x00A020, 0xC0FFC0))
        }

        /** Linear between colour stops. */
        fun ramp(v: Float, stops: FloatArray, cols: IntArray): Int {
            val x = v.coerceIn(0f, 1f)
            var i = 0
            while (i < stops.size - 2 && x > stops[i + 1]) i++
            val t = ((x - stops[i]) / (stops[i + 1] - stops[i])).coerceIn(0f, 1f)
            val a = cols[i]; val b = cols[i + 1]
            fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * t).toInt()
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}

/**
 * A trace averaged over sweeps, as [ScopeLook.averaging] asks: each new
 * sweep moves the average 1/(n+1) of the way towards itself, so a steady
 * signal stands out of the noise and a passing one fades in and out. A sweep
 * of another length (a new span from the radio) starts it again.
 */
class TraceAverage {
    private var avg = FloatArray(0)

    fun apply(sweep: FloatArray, n: Int): FloatArray {
        if (n <= 0 || avg.size != sweep.size) {
            avg = sweep.copyOf()
            return avg
        }
        val k = 1f / (n + 1)
        for (i in sweep.indices) avg[i] += (sweep[i] - avg[i]) * k
        return avg
    }

    fun reset() { avg = FloatArray(0) }
}
