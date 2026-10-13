package com.openhamshack.kind.stationflow.radio

import com.openhamshack.kind.stationflow.api.RadioState

/*
 * The radio-shaped display: which radio looks like what, as data.
 *
 * docs/RADIO-DISPLAYS.md is the source for every number here. It lays out
 * each model's own screen from Icom's manuals, region by region, in the
 * radio's own pixels, and says where each fact came from; its section 5
 * ("Layout spec") is this file in prose. The TV, phone and tablet views
 * (RadioFaceView) and the web page (icom's app/static/display.js, which
 * carries the same numbers) are all drawn from it, so the three are one
 * description rather than three impressions of a photograph. The Swift
 * version for Apple TV/iPad/iPhone is to follow the same document.
 *
 * Kept free of Compose so it is tested on the JVM: which face a model gets,
 * how a frequency splits into large and small digits, where a reading lands
 * on the meter, and that no key is a transmit action.
 *
 * What is NOT here, on purpose: any value StationFlow does not report. The
 * radios show AGC, noise blanker and reduction, tone, duplex, RIT, memory
 * channel and the scope's reference level; StationFlow's API carries none
 * of them (docs/QUESTIONS.md, "What the radio's own screen shows that the
 * API does not"). Their places are left empty rather than filled with a
 * plausible guess: an "AGC-F" the module never read would be the display
 * lying about the radio.
 *
 * The preamp and attenuator ARE reported (Icom 1.23.0-beta.5, 10 October
 * 2026: Brian asked for P.AMP and ATT on the Remote). They are drawn where
 * each radio prints them - "P.AMP" / "ATT" right of the filter on the 9700
 * and 705's mode line ([BandLayout.amp]; IC-9700 Basic Manual p. 1-5, item
 * 21), the value under the 7610's P.AMP and ATT tiles - and only once the
 * radio has said. Their keys appear only for what the radio's model lists
 * (RadioState.preamps / attenuators): nothing here names a radio to decide.
 *
 * Nor is the sub band. The 9700 and 7610 show main and sub together, and
 * these faces used to keep the sub's block on screen, empty, saying "sub
 * band: not reported". Brian's call (9 October 2026) was that the VFO is
 * enough: the sub block is gone from every face and the main block and the
 * scope have its room. RADIO-DISPLAYS.md keeps the research on how the
 * radios draw the sub band, for when the API reports one.
 */

/** A rectangle in the radio's own screen pixels. */
data class Px(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right: Float get() = x + w
    val bottom: Float get() = y + h
    fun shift(dx: Float, dy: Float) = Px(x + dx, y + dy, w, h)
}

/**
 * What a key along the bottom does. Every member is a receive-side action
 * or opens one of this app's own screens; there is deliberately no member
 * for anything that keys or changes power, so no face can be given one.
 * FacesTest holds every face's keys to this list.
 *
 * [Settings] is the SET key Brian asked for (10 October 2026): it opens a
 * sheet of the radio's less-used receive settings - P.AMP/ATT, the scope,
 * NR2, mute - which used to take keys of their own. Everything in it is
 * receive-side too; the client below cannot send anything else.
 */
enum class KeyAction { Page, Mode, Filter, Band, Vfo, Tune, Settings, Decodes, Sstv }
/**
 * One key in the bottom bar. The radio's own keys there are its scope's
 * (span, hold, centre/fixed, reference, speed, marker - RADIO-DISPLAYS.md),
 * which StationFlow cannot command; ours keep their look and their paging
 * key ("< 1 >", "<MENU1>") but carry what the app can do, in the radio's
 * own words where it has them (MODE, FIL, BAND, A/B, F-INP) and ours where
 * it has not.
 */
data class SoftKey(val label: String, val action: KeyAction)

/** How a face draws its receive meter. */
enum class MeterStyle {
    /** A row of segments under a printed scale (IC-9700, IC-705). */
    Bar,
    /** An analogue needle over printed arcs (the IC-7610's default "Standard" meter). */
    Needle,
}

