package com.openhamshack.remote.shack

import kotlin.math.abs

// Where in the receive audio each mode listens, relative to the dial: what
// the scope shades as the passband, and how far below a signal the dial
// goes when the operator taps on it. Found the hard way on the real box
// (09/10/2026): Brian put the dial on the middle of a FreeDV signal and
// nothing decoded until it was moved 1.5 kHz lower - the engine's audio
// modems expect the signal centred 1500 Hz above the dial (USB), as
// WSJT-X's dial sits below its waterfall. Pure, for the JVM tests.

/** The audio window a mode decodes, Hz relative to the dial; [centre] is where a signal should sit. */
data class Passband(val lo: Double, val hi: Double, val centre: Double)

object ModeAudio {
    const val WFM_HALF_HZ = 90_000.0

    /**
     * The digital modes' own windows, copied from openhamshack-nb's
     * ohscore/modes/__init__.py MODES (offset_hz, passband_hz) at 8498fe9,
     * because the store API does not publish them: `api/spec` lists the
     * modes' names only. Remove this table when the engine sends the
     * registry (an open item: REMOTE-CONTRACT 12b). FreeDV is the registry's
     * freedv_* entries, all alike.
     */
    private val DIGITAL = mapOf(
        "ft8" to Passband(200.0, 3000.0, 1500.0), "ft4" to Passband(200.0, 3000.0, 1500.0),
        "q65" to Passband(200.0, 3000.0, 1500.0), "js8" to Passband(200.0, 3000.0, 1500.0),
        "rtty" to Passband(830.0, 1170.0, 1000.0), "psk31" to Passband(970.0, 1030.0, 1000.0),
        "psk63" to Passband(940.0, 1060.0, 1000.0), "psk125" to Passband(875.0, 1125.0, 1000.0),
        "freedv" to Passband(300.0, 2700.0, 1500.0), "sstv" to Passband(1100.0, 2300.0, 1900.0),
        "hsmodem" to Passband(100.0, 2950.0, 1500.0),
    )

    /**
     * The passband for [state]'s mode. The voice modes' come from the
     * engine's own state keys, as ohscore/dsp/demod.py `passband` reads
     * them (filterLo/filterHi for SSB, cwPitch/cwWidth, amWidth: ±4 kHz by
     * default since openhamshack-nb 87177e8, for airband and broadcast), so an
     * operator's filter setting is what is drawn; FM is a 12.5 kHz channel,
     * wide FM a 180 kHz one.
     * Null for a mode this build has no window for: nothing is drawn and a
     * tap tunes the dial to where it was tapped.
     */
    fun of(state: EngineState): Passband? {
        val guess = of(state.mode, state.raw("filterLo"), state.raw("filterHi"),
            state.raw("cwPitch"), state.raw("cwWidth"), state.raw("amWidth"))
        return served(state.raw("rxFilterLo"), state.raw("rxFilterHi"), guess) ?: guess
    }

    /**
     * The server's own passband (`rxFilterLo`/`rxFilterHi`, Hz either side
     * of the dial, in every mode: openhamshack-nb SERVER-API "Wide FM",
     * 23/10/2026) wins over the guess from the filter keys, which stays
     * only for a server too old to send it. A signal belongs in the middle
     * of the edges, except where this table knows a modem's own centre that
     * is not its window's middle (FT8 and FreeDV 1500 Hz up in a 200-3000 Hz
     * window): the server's edges are the filter's, not the modem's. Null
     * without both edges, or with edges that make no window.
     */
    fun served(lo: Double?, hi: Double?, guess: Passband?): Passband? {
        if (lo == null || hi == null || hi <= lo) return null
        val own = guess?.takeIf { abs(it.centre - (it.lo + it.hi) / 2) > 0.5 && it.centre in lo..hi }?.centre
        return Passband(lo, hi, own ?: (lo + hi) / 2)
    }

    fun of(mode: String, filterLo: Double?, filterHi: Double?, cwPitch: Double?, cwWidth: Double?, amWidth: Double?): Passband? {
        val lo = filterLo ?: 300.0
        val hi = filterHi ?: 2700.0
        return when (mode) {
            "usb" -> Passband(lo, hi, (lo + hi) / 2)
            "lsb" -> Passband(-hi, -lo, -(lo + hi) / 2)
            "cw" -> {
                val p = cwPitch ?: 700.0
                val w = cwWidth ?: 500.0
                Passband(p - w / 2, p + w / 2, p)
            }
            "am" -> (amWidth ?: 4000.0).let { Passband(-it, it, 0.0) }
            "fm" -> Passband(-6250.0, 6250.0, 0.0)
            // Broadcast FM: ±75 kHz deviation and 15 kHz audio make a channel
            // of about 180 kHz (Carson; RADIO-LISTEN-CONTRACT §1), drawn as such.
            "wfm" -> Passband(-WFM_HALF_HZ, WFM_HALF_HZ, 0.0)
            else -> DIGITAL[mode] ?: DIGITAL[mode.substringBefore('_')]
        }
    }

    /**
     * The dial for a signal at [signalHz]: the signal lands on the mode's
     * centre (an FT8 or FreeDV signal 1500 Hz above the dial, a CW one at
     * the pitch), to 10 Hz - not the step's grid: a 1 kHz step would undo
     * the 700 Hz CW pitch it has just allowed for. A bookmark's frequency is
     * already a dial and never goes through here.
     */
    fun dialFor(signalHz: Double, band: Passband?): Double {
        val dial = signalHz - (band?.centre ?: 0.0)
        return Math.round(dial / 10.0) * 10.0
    }
}
