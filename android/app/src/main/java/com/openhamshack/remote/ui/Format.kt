package com.openhamshack.remote.ui

import com.openhamshack.remote.api.BoxConfig
import com.openhamshack.remote.api.Receiver
import com.openhamshack.remote.R
import com.openhamshack.i18n.ShackFormat
import java.util.Locale

// The console's numbers, so an operator moving between the box's screen and
// the phone reads the same thing in both places. Pure functions: FormatTest
// holds them, and the iPad build can copy them line for line. The words
// around them come from the catalog, through I18n.kt.

/** Symbol rates by band, as the console's SR_SETS (sheets.js). */
val SR_QO100 = listOf(33, 66, 125, 250, 333, 500, 1000, 1500)
val SR_TERRESTRIAL = listOf(125, 250, 333, 500, 1000, 1500, 2000, 3000, 4000)

/**
 * The receiver window's terrestrial presets (index.html #rxtpresets). Its
 * fourth, the QO-100 beacon, lives on the QO-100 page with the rest of the
 * transponder (Qo100Page / TvQo100): the receive tuning here is terrestrial
 * only, no LNB, so a preset always means offset 0.
 */
data class Preset(val label: String, val mhz: Double)

val RX_PRESETS = listOf(
    Preset("146.50", 146.5),
    Preset("437.00", 437.0),
    Preset("1255.00", 1255.0),
)

fun mhz(v: Double?): String = if (v == null) "—" else String.format(Locale.ROOT, "%.3f", v)

/**
 * A QO-100 downlink frequency as I18N.md §4.1 has every OpenHamShack app
 * show one: in MHz with three decimals (kHz resolution, so a column of
 * them does not change width), the locale's decimal separator, never
 * grouped, and the unit after a no-break space - "10491.500 MHz", or
 * "10491,500 MHz" in German. The box reports MHz as a double; it is
 * rounded to whole Hz first, the unit ShackFormat counts in.
 */
fun downlinkText(mhz: Double, localeTag: String): String =
    ShackFormat.frequency(Math.round(mhz * 1_000_000), localeTag, unit = "MHz")

fun ks(v: Double?): String = if (v == null) "—" else if (v == Math.floor(v)) v.toLong().toString() else v.toString()

fun db(v: Double?): String = if (v == null) "—" else String.format(Locale.ROOT, "%.1f", v)

/** The console's onAirClock: m:ss, or h:mm:ss from an hour. */
fun clock(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    val m = s / 60
    return if (m >= 60) String.format(Locale.ROOT, "%d:%02d:%02d", m / 60, m % 60, s % 60)
    else String.format(Locale.ROOT, "%d:%02d", m, s % 60)
}

/**
 * The LNB offset a typed receive frequency means (receivers.js rxOffsetFor):
 * 10 GHz and up is through the LNB (the receiver's own, else the QO-100
 * one); anything at or below the LNB's LO is not; between, keep what it had.
 */
fun rxOffsetFor(mhz: Double, currentOffset: Double?, qoLo: Double): Double {
    val lnb = currentOffset ?: 0.0
    return if (mhz >= 10000) (if (lnb > 0) lnb else qoLo) else if (mhz <= lnb) 0.0 else lnb
}

/**
 * The symbol rate to send with a typed or nudged frequency, or null to
 * leave the receiver's as it is. A rate is only changed when the frequency
 * crosses into a band it does not belong to: onto the LNB, the beacon
 * (10491.5 ± 0.25) is always 1500 kS, and a terrestrial-only rate (2000,
 * 3000, 4000) becomes the nearest QO-100 one; off it, a QO-100-only rate
 * (33, 66) becomes the nearest terrestrial one. A real tuner told 10491.5
 * at 333 kS never locks, and nothing on screen said why.
 */
fun rateForFrequency(mhz: Double, offsetMHz: Double, currentKs: Double?): Int? {
    val qo = offsetMHz > 0
    if (qo && kotlin.math.abs(mhz - 10491.5) <= 0.25) return if (currentKs?.toInt() == 1500) null else 1500
    val rates = if (qo) SR_QO100 else SR_TERRESTRIAL
    val cur = currentKs ?: return null
    if (cur <= 0 || cur.toInt() in rates) return null
    return rates.minBy { kotlin.math.abs(kotlin.math.ln(it / cur)) }
}