/** The band's readout block, relative to the block's top-left. */
data class BandLayout(
    val mode: Px,
    val filter: Px,
    /** Whether the filter sits in a box (9700, 705) or is plain text beside the mode (7610). */
    val filterBoxed: Boolean,
    /** The meter: under the badges on the 9700 and 705, above them on the 7610. */
    val meter: Px,
    /** How much of a bar meter's width its label ("S", "Po") takes before the bar starts. */
    val meterLabelW: Float = 0f,
    /** The frequency: right edge, baseline, and the large digits' size (font px). */
    val freqRight: Float,
    val freqBaseline: Float,
    val freqSize: Float,
    /** The small trailing digits' size, when the radio shows any. */
    val smallSize: Float,
    /** "VFO A" / "VFO B", right-aligned at this point (its top). */
    val vfoRight: Float,
    val vfoTop: Float,
    val labelSize: Float,
    /** The frequency's touch and focus area: what the radio's own screen answers a touch on as "the frequency". */
    val dial: Px,
    /**
     * Where "P.AMP" and "ATT" are printed while on: the 9700 and 705's mode
     * line, right of the filter, as the radio's icon slot is (Basic Manual
     * p. 1-5, item 21). Held above the tuning-step mark over the digits, so
     * no frequency's readout - 1300.000.00 included - can reach it, and
     * outside the dial, so the TV's focus ring does not enclose it. Null on
     * a face that shows them elsewhere (the 7610's tiles).
     */
    val amp: Px? = null,
)

/**
 * One model's screen, in its own pixels (`w` x `h`). A view scales the
 * whole face uniformly to the room it has, so the proportions stay the
 * radio's.
 */
data class Face(
    val key: String,
    val name: String,
    val w: Float,
    val h: Float,
    /** The top row: the clock, LAN. */
    val status: Px,
    val clockRight: Float,
    val textSize: Float,
    /** The TX indicator: red outline, lit only while the radio transmits. */
    val txBox: Px,
    /** Where the main band's block is, and its layout. */
    val main: Px,
    val band: BandLayout,
    /**
     * The IC-7610's column of function tiles down the left edge (ANT, P.AMP,
     * ATT, IP+, AGC, VOX, COMP). Drawn with their names; P.AMP and ATT carry
     * the radio's value under the name once it has reported one, and the
     * other five stay names alone (not reported).
     */
    val tileColumn: Px? = null,
    val tiles: List<String> = emptyList(),
    val scopeHeader: Px,
    val spectrum: Px,
    val waterfall: Px,
    val ruler: Px,
    val keys: Px,
    /** Keys per page, the page key included, as the radio's bar has them. */
    val keysPerPage: Int,
    /**
     * False on the compact (phone, portrait) face: there is room for every
     * key in [keyRows] rows, and paging on a touch screen is a press spent
     * on nothing.
     */
    val pagedKeys: Boolean = true,
    val keyRows: Int = 1,
    /** How many trailing digits are drawn small (none on the default 10 Hz readouts). */
    val smallDigits: Int,
    /** What the last digit is worth: 10 Hz on all three radios' default screens. */
    val resolutionHz: Long,
    val meterStyle: MeterStyle,
    /** Where S9 sits along the meter's bar (or arc), 0..1, measured off the manuals' screens. */
    val s9At: Float,
    /** The scope trace's colour (ARGB): Icom's own default "current waveform" colour for that model. */
    val trace: Long,
)

/**
 * Every action a key can carry, in the order the pages show them: the
 * radio's MODE, FIL, BAND and our SET on the first page, then A/B, F-INP
 * and the decoders. P.AMP, ATT and MUTE had keys until the face's own
 * badges became selectable and SET arrived; they are in the settings now
 * (and P.AMP/ATT a press on their badge, MUTE play/pause on a TV).
 */
val KEY_ACTIONS: List<SoftKey> = listOf(
    SoftKey("MODE", KeyAction.Mode), SoftKey("FIL", KeyAction.Filter),
    SoftKey("BAND", KeyAction.Band), SoftKey("SET", KeyAction.Settings),
    SoftKey("A/B", KeyAction.Vfo), SoftKey("F-INP", KeyAction.Tune),
    SoftKey("DECODE", KeyAction.Decodes), SoftKey("SSTV", KeyAction.Sstv),
)

