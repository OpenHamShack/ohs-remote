package com.openhamshack.remote.shack

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where each mode listens relative to the dial, and where a tap on a
 * signal puts the dial. The case that started it, on the real box
 * (09/10/2026): a FreeDV signal with the dial on its middle decoded
 * nothing until the dial went 1.5 kHz lower.
 */
class ModeAudioTest {
    private fun state(vararg kv: Pair<String, Any>) = EngineState(JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } })

    @Test fun aTapOnAnAudioModemSignalPutsItAt1500HzAboveTheDial() {
        val signal = 10_489_641_234.0
        for (mode in listOf("freedv", "hsmodem", "ft8", "js8")) {
            val dial = ModeAudio.dialFor(signal, ModeAudio.of(state("mode" to mode)))
            assertEquals(mode, signal - 1500.0, dial, 10.0)
        }
        // The engine's FreeDV registry entries are freedv_<mode>.
        assertEquals(1500.0, ModeAudio.of("freedv_700d", null, null, null, null, null)!!.centre, 0.0)
    }

    @Test fun cwGoesByTheEnginesPitchAndSsbByItsFilter() {
        assertEquals(10_489_500_000.0 - 650.0,
            ModeAudio.dialFor(10_489_500_000.0, ModeAudio.of(state("mode" to "cw", "cwPitch" to 650.0, "cwWidth" to 400.0))), 0.0)
        val usb = ModeAudio.of(state("mode" to "usb", "filterLo" to 200.0, "filterHi" to 2800.0))!!
        assertEquals(Passband(200.0, 2800.0, 1500.0), usb)
        assertEquals(Passband(-2800.0, -200.0, -1500.0), ModeAudio.of(state("mode" to "lsb", "filterLo" to 200.0, "filterHi" to 2800.0)))
        // Radio's FM: the channel, centred on the dial.
        assertEquals(145_500_000.0, ModeAudio.dialFor(145_500_004.0, ModeAudio.of(state("mode" to "fm"))), 0.0)
    }

    @Test fun aModeWithNoKnownWindowTunesWhereTapped() {
        assertNull(ModeAudio.of(state("mode" to "wspr")))
        assertEquals(7_040_120.0, ModeAudio.dialFor(7_040_123.0, null), 0.0)
    }

    @Test fun theServersPassbandWinsOverTheGuessAndKeepsTheModesCentre() {
        // Wide FM as the server has it: 180 kHz, centred on the dial.
        assertEquals(Passband(-90_000.0, 90_000.0, 0.0),
            ModeAudio.of(state("mode" to "wfm", "rxFilterLo" to -90_000.0, "rxFilterHi" to 90_000.0)))
        // An operator's narrow SSB filter, though filterLo/filterHi say otherwise.
        assertEquals(Passband(300.0, 2400.0, 1350.0),
            ModeAudio.of(state("mode" to "usb", "filterLo" to 200.0, "filterHi" to 2800.0, "rxFilterLo" to 300.0, "rxFilterHi" to 2400.0)))
        // FT8's signal still belongs 1500 Hz up: the edges are the server's, the centre the modem's.
        assertEquals(10_489_540_000.0, ModeAudio.dialFor(10_489_541_500.0,
            ModeAudio.of(state("mode" to "ft8", "rxFilterLo" to 200.0, "rxFilterHi" to 2800.0))), 0.0)
        // A mode the table has no window for is shaded from the server and centred on it.
        assertEquals(Passband(200.0, 2800.0, 1500.0), ModeAudio.of(state("mode" to "wspr", "rxFilterLo" to 200.0, "rxFilterHi" to 2800.0)))
    }

    @Test fun anOlderServerStillGetsTheGuess() {
        assertEquals(Passband(-4000.0, 4000.0, 0.0), ModeAudio.of(state("mode" to "am")))
        // One edge alone, or edges that make no window, are not a passband.
        assertEquals(Passband(-6250.0, 6250.0, 0.0), ModeAudio.of(state("mode" to "fm", "rxFilterLo" to 0.0, "rxFilterHi" to 0.0)))
        assertEquals(Passband(-6250.0, 6250.0, 0.0), ModeAudio.of(state("mode" to "fm", "rxFilterLo" to -5000.0)))
    }
}
