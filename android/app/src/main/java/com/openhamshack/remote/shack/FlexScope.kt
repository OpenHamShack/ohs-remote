package com.openhamshack.remote.shack

import kotlin.math.abs
import kotlin.math.floor

/**
 * Flex's panadapter and waterfall for a screen, from the module's frames.
 * Pure (no Android types) so the mapping is tested on the JVM.
 *
 * Unlike the NB/Radio scope, the waterfall here is not built from the
 * trace: the radio sends its own waterfall rows (finer, and at its own
 * line rate), so the trace comes from [FlexFrame.Sweep]s and the rows from
 * [FlexFrame.WaterfallRow]s, both placed by frequency on the panadapter's
 * window. A row's tile may not cover exactly the panadapter's span, so it
 * is resampled by frequency rather than stretched.
 *
 * The rows' intensities have no defined zero (PROTOCOL.md 3: noise near
 * 90-100 on HF, signals some 15 above), and the radio's own black level
 * is on another scale, so each row's floor is its own 20th percentile -
 * what the module's page does - and the colour spans [ROW_RANGE] above it.
 *
 * The history keeps each point's level (0..1, NaN where a row did not
 * reach) rather than its colour, so a colour map, contrast or brightness
 * chosen on the screen (core ScopeLook) recolours all of it at once:
 * [render] colours it through a 256-entry table when the screen draws.
 */
class FlexScope(val width: Int = 480, val height: Int = 150) {
    /** The latest trace folded to [width] columns, dBm. Empty until the first sweep. */
    var line: FloatArray = FloatArray(0)
        private set
    var lowHz: Double = 0.0
        private set
    var highHz: Double = 0.0
        private set
    /** The trace's floor (20th percentile), for scaling it. */
    var floorDbm: Float = -130f
        private set
    /** Levels 0..1, NaN for nothing, newest row at the top. */
    private val levels: FloatArray = FloatArray(width * height) { Float.NaN }
    var version: Int = 0
        private set

    /** The waterfall in the radio's own colours (ARGB, newest row at the top). */
    val pixels: IntArray get() = IntArray(width * height).also { render(it, DEFAULT_TABLE) }

    /** The waterfall into [out] through [table] (256 colours, level 0 first); [Scope.BACKGROUND] where empty. */
    @Synchronized
    fun render(out: IntArray, table: IntArray) {
        for (i in 0 until minOf(out.size, levels.size)) {
            val v = levels[i]
            out[i] = if (v.isNaN()) Scope.BACKGROUND else table[(v * 255f).toInt().coerceIn(0, 255)]
        }
    }

    @Synchronized
    fun sweep(f: FlexFrame.Sweep) {
        moveTo(f.lowHz, f.highHz)
        line = Scope.fold(f.dbm, width)
        floorDbm = Scope.percentile(line, 0.2f)
        version++
    }

    @Synchronized
    fun row(r: FlexFrame.WaterfallRow) {
        if (highHz <= lowHz || r.intensity.isEmpty() || r.binHz <= 0) return
        val cols = resample(r, lowHz, highHz, width)
        val floor = Scope.percentile(cols.filter { !it.isNaN() }.toFloatArray(), 0.2f)
        System.arraycopy(levels, 0, levels, width, width * (height - 1))
        for (x in 0 until width) {
            val v = cols[x]
            levels[x] = if (v.isNaN()) Float.NaN else ((v - floor) / ROW_RANGE).coerceIn(0f, 1f)
        }
        version++
    }

    /** A new window (the display was retuned or zoomed): the old rows would lie about frequency. */
    private fun moveTo(low: Double, high: Double) {
        val col = (highHz - lowHz) / width
        if (line.isNotEmpty() && (abs(low - lowHz) > col / 2 || abs(high - highHz) > col / 2)) levels.fill(Float.NaN)
        lowHz = low
        highHz = high
    }

    /** 0..1 of the width for [hz], or null off the display. */
    fun xOf(hz: Double): Float? {
        if (highHz <= lowHz || hz < lowHz || hz > highHz) return null
        return ((hz - lowHz) / (highHz - lowHz)).toFloat()
    }