/** The actions this radio offers: all of them, whatever the radio (each one is something every Icom has). */
@Suppress("UNUSED_PARAMETER")
fun keyActions(st: RadioState?): List<SoftKey> = KEY_ACTIONS
/**
 * The words the 9700 and 705 print on the mode line: the preamp setting ("P.AMP", a level's name, or on a
 * 9700 "EXT" / "P.AMP+EXT" for its external preamp) and "ATT", each only while on. EXT alone used to
 * draw nothing, because the internal level was 0 (Brian, 10/10/2026).
 */
fun ampWords(st: RadioState): List<String> =
    listOfNotNull(st.preampName.takeIf { st.preampOn }, "ATT".takeIf { (st.att ?: 0) > 0 })

/** Their size in the radio's pixels, and the gap between them as a fraction of it. */
fun ampTextSize(r: Px): Float = r.h * 0.8f
const val AMP_GAP_K = 0.6f

/** How many pages the face's key bar has, for this radio's actions. */
fun pageCount(face: Face, st: RadioState? = null): Int =
    if (!face.pagedKeys) 1 else (keyActions(st).size + face.keysPerPage - 2) / (face.keysPerPage - 1)

/**
 * The keys a face shows on [page]: the radio's own paged row - a page key
 * first ("< 1 >" on the 9700 and 705, "<MENU1>" on the 7610), then as many
 * actions as the row has room for - or, on a compact face, every action.
 */
fun faceKeys(face: Face, page: Int, st: RadioState? = null): List<SoftKey> {
    val actions = keyActions(st)
    if (!face.pagedKeys) return actions
    val per = face.keysPerPage - 1
    val p = Math.floorMod(page, pageCount(face, st))
    val label = if (face.key == "ic-7610") "<MENU${p + 1}>" else "< ${p + 1} >"
    return listOf(SoftKey(label, KeyAction.Page)) + actions.drop(p * per).take(per)
}

/**
 * Every key any page of any face can show, compact ones included, for a radio that has every optional
 * action: for the "nothing here transmits" test.
 */
fun allKeys(): List<SoftKey> {
    val all = RadioState(preamps = listOf("P.AMP"), attenuators = listOf(10))
    return FACES.values.flatMap { f -> (0 until pageCount(f, all)).flatMap { faceKeys(f, it, all) } } +
        FACES.values.flatMap { faceKeys(compactOf(it, 0.5f), 0, all) }
}

/**
 * Where the radio's raw meter reading (0-255: 0 = S0, 120 = S9, 241 =
 * S9+60, TV-API.md section 5) sits along a bar whose S9 point is at
 * [s9At]: linear in raw units either side of S9, as Icom's scale is.
 */
fun meterFraction(raw: Int?, s9At: Float): Float? {
    if (raw == null) return null
    val r = raw.coerceIn(0, 241)
    return if (r <= 120) s9At * r / 120f else s9At + (1f - s9At) * (r - 120) / 121f
}

/** The S scale's labels and where each sits (0..1): odd S-units in white, +20/+40/+60 dB in red. */
fun sScale(s9At: Float): List<Triple<Float, String, Boolean>> =
    (1..9 step 2).map { n -> Triple(s9At * n / 9f, n.toString(), false) } +
        listOf(20, 40, 60).map { d -> Triple(s9At + (1f - s9At) * d / 60f, if (d == 60) "+60dB" else "+$d", true) }

/** How a printed mark sits on its point of the scale. */
enum class MarkAnchor { Centre, Right }

/**
 * One printed mark on a bar meter's scale: where along the bar (0..1), its
 * text, red (the dB half) or white, how it is anchored to its point, and its
 * size as a fraction of the meter's label size ([BarParts.size]).
 */
data class MeterMark(
    val at: Float,
    val label: String,
    val red: Boolean,
    val anchor: MarkAnchor = MarkAnchor.Centre,
    val sizeK: Float = 0.9f,
)

/**
 * A bar meter's parts, in the radio's pixels relative to the face's main
 * block (the meter rect already shifted into it): the bar, the scale's line
 * above it, the label size, and where the "S" / "Po" label ends - it is
 * drawn right-aligned against the bar, as the radio prints it.
 */
data class BarParts(val barX: Float, val barW: Float, val scaleY: Float, val barY: Float, val barH: Float,
                    val size: Float, val labelRight: Float)

