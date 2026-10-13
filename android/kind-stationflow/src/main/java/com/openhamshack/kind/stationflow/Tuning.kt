package com.openhamshack.kind.stationflow

import com.openhamshack.kind.stationflow.api.Band
import kotlin.math.abs

/*
 * The arithmetic behind the D-pad, kept out of the screens so it is tested
 * on the JVM: steps, snapping, mode and band cycling, the S-meter's words.
 */

/** The tuning steps OK cycles through, in Hz: fine SSB/CW, the FM channel rasters, a fast jump. */
val TUNE_STEPS = listOf(10L, 100L, 1_000L, 5_000L, 12_500L, 25_000L, 100_000L)

/** A step suited to [mode] when nothing has been chosen: channels for FM, 100 Hz for the rest. */
fun defaultStep(mode: String?): Long = when (mode?.substringBefore('-')?.uppercase()) {
    "FM", "DV" -> 12_500L
    "AM" -> 5_000L
    else -> 100L
}

fun nextStep(step: Long): Long = TUNE_STEPS[(TUNE_STEPS.indexOf(step).coerceAtLeast(-1) + 1) % TUNE_STEPS.size]

/**
 * Rewind/fast-forward: one step finer or coarser, stopping at the ends
 * rather than wrapping (the remote's standard map, REMOTE-KEYS.md: a held
 * coarser key must not land on 10 Hz). A step not on the ladder moves to
 * the nearest one in that direction.
 */
fun shiftStep(step: Long, by: Int): Long {
    if (by > 0) return TUNE_STEPS.firstOrNull { it > step } ?: TUNE_STEPS.last()
    if (by < 0) return TUNE_STEPS.lastOrNull { it < step } ?: TUNE_STEPS.first()
    return step
}

/**
 * One press of left/right: [dir] steps of [step] from [hz], landing on the
 * step's grid. Off the grid, the first press goes to the next grid line in
 * that direction rather than a whole step past it - so 145.512 on a 25 kHz
 * step goes to 145.525 or 145.500, as a radio's own dial does, and the
 * display reads in round numbers from then on.
 */
fun stepFrequency(hz: Long, step: Long, dir: Int): Long {
    require(step > 0)
    if (dir == 0) return hz
    val below = Math.floorDiv(hz, step) * step
    val onGrid = below == hz
    val out = when {
        dir > 0 -> (if (onGrid) hz else below) + step * dir
        else -> (if (onGrid) hz + step * dir else below + step * (dir + 1))
    }
    return out.coerceAtLeast(step)
}

/**
 * The modes the Mode chip steps through, most used first, from what this
 * radio has. "-D" (DATA) variants only where the radio offers DATA in that
 * mode. Reverse modes, RTTY and DD are left to the Tune panel's full list:
 * on a chip they are presses spent getting past them.
 */
fun quickModes(modes: List<String>, dataModes: List<String>): List<String> {
    val has = modes.map { it.uppercase() }.toSet()
    val data = dataModes.map { it.uppercase() }.toSet()
    return listOf("FM", "USB", "LSB", "CW", "AM", "USB-D", "FM-D", "DV").filter { m ->
        if (m.endsWith("-D")) m.removeSuffix("-D") in data else m in has
    }.ifEmpty { modes }
}

/** Every mode, for the Tune panel: the radio's, then DATA variants. */
fun allModes(modes: List<String>, dataModes: List<String>): List<String> =
    modes + dataModes.map { "$it-D" }

/** The next of [list] after [current] (wrapping), or the first when [current] is not in it. */
fun <T> cycle(list: List<T>, current: T?, by: Int = 1): T? {
    if (list.isEmpty()) return null
    val i = list.indexOf(current)
    if (i < 0) return list.first()
    return list[Math.floorMod(i + by, list.size)]
}

/** The band [hz] is in or nearest to, by the module's band buttons. */
fun bandOf(bands: List<Band>, hz: Long?): Band? {
    if (hz == null || bands.isEmpty()) return null
    return bands.minByOrNull { abs(it.hz - hz) }
}

/**
 * The S-meter in S-units, from the module's dB relative to S9 (Icom: 6 dB
 * per S-unit below S9). "S7", "S9", "S9+20".
 */
fun sUnits(dbOverS9: Double?): String {
    if (dbOverS9 == null) return "S–"
    if (dbOverS9 >= 0.5) return "S9+" + Math.round(dbOverS9)
    val s = (9 + dbOverS9 / 6.0).coerceIn(0.0, 9.0)
    return "S" + Math.round(s).toInt()
}

/** 145.174.000 - MHz.kHz.Hz in groups, the way a radio's display reads. */
fun formatHz(hz: Long?): String {
    if (hz == null) return "---.---.---"
    val mhz = hz / 1_000_000
    val khz = (hz / 1_000) % 1_000
    val h = hz % 1_000
    return "%d.%03d.%03d".format(mhz, khz, h)
}

/** "10 Hz", "12.5 kHz", "1 MHz". */
fun formatStep(step: Long): String = when {
    step >= 1_000_000 && step % 1_000_000 == 0L -> "${step / 1_000_000} MHz"
    step >= 1_000 -> (step / 1000.0).let { if (it == Math.floor(it)) "${it.toLong()} kHz" else "$it kHz" }
    else -> "$step Hz"
}

/** A frequency in MHz typed on a keypad, to Hz. Accepts "145.5", "145,5", "145500" (kHz), "1296.2". */
fun parseTypedFrequency(text: String): Long? {
    val t = text.trim().replace(',', '.')
    val v = t.toDoubleOrNull() ?: return null
    if (v <= 0) return null
    val hz = when {
        v < 10_000 -> v * 1_000_000          // MHz
        v < 10_000_000 -> v * 1_000          // kHz
        else -> v                            // Hz
    }
    return Math.round(hz)
}