    companion object {
        /** [Scope.colour] at each of 256 levels: the radio-like map the waterfall had before looks. */
        val DEFAULT_TABLE: IntArray = IntArray(256) { Scope.colour(it / 255f) }

        /**
         * Intensity units from a row's floor to the top of the colour map:
         * a FLEX-6400's signals stand some 15 above its noise (PROTOCOL.md
         * 3), so a strong one reaches yellow. 25 left fakeflex's carriers,
         * 11 above, a dim blue.
         */
        const val ROW_RANGE = 18f

        /**
         * [r]'s bins onto [columns] columns spanning [low]..[high] Hz, the
         * strongest bin in each column (a narrow carrier must not vanish
         * into its neighbours); NaN where the tile does not reach.
         */
        fun resample(r: FlexFrame.WaterfallRow, low: Double, high: Double, columns: Int): FloatArray {
            val out = FloatArray(columns) { Float.NaN }
            val colHz = (high - low) / columns
            for (i in r.intensity.indices) {
                val hz = r.lowHz + i * r.binHz
                if (hz < low || hz >= high) continue
                val c = floor((hz - low) / colHz).toInt().coerceIn(0, columns - 1)
                val v = r.intensity[i]
                if (out[c].isNaN() || v > out[c]) out[c] = v
            }
            // Finer columns than bins: a column no bin fell in takes its left neighbour.
            for (c in 1 until columns) if (out[c].isNaN() && !out[c - 1].isNaN()) {
                val hz = low + c * colHz
                if (hz >= r.lowHz && hz < r.lowHz + r.intensity.size * r.binHz) out[c] = out[c - 1]
            }
            return out
        }
    }
}

/** Tuning arithmetic for the Flex kind: steps, the grid, and when the display should follow. */
object FlexTuning {
    /** Steps offered when the mode table gives none: a sensible ladder for SSB/CW/digital. */
    val FALLBACK_STEPS = listOf(10, 50, 100, 500, 1000, 5000, 10000)

    /** The mode's steps from the module's table, or [FALLBACK_STEPS]. */
    fun steps(mode: FlexMode?): List<Int> = mode?.steps?.takeIf { it.isNotEmpty() } ?: FALLBACK_STEPS

    /** One step up or down the ladder from [current], staying on it (the nearest rung when [current] is off it). */
    fun nextStep(steps: List<Int>, current: Int, by: Int): Int {
        if (steps.isEmpty()) return current
        val i = steps.indexOf(current).takeIf { it >= 0 } ?: steps.indices.minByOrNull { abs(steps[it] - current) }!!
        return steps[(i + by).coerceIn(0, steps.size - 1)]
    }

    /**
     * [hz] moved [clicks] steps, landing on the step's grid: from 14.2013
     * with 1 kHz steps, up goes to 14.202 rather than 14.2023 - as a
     * radio's knob does with its step set, so the readout stays round.
     */
    fun tune(hz: Long, step: Int, clicks: Int): Long {
        if (step <= 0 || clicks == 0) return hz
        val s = step.toLong()
        val base = if (hz % s == 0L) hz else if (clicks > 0) Math.floorDiv(hz, s) * s else Math.floorDiv(hz, s) * s + s
        return base + clicks * s
    }

    /**
     * Where a finger dragged [dxPx] across a scope [widthPx] wide showing
     * [spanHz] leaves a dial that was on [startHz], on [step]'s grid.
     *
     * The frequency goes the way the finger goes: swipe right and it goes
     * up, as the numbers on the scale do, so the cursor appears to be pushed
     * along the band. The first version moved the scope with the finger as a
     * map does (left for up, as SmartSDR does); Brian tried it on the Tab S9
     * and wanted it this way round, 12/10/2026, and a club member is asked
     * to "swipe up the band", not to drag the band past. [dxPx] is the
     * whole drag since it began, each movement already scaled by
     * [dragGain], and the dial is worked out from where the drag began
     * rather than stepped event by event, so a long swipe gathers no
     * rounding. Dragging less than half a step leaves the dial alone.
     */
    fun dragged(startHz: Long, dxPx: Float, widthPx: Int, spanHz: Long, step: Int): Long {
        if (widthPx <= 0 || spanHz <= 0) return startHz
        val hz = startHz + (dxPx.toDouble() / widthPx * spanHz)
        val s = step.toLong().coerceAtLeast(1)
        return Math.round(hz / s) * s
    }

    /**
     * How much of a finger's movement at [dpPerSecond] counts towards
     * [dragged]: [FINE_GAIN] at a slow, careful drag, rising in a straight
     * line to all of it at [FAST_DP_S] and above, as a mouse pointer does.
     *
     * One to one, a 200 kHz scope on the Tab S9 moved about 70 Hz a pixel,
     * so landing on an SSB signal meant a finger held still to within a
     * pixel or two; Brian found it too quick to stop on anything (12/10/2026).
     * A tenth when slow is about 7 Hz a pixel, 14 px for a 100 Hz step,
     * while a quick swipe still crosses the band. In dp so a phone and the
     * tablet feel the same.
     */
    fun dragGain(dpPerSecond: Float): Float {
        val v = abs(dpPerSecond)
        if (v <= SLOW_DP_S) return FINE_GAIN
        if (v >= FAST_DP_S) return 1f
        return FINE_GAIN + (1f - FINE_GAIN) * (v - SLOW_DP_S) / (FAST_DP_S - SLOW_DP_S)
    }

    const val FINE_GAIN = 0.1f
    const val SLOW_DP_S = 60f
    const val FAST_DP_S = 900f