fun barParts(face: Face, r: Px = face.band.meter): BarParts {
    val size = r.h * 0.42f
    val barX = r.x + face.band.meterLabelW
    return BarParts(barX = barX, barW = r.w - face.band.meterLabelW, scaleY = r.y + r.h * 0.28f,
        barY = r.y + r.h * 0.55f, barH = r.h * 0.40f, size = size, labelRight = barX - size * 0.4f)
}

/**
 * What a bar meter prints over its bar, by how much bar there is:
 *  - under 120 px, the 9700's own mini meter: 1, 5 and 9 with red dots for
 *    the dB marks (RADIO-DISPLAYS.md 1.5);
 *  - under 200 px, every odd S unit and +20/+40/+60 in red, the dB marks a
 *    size smaller because "+40" is wider than "7" and the dB half has three
 *    marks where the S half has five. This is the 9700's TV face since Brian
 *    asked (10 October 2026) for its meter to be longer and to read
 *    "1 3 5 7 9" like the radio's full meter;
 *  - wider (the IC-705's), the radio's full scale ending "+60dB".
 * The last mark is anchored at the bar's right end so it never overhangs
 * the bar towards the frequency. While transmitting it is the Po scale.
 */
fun barMarks(face: Face, tx: Boolean): List<MeterMark> {
    val barW = barParts(face).barW
    if (tx) return listOf(MeterMark(0f, "0", false), MeterMark(0.25f, "25", false), MeterMark(0.5f, "50", false),
        MeterMark(1f, "100%", false, if (barW < 120f) MarkAnchor.Centre else MarkAnchor.Right))
    val odd = (1..9 step 2).map { n -> MeterMark(face.s9At * n / 9f, n.toString(), false) }
    fun db(d: Int) = face.s9At + (1f - face.s9At) * d / 60f
    return when {
        barW < 120f -> odd.filter { it.label in setOf("1", "5", "9") } +
            listOf(20, 40, 60).map { MeterMark(db(it), "\u2022", true) }
        barW < 200f -> odd + listOf(20, 40, 60).map {
            MeterMark(db(it), "+$it", true, if (it == 60) MarkAnchor.Right else MarkAnchor.Centre, sizeK = 0.72f)
        }
        else -> odd + listOf(20, 40, 60).map {
            if (it == 60) MeterMark(db(it), "+60dB", true, MarkAnchor.Right) else MeterMark(db(it), "+$it", true)
        }
    }
}

/**
 * A frequency the way the radio prints it: MHz, a dot, kHz, a dot, then the
 * rest to the face's resolution, with the last [Face.smallDigits] of it
 * small - "433.000.00" on the 9700's default 10 Hz screen; with 1 Hz
 * resolution and one small digit, "146.520.00" + "0" as the manual shows
 * it. Null is dashes in the same shape, so the readout does not jump when
 * the radio speaks.
 */
fun splitFrequency(hz: Long?, face: Face): Pair<String, String> {
    val restDigits = when (face.resolutionHz) { 1L -> 3; 10L -> 2; 100L -> 1; else -> 0 }
    val small = face.smallDigits.coerceAtMost(restDigits)
    if (hz == null) {
        val rest = "-".repeat(restDigits)
        return "---.---." + rest.dropLast(small) to rest.takeLast(small)
    }
    val mhz = hz / 1_000_000
    val khz = (hz / 1_000) % 1_000
    val rest = if (restDigits == 0) "" else ((hz % 1_000) / face.resolutionHz).toString().padStart(restDigits, '0')
    return "$mhz.${"%03d".format(khz)}." + rest.dropLast(small) to rest.takeLast(small)
}

/**
 * How wide the frequency readout may be, in the radio's pixels: from the
 * left of the dial (the frequency's own touch and focus area) to the
 * readout's right edge, less a margin so a fitted readout's first digit
 * does not touch the TV's focus ring, which is drawn on the dial's edge.
 * Every face lays its dial out clear of the meter and badges (FacesTest
 * holds it to that), so a readout kept inside this never runs under them.
 */
fun readoutRoom(face: Face): Float = face.band.freqRight - face.band.dial.x - READOUT_MARGIN

private const val READOUT_MARGIN = 4f