fun srRatesFor(offsetMHz: Double?): List<Int> = if ((offsetMHz ?: 0.0) > 0) SR_QO100 else SR_TERRESTRIAL

/** Station, MODCOD, picture: what is being received (data, never translated). */
fun programmeLine(r: Receiver): String = if (r.locked != true) "" else listOfNotNull(
    r.name.ifBlank { null },
    r.modcod,
    r.video?.let { v -> if (v.width != null && v.height != null) "${v.width}×${v.height} ${v.codec ?: ""}".trim() else null },
).joinToString(" · ")

/**
 * The transmit standard and MODCOD as the box names them ("DVB-S2 QPSK
 * 3/4"): identifiers from the standard, so data rather than language. The
 * words around it (MHz, kS, dB) are I18n.kt's txText.
 */
fun txModcod(c: BoxConfig): String =
    (if (c.mode == "dvbs") "DVB-S" else "DVB-S2") + " ${c.constellation.uppercase()} ${c.fec}"

/** The catalog entry naming a picture source, or null for one this app does not know (shown as the box sent it). */
@androidx.annotation.StringRes
fun sourceRes(source: String): Int? = when (source) {
    "testcard" -> R.string.wb_source_testcard
    "webcam" -> R.string.wb_source_webcam
    "file" -> R.string.wb_source_file
    "stream" -> R.string.wb_source_stream
    else -> null
}

/**
 * One step of the TV's up/down: hear another receiver, or tune the heard
 * one to a preset. Both change the box (POST /api/rx/select or
 * /api/rx/tune), so both need the PIN.
 */
sealed interface TvChannel {
    // Its title is said by I18n.kt's channelTitle, from the catalog.
    data class Hear(val id: Int, val tuner: String) : TvChannel
    data class Tune(val preset: Preset) : TvChannel
}

/**
 * What up/down steps through on a TV: the box's receivers (only when there
 * is more than one to choose between), then the tablet's receive presets
 * ([RX_PRESETS], the console's #rxtpresets) - the same list, so a channel
 * means the same on the TV, the tablet and the box. Empty without the PIN:
 * the stream URL follows the receiver the box has as heard, so even
 * "another receiver" is a change made at the box, and view only changes
 * nothing.
 */
fun tvChannels(receivers: List<Receiver>, canTune: Boolean): List<TvChannel> {
    if (!canTune) return emptyList()
    val rx = receivers.filter { it.enabled }
    val hear = if (rx.size > 1) rx.map { TvChannel.Hear(it.id, it.tuner) } else emptyList()
    return hear + RX_PRESETS.map { TvChannel.Tune(it) }
}

/**
 * Where in [list] the box is now, so the first press steps from what is on
 * screen rather than from the top: the preset the heard receiver is tuned
 * to, else its own entry; -1 when it is on neither (a typed frequency).
 */
fun tvChannelIndex(list: List<TvChannel>, heard: Receiver?): Int {
    if (heard == null) return -1
    val f = heard.frequencyMHz
    val preset = list.indexOfFirst { it is TvChannel.Tune && f != null && kotlin.math.abs(it.preset.mhz - f) < 0.001 }
    if (preset >= 0) return preset
    return list.indexOfFirst { it is TvChannel.Hear && it.id == heard.id }
}

/** The index one press of up (-1) or down (+1) lands on, wrapping; from -1, down is the first and up the last. */
fun tvStep(size: Int, current: Int, by: Int): Int = when {
    size == 0 -> -1
    current !in 0 until size -> if (by > 0) 0 else size - 1
    else -> Math.floorMod(current + by, size)
}

/**
 * Whether an NB or Radio screen keeps the display awake: while receive
 * audio is playing and not muted (a TV's screensaver must not cover a
 * QSO being listened to), or while this device holds the transmitter
 * (the screen going off would unkey it). Released when audio stops, is
 * muted, or the screen closes - KeepScreenOn leaves with the composable.
 */
fun keepAwake(audioRate: Int, muted: Boolean, keyed: Boolean): Boolean = keyed || (audioRate > 0 && !muted)