    /**
     * Where a tune from [from] towards [to] may go inside [coverage]: [to]
     * itself, or the edge it ran into. At an edge the dial stops there
     * rather than wrapping round to the other end: a held Left at the
     * bottom of the receiver should feel like a knob against its stop, not
     * a jump to 54 MHz. [Limit.send] is false when the dial is already
     * at that edge, so a held button sends nothing more.
     *
     * A dial already outside [coverage] (a radio set there by something
     * else) is brought back to the nearest edge by any press.
     */
    fun limit(from: Long?, to: Long, coverage: FlexCoverage): Limit {
        val hz = coverage.clamp(to)
        val edge = when {
            to < coverage.lowHz -> Edge.FLOOR
            to > coverage.highHz -> Edge.CEILING
            else -> null
        }
        return Limit(hz, edge, send = hz != from)
    }

    enum class Edge { FLOOR, CEILING }

    data class Limit(val hz: Long, val edge: Edge?, val send: Boolean)

    /** Where to put the display's centre for [hz], or null while it sits inside the middle of [pan] (pane.js FOLLOW_MARGIN). */
    fun recentre(hz: Long, pan: FlexPan?): Double? {
        if (pan == null || pan.spanHz <= 0) return null
        val margin = pan.spanHz * FOLLOW_MARGIN
        return if (hz < pan.lowHz + margin || hz > pan.highHz - margin) hz.toDouble() else null
    }

    /** The module's page recentres when the receiver comes within 12 % of an edge. */
    const val FOLLOW_MARGIN = 0.12

    /** MHz as the module's commands take it: six places, so 1 Hz survives. */
    fun mhz(hz: Long): Double = hz / 1e6
}

/**
 * The panadapter's zoom and reference level from the D-pad (or the scope
 * page), stepping along what the module reports (FlexPan.spansHz) and
 * inside FlexRadio's scale (-180..20 dBm, API.md 4b). Pure, tested on the JVM.
 */
object FlexScopeControl {
    /** One press of the reference level: 5 dB, about a S-unit, so a few presses visibly move the trace. */
    const val REF_PRESS_DB = 5.0
    const val REF_MIN = -180.0
    const val REF_MAX = 20.0

    /** The span one step in (narrower) or out along [ladder] from [current]; null at the ends or with none. */
    fun zoom(ladder: List<Long>, current: Long, zoomIn: Boolean): Long? {
        if (ladder.isEmpty()) return null
        return if (zoomIn) ladder.lastOrNull { it < current } else ladder.firstOrNull { it > current }
    }

    /** The top of the scale one press up or down, on whole dB and inside the radio's range; null if it would not move. */
    fun ref(current: Double, up: Boolean): Double? {
        val next = (Math.round(current) + if (up) REF_PRESS_DB else -REF_PRESS_DB).coerceIn(REF_MIN, REF_MAX)
        return next.takeIf { kotlin.math.abs(it - current) > 1e-9 }
    }

    /**
     * Where a trace level in dBm sits, 0 (bottom) to 1 (top), on the
     * radio's scale [minDbm]..[maxDbm] - what the module's page draws to,
     * so the reference level moves the trace on this screen as it does on
     * the radio's - or, with no usable scale, between [floor] and 55 dB
     * above it, as before the module reported one.
     */
    fun traceY(dbm: Float, minDbm: Double?, maxDbm: Double?, floor: Float): Float {
        if (minDbm != null && maxDbm != null && maxDbm - minDbm >= 10) {
            return ((dbm - minDbm) / (maxDbm - minDbm)).toFloat().coerceIn(0f, 1f)
        }
        return ((dbm - floor + 5f) / 55f).coerceIn(0f, 1f)
    }
}

/**
 * The band plan strip under the spectrum (as the module's page draws it,
 * overlay.js _bandPlan): each segment of the plan clipped to the visible
 * span and placed in pixels, at least one pixel wide so a narrow beacon
 * segment does not vanish at a wide span; its label only where it fits.
 */
object FlexPlanStrip {
    data class Piece(val x: Float, val w: Float, val kind: String, val colour: Int, val label: Boolean)

    /** [segments] over [lowHz]..[highHz] drawn [widthPx] wide; a label needs [labelPx] of room. */
    fun layout(segments: List<FlexSegment>, lowHz: Double, highHz: Double, widthPx: Float, labelPx: Float): List<Piece> {
        val span = highHz - lowHz
        if (span <= 0 || widthPx <= 0) return emptyList()
        return segments.filter { it.highHz >= lowHz && it.lowHz <= highHz }.map { s ->
            val lo = maxOf(s.lowHz.toDouble(), lowHz)
            val hi = minOf(s.highHz.toDouble(), highHz)
            val x = ((lo - lowHz) / span * widthPx).toFloat()
            val w = maxOf(((hi - lo) / span * widthPx).toFloat(), 1f)
            Piece(x, w, s.kind, s.colour, w >= labelPx)
        }
    }
}