/**
 * What a readout [naturalWidth] wide at the face's own digit size is scaled
 * by to fit [room]: 1 when it fits, as every 2 m and 70 cm frequency does,
 * and smaller only for one that does not - the 9700's 23 cm band has a
 * fourth MHz digit ("1296.904.00"), which at the face's 44 px ran its
 * leading 1 under the S meter on Brian's Fire TV (10 October 2026). Shrunk
 * rather than moved left or cut: the radio itself keeps the readout's right
 * edge and baseline and fits the digits to the box.
 */
fun readoutScale(naturalWidth: Float, room: Float): Float =
    if (naturalWidth <= room || naturalWidth <= 0f) 1f else room / naturalWidth

/**
 * Which digit the tuning step moves, counted from the right of the readout
 * (0 = its last digit): the radio puts a small mark over the digit its dial
 * moves (the "quick tuning" mark, over the 1 kHz digit by default). A step
 * that is not a power of ten (5 kHz, 12.5 kHz) marks the digit of its
 * leading figure; a step finer than the readout, none.
 */
fun stepDigitFromRight(step: Long, face: Face): Int? {
    if (step < face.resolutionHz) return null
    var place = 1L
    while (place * 10 <= step) place *= 10
    var i = 0
    var p = face.resolutionHz
    while (p < place) { p *= 10; i++ }
    return i
}

/* ---- the faces ----------------------------------------------------------

   Numbers are the radio's pixels, measured off Icom's own pictures of each
   screen; RADIO-DISPLAYS.md section 5 lists each with its source. */

/**
 * IC-9700: 4.3 in, 480 x 272 (inferred: Icom does not state it). The
 * SPECTRUM SCOPE screen: a mode button, a filter button and a mini meter on
 * the left and the frequency on the right, then the scope and five keys.
 * Measured off the Basic manual's cover photo (RADIO-DISPLAYS.md 1), which
 * shows Dualwatch - main above sub. With the sub band left out (see the top
 * of this file) its 64 px are shared: 24 to the main block, so the
 * frequency grows from 38 to 44 px, and 40 to the scope, whose spectrum and
 * waterfall were a cramped 31 and 24 px. The status row, ruler and keys
 * are where the radio has them.
 *
 * Left of the frequency is not quite the radio's. Its mini meter left a
 * 46 px label column with one "S" in it at the far right, so on Brian's
 * Fire TV (10 October 2026) the meter sat hard against the frequency box
 * with an empty space to its left, and printed only 1, 5, 9. He asked for
 * it centred between the screen's left edge and the box, and longer, with
 * more numbers. So the label column is just wide enough for "Po" (the
 * wider of its two labels), the bar takes the rest - 129 px, enough for
 * 1 3 5 7 9 +20 +40 +60 (barMarks) - and the printed meter, from the S to
 * the bar's end (7..146), and the mode/filter badges above it (5..146) are
 * both centred on the 150 px left of the dial. FacesTest holds it to that.
 */
private val IC9700 = Face(
    key = "ic-9700", name = "IC-9700", w = 480f, h = 272f,
    status = Px(0f, 0f, 480f, 22f), clockRight = 414f, textSize = 12f,
    txBox = Px(1f, 3f, 40f, 17f),
    main = Px(0f, 24f, 480f, 86f),
    band = BandLayout(
        mode = Px(5f, 4f, 83f, 27f), filter = Px(92f, 4f, 54f, 27f), filterBoxed = true,
        meter = Px(0f, 40f, 146f, 24f), meterLabelW = 17f,
        freqRight = 388f, freqBaseline = 72f, freqSize = 44f, smallSize = 30f,
        vfoRight = 479f, vfoTop = 26f, labelSize = 13f,
        // The dial starts below the P.AMP/ATT row (it reached the block's top, so on the TV its focus
        // ring drew round the badges as though they were part of the frequency); it still holds the
        // readout and the step mark over it, which is all its touch and focus are for.
        dial = Px(150f, 26f, 246f, 58f),
        amp = Px(154f, 6f, 120f, 18f),
    ),
    scopeHeader = Px(0f, 114f, 480f, 12f),
    spectrum = Px(1f, 127f, 478f, 46f),
    waterfall = Px(1f, 173f, 478f, 47f),
    ruler = Px(1f, 221f, 478f, 17f),
    keys = Px(1f, 241f, 478f, 30f), keysPerPage = 5,
    smallDigits = 0, resolutionHz = 10, meterStyle = MeterStyle.Bar, s9At = 0.5f,
    trace = 0xFFACBFBF,
)

