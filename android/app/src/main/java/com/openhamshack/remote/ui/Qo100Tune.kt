package com.openhamshack.remote.ui

import com.openhamshack.remote.api.Qo100Signal
import com.openhamshack.remote.api.Receiver
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

// How a signal on the QO-100 spectrum becomes a receive tune: the box
// console's QO-100 page (appliance/www/js/qo100.js, qoTune and the
// spectrum's click handler) reduced to pure functions, so the tablet, the
// TV and the iPad (docs/REMOTE-CONTRACT.md) tune exactly as the box's own
// screen does. Qo100TuneTest holds them.

/** The QO-100 beacon, as the box's detector offers it: its published frequency and rate. */
const val QO100_BEACON_MHZ = 10491.5
const val QO100_BEACON_KS = 1500

/** The console's band plan lines on the downlink: beacon only below, narrow only above. */
const val QO100_BEACON_EDGE_MHZ = 10492.5
const val QO100_NARROW_EDGE_MHZ = 10497.0

/** One POST /api/rx/tune. */
data class RxTune(val id: Int, val frequencyMHz: Double, val symbolRateKs: Double, val offsetMHz: Double)

/**
 * The console's onQo(): the receiver is on QO-100's downlink through the
 * LNB. An offset alone is not enough - HamTV's downconverter is an offset
 * too, and its LO is no LNB's - so the frequency must be 10 GHz or more.
 */
fun onQo100(r: Receiver): Boolean = (r.offsetMHz ?: 0.0) > 0 && (r.frequencyMHz ?: 0.0) >= 10000

/**
 * Tune receiver [r] to [freqMHz] at [symbolRateKs] through the LNB: the
 * receiver's own LO when it is already on QO-100 (an LNB that is not
 * 9750 has been set there), else the QO-100 LNB's (status.config.qo100LnbMHz).
 *
 * The console applies onQo() to receiver 1 and "any offset" to the others;
 * here onQo() is applied to every receiver, which is the console's stated
 * intent (a receiver left on HamTV's downconverter keeps its LO otherwise).
 */
fun qo100Tune(r: Receiver, freqMHz: Double, symbolRateKs: Int, qoLo: Double): RxTune =
    RxTune(r.id, freqMHz, symbolRateKs.toDouble(), if (onQo100(r)) r.offsetMHz!! else qoLo)

/** A detected signal: at its frequency and the standard rate nearest its width. */
fun qo100Tune(r: Receiver, s: Qo100Signal, qoLo: Double): RxTune = qo100Tune(r, s.freqMHz, s.tuneSymbolRateKs, qoLo)

/**
 * The signal under a tap at [mhz] on the spectrum or the waterfall: the
 * first whose occupied width, widened by 100 kHz either side, holds it (a
 * finger is wide and a 33 kS signal is 3 px), or null.
 */
fun qo100SignalAt(signals: List<Qo100Signal>, mhz: Double): Qo100Signal? =
    signals.firstOrNull { mhz >= it.lowMHz - 0.1 && mhz <= it.highMHz + 0.1 }

/**
 * A tap on the waterfall where nothing is found now (a trace from a minute
 * ago, or noise): the frequency on the 12.5 kHz raster and the receiver's
 * own rate - unless it is still on the beacon's 1500 kS, since most stations
 * are 333 or less. Offered for the operator to confirm, never tuned unasked.
 */
fun qo100Offer(mhz: Double, current: Receiver): Pair<Double, Int> {
    val f = (mhz * 80).roundToLong() / 80.0
    val cur = current.symbolRateKs?.toInt()?.takeIf { it > 0 } ?: 333
    return f to (if (cur > 1000) 333 else cur)
}

/** The ids of [receivers] tuned to [s] now: on the LNB, within 20 kHz, at its tune rate. */
fun qo100TunedOn(s: Qo100Signal, receivers: List<Receiver>): List<Int> = receivers
    .filter { it.enabled && (it.offsetMHz ?: 0.0) > 0 }
    .filter { r -> r.frequencyMHz?.let { abs(it - s.freqMHz) < 0.02 } == true && r.symbolRateKs?.toInt() == s.tuneSymbolRateKs }
    .map { it.id }

/**
 * The spectrum's label over a signal: the beacon's word, the over-power
 * word, or the rate and frequency ("333 kS 10494.750"). The words are the
 * caller's, from the catalog; [rateAt] puts the rate (kS) and the
 * frequency (MHz, formatted here as the console does) into its sentence.
 */
fun qo100GraphLabel(s: Qo100Signal, beacon: String, overPower: String, rateAt: (Int, String) -> String): String = when {
    s.beacon -> beacon
    s.overPower -> overPower
    else -> rateAt(s.symbolRateKs, String.format(Locale.ROOT, "%.3f", s.freqMHz))
}

/**
 * What the state line above the spectrum says, in the console's words.
 * A state and not the sentence, so the sentence can come from the string
 * catalog (qo100StateText, with the signal count as a plural) while this
 * stays a pure function Qo100TuneTest can hold.
 */
sealed interface Qo100State {
    data object Connecting : Qo100State
    data class Live(val signals: Int, val outOfBand: Boolean) : Qo100State
    /** The box's own sentence for what went wrong (it comes from the box's catalog, not ours). */
    data class Failed(val error: String) : Qo100State
    data class Stale(val seconds: Long) : Qo100State
}

fun qo100State(live: Boolean, error: String, age: Double?, signals: Int, outOfBand: Boolean, loaded: Boolean): Qo100State = when {
    !loaded -> Qo100State.Connecting
    live -> Qo100State.Live(signals, outOfBand)
    error.isNotBlank() -> Qo100State.Failed(error)
    age != null -> Qo100State.Stale(age.roundToLong())
    else -> Qo100State.Connecting
}