/**
 * IC-705: 4.3 in, 480 x 272 (inferred), one receiver. Mode and filter on
 * the second row, the frequency larger than the 9700's, the bar meter under
 * it, then the scope and five keys. Measured off the Basic manual's cover
 * photo (RADIO-DISPLAYS.md 3).
 */
private val IC705 = Face(
    key = "ic-705", name = "IC-705", w = 480f, h = 272f,
    status = Px(0f, 0f, 480f, 30f), clockRight = 420f, textSize = 12f,
    txBox = Px(5f, 11f, 37f, 16f),
    main = Px(0f, 31f, 480f, 122f),
    band = BandLayout(
        mode = Px(5f, 2f, 81f, 26f), filter = Px(92f, 2f, 54f, 26f), filterBoxed = true,
        meter = Px(38f, 88f, 250f, 28f), meterLabelW = 20f,
        freqRight = 383f, freqBaseline = 81f, freqSize = 58f, smallSize = 38f,
        vfoRight = 475f, vfoTop = 41f, labelSize = 13f,
        dial = Px(128f, 20f, 263f, 66f),
        amp = Px(152f, 2f, 120f, 17f),
    ),
    scopeHeader = Px(3f, 157f, 474f, 13f),
    spectrum = Px(3f, 170f, 474f, 33f),
    waterfall = Px(3f, 203f, 474f, 25f),
    ruler = Px(3f, 228f, 474f, 15f),
    keys = Px(1f, 246f, 478f, 26f), keysPerPage = 5,
    smallDigits = 0, resolutionHz = 10, meterStyle = MeterStyle.Bar, s9At = 0.47f,
    trace = 0xFFACBFBF,
)

/**
 * IC-7610: 7 in, 800 x 480 (Icom's own figure). The radio puts main and
 * sub side by side, each with its analogue meter above the VFO/mode/filter
 * line and the frequency; the function tiles down the left; the scope
 * beside the tiles; seven keys across the bottom. Measured off the Basic
 * manual pp. 1-7 and 5-1 (RADIO-DISPLAYS.md 2).
 *
 * With the sub band left out, the main block takes the whole width right
 * of the tiles (704 px, from 366) at the same height, keeping the radio's
 * order top to bottom - meter, VFO/mode/filter line, frequency. Height is
 * what limits the needle meter (its arcs are drawn from the meter's height),
 * so it grows only from 106 to 130 px, at the block's left as before
 * (centred, its top labels ran into the clock); the width goes to the
 * frequency, 76 px digits to the right of the mode line instead of 56 px
 * under it. The TX box moves down with the line it sits beside. Tiles,
 * scope and keys are unchanged.
 */
private val IC7610 = Face(
    key = "ic-7610", name = "IC-7610", w = 800f, h = 480f,
    status = Px(80f, 0f, 720f, 34f), clockRight = 484f, textSize = 15f,
    txBox = Px(89f, 146f, 37f, 23f),
    main = Px(96f, 36f, 704f, 214f),
    band = BandLayout(
        mode = Px(46f, 138f, 83f, 31f), filter = Px(135f, 138f, 44f, 31f), filterBoxed = false,
        meter = Px(0f, 0f, 400f, 130f),
        freqRight = 690f, freqBaseline = 206f, freqSize = 76f, smallSize = 52f,
        vfoRight = 42f, vfoTop = 144f, labelSize = 16f,
        dial = Px(190f, 140f, 504f, 72f),
    ),
    tileColumn = Px(0f, 0f, 80f, 440f),
    tiles = listOf("ANT", "P.AMP", "ATT", "IP+", "AGC", "VOX", "COMP"),
    scopeHeader = Px(95f, 263f, 692f, 16f),
    spectrum = Px(95f, 280f, 692f, 83f),
    waterfall = Px(95f, 363f, 692f, 66f),
    ruler = Px(95f, 429f, 692f, 15f),
    keys = Px(3f, 447f, 794f, 32f), keysPerPage = 7,
    smallDigits = 0, resolutionHz = 10, meterStyle = MeterStyle.Needle, s9At = 0.5f,
    trace = 0xFFD9F1F7,
)

/**
 * The same face for a phone held upright (RADIO-DISPLAYS.md 5.4): the
 * radio's own top half - status row, bands, meter - kept exactly, then the
 * scope drawn taller than the radio's because a phone is taller than it is
 * wide, and every key in two rows instead of pages. Same pixels, same
 * colours, same order top to bottom; only heights change. [aspect] is the
 * room's width over its height.
 */
fun compactOf(face: Face, aspect: Float): Face {
    val h = (face.w / aspect).coerceAtLeast(face.h)
    val keysH = face.keys.h * 2 + face.w * 0.01f
    val sh = face.scopeHeader
    val left = h - sh.bottom - 1f - face.ruler.h - 1f - 3f - keysH - 2f
    val specH = left * 0.30f
    val spec = Px(face.spectrum.x, sh.bottom + 1f, face.spectrum.w, specH)
    val wf = Px(face.waterfall.x, spec.bottom, face.waterfall.w, left - specH)
    val ruler = Px(face.ruler.x, wf.bottom + 1f, face.ruler.w, face.ruler.h)
    val keys = Px(face.keys.x, ruler.bottom + 3f, face.w - 2 * face.keys.x, keysH)
    return face.copy(
        h = h, spectrum = spec, waterfall = wf, ruler = ruler, keys = keys,
        pagedKeys = false, keyRows = 2, keysPerPage = KEY_ACTIONS.size,
        // The tile column would run the full height of a tall phone for seven names with no values.
        tileColumn = face.tileColumn?.copy(h = sh.y - 4f),
    )
}

/**
 * What on the face can take focus (D-pad) or a tap, besides the keys: the
 * places the radio's own touch screen answers - its mode and filter
 * badges, the P.AMP/ATT indicators, the VFO label, the frequency, the
 * scope. Brian (10 October 2026): "make more of the UI selectable".
 */
sealed interface FaceSpot {
    data object Dial : FaceSpot
    data object Mode : FaceSpot
    data object Filter : FaceSpot
    /** P.AMP and ATT: the 9700/705's indicator slot, or the 7610's two tiles. */
    data object Amp : FaceSpot
    data object Vfo : FaceSpot
    /** The spectrum and the waterfall together. */
    data object Scope : FaceSpot
    data class Key(val index: Int) : FaceSpot
}

/** Where [spot] is on [face], in the radio's pixels: what is focused and ringed, or touched. */
fun spotRect(face: Face, spot: FaceSpot): Px? {
    val m = face.main
    val b = face.band
    return when (spot) {
        // The IC-705's dial reaches up beside its filter badge (a touch there is the frequency on the
        // radio); as a place to focus or tap here it stops at the badge, so the two never share a pixel.
        FaceSpot.Dial -> {
            val d = b.dial
            val f = b.filter
            val clipped = if (d.x < f.right && d.y < f.bottom && f.y < d.bottom) Px(f.right + 2f, d.y, d.right - f.right - 2f, d.h) else d
            clipped.shift(m.x, m.y)
        }
        FaceSpot.Mode -> b.mode.shift(m.x, m.y)
        FaceSpot.Filter -> b.filter.shift(m.x, m.y)
        FaceSpot.Amp -> b.amp?.shift(m.x, m.y) ?: face.tileColumn?.let { col ->
            // The 7610's P.AMP and ATT tiles, second and third down its column.
            val i = face.tiles.indexOf("P.AMP").takeIf { it >= 0 } ?: return null
            val h = col.h / face.tiles.size
            val n = if (face.tiles.getOrNull(i + 1) == "ATT") 2 else 1
            Px(col.x, col.y + i * h, col.w - 6f, h * n)
        }
        FaceSpot.Vfo -> {
            val w = b.labelSize * VFO_W_K
            Px(m.x + b.vfoRight - w, m.y + b.vfoTop - 1f, w, b.labelSize * 1.4f)
        }
        FaceSpot.Scope -> Px(face.spectrum.x, face.spectrum.y, face.spectrum.w, face.waterfall.bottom - face.spectrum.y)
        is FaceSpot.Key -> keyRectsOf(face).getOrNull(spot.index)
    }
}

/** How wide "VFO A" is, in label sizes: the VFO label's touch and focus area. */
private const val VFO_W_K = 3.6f

/** The key bar's rectangles (also RadioFaceView's keyRects, which draws them). */
internal fun keyRectsOf(face: Face): List<Px> {
    val k = face.keys
    val gap = face.w * 0.01f
    val perRow = (face.keysPerPage + face.keyRows - 1) / face.keyRows
    val w = (k.w - gap * (perRow - 1)) / perRow
    val h = (k.h - gap * (face.keyRows - 1)) / face.keyRows
    return (0 until face.keysPerPage).map { i ->
        Px(k.x + (i % perRow) * (w + gap), k.y + (i / perRow) * (h + gap), w, h)
    }
}

/**
 * The face reshaped to fill a room of [aspect] (width over height) rather
 * than sit in it at the radio's own proportions with bars beside or above.
 * Brian (10 October 2026): the Fire TV showed the 9700's face with a large
 * border, the radio's 480 x 272 inside the TV's 5% safe area and a help line
 * under it; he wants the screen used, the scope and waterfall taking the room.
 *
 * Nothing is stretched: text and badges keep the radio's pixel sizes, as a
 * uniform scale needs. What changes is where the room goes -
 *  - a wider room ([widerOf]) widens the status row, the band block, the
 *    scope and the key bar, the frequency (and anything at the block's
 *    right) moving right with the edge, the badges and meter staying left;
 *  - a taller one ([tallerOf]) gives the height to the spectrum and the
 *    waterfall, the keys staying at the bottom and paged as the radio's are;
 *  - much taller with [allowCompact] (a phone held upright) is [compactOf],
 *    every key in two rows.
 * Within half a percent it is the face itself: a TV's room is within a few
 * percent of the 9700's own shape anyway.
 */
fun fitFace(face: Face, aspect: Float, allowCompact: Boolean = false): Face {
    if (!aspect.isFinite() || aspect <= 0f) return face
    val own = face.w / face.h
    return when {
        allowCompact && aspect < own * COMPACT_BELOW -> compactOf(face, aspect)
        aspect > own * (1 + FIT_SLACK) -> widerOf(face, face.h * aspect)
        aspect < own * (1 - FIT_SLACK) -> tallerOf(face, face.w / aspect)
        else -> face
    }
}

/** Below this fraction of the face's own aspect a touch screen gets the compact face (a phone upright). */
const val COMPACT_BELOW = 0.92f
private const val FIT_SLACK = 0.005f

/** The face [w] wide instead of its own width; see [fitFace]. */
fun widerOf(face: Face, w: Float): Face {
    val dw = w - face.w
    if (dw <= 0f) return face
    val b = face.band
    // The VFO label sits at the block's right on the 9700 and 705 and moves with the frequency; on the
    // 7610 it is left of the mode badge and stays.
    val vfoRight = if (b.vfoRight > face.main.w / 2) b.vfoRight + dw else b.vfoRight
    fun wide(r: Px) = r.copy(w = r.w + dw)
    return face.copy(
        w = w, status = wide(face.status), clockRight = face.clockRight + dw,
        main = wide(face.main),
        band = b.copy(freqRight = b.freqRight + dw, dial = b.dial.shift(dw, 0f), vfoRight = vfoRight),
        scopeHeader = wide(face.scopeHeader), spectrum = wide(face.spectrum), waterfall = wide(face.waterfall),
        ruler = wide(face.ruler), keys = wide(face.keys),
    )
}

/**
 * The face [h] tall instead of its own height; see [fitFace]. The spectrum
 * takes about a third of the extra and the waterfall the rest: a taller
 * waterfall is more history, which is what the room is best spent on.
 */
fun tallerOf(face: Face, h: Float): Face {
    val dh = h - face.h
    if (dh <= 0f) return face
    val ds = dh * 0.35f
    val wf = face.waterfall
    return face.copy(
        h = h,
        spectrum = face.spectrum.copy(h = face.spectrum.h + ds),
        waterfall = Px(wf.x, wf.y + ds, wf.w, wf.h + dh - ds),
        ruler = face.ruler.shift(0f, dh), keys = face.keys.shift(0f, dh),
    )
}

val FACES: Map<String, Face> = listOf(IC9700, IC7610, IC705).associateBy { it.key }

/** The face for a model key, or null for a radio with no face of its own (it keeps the plain layout). */
fun faceFor(modelKey: String?): Face? = modelKey?.lowercase()?.let { FACES[it] }
