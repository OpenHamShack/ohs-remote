package com.openhamshack.remote.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.openhamshack.remote.api.Qo100
import com.openhamshack.remote.api.Qo100Signal
import com.openhamshack.remote.api.Qo100Waterfall
import com.openhamshack.remote.api.Receiver

/**
 * The signal -> receive tune mapping, against the box console's QO-100 page
 * (appliance/www/js/qo100.js qoTune and its click handlers), and the
 * /api/qo100 parser against real answers:
 *
 *  - qo100-live.json: the real BATC feed through tools/fakebox.py (the box's
 *    own qo100_poll), 9 October 2026 04:12 - a quiet transponder, beacon only;
 *  - qo100-busy.json: the box's recorded BATC frame (testdata/qo100-wb-frame.bin)
 *    with synthetic stations added, so every kind of signal is present.
 */
class Qo100TuneTest {
    private fun fixture(name: String) =
        Qo100.parse(JSONObject(javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()))

    private fun rx(id: Int = 1, mhz: Double? = 437.0, offset: Double? = 0.0, sr: Double? = 333.0) = Receiver(
        id = id, tuner = "minitiouner", kind = "", enabled = true, locked = true, label = "", mer = null,
        marginDb = null, name = "", provider = "", modcod = null, video = null, frequencyMHz = mhz,
        symbolRateKs = sr, offsetMHz = offset, heard = id == 1,
    )

    private fun sig(f: Double, lo: Double, hi: Double, sr: Int, beacon: Boolean = false) =
        Qo100Signal(f, lo, hi, sr, sr, 6.0, beacon, false)

    @Test
    fun theRealFeedParses() {
        val q = fixture("qo100-live.json")
        assertTrue(q.live)
        assertEquals("", q.error)
        assertEquals(10490.5, q.startMHz, 0.0)
        assertEquals(10499.5, q.stopMHz, 0.0)
        assertEquals(8089.5, q.uplinkOffsetMHz, 0.0)
        assertEquals(461, q.spectrum.size)
        assertEquals("BATC QO-100 wideband spectrum monitor", q.source)
        assertEquals("https://eshail.batc.org.uk/wb/", q.sourceUrl)
        val beacon = q.signals.single { it.beacon }
        assertEquals(QO100_BEACON_MHZ, beacon.freqMHz, 0.0)
        assertEquals(QO100_BEACON_KS, beacon.tuneSymbolRateKs)
        assertTrue(q.channels[333]!!.isNotEmpty())
        val wf = q.waterfall!!
        assertTrue(wf.rows.isNotEmpty())
        assertEquals(461, wf.rows.first().size)
    }

    @Test
    fun aBusyTransponderParsesEveryKindOfSignal() {
        val q = fixture("qo100-busy.json")
        assertTrue(q.signals.size >= 6)
        assertTrue(q.signals.any { it.overPower })
        assertTrue(q.signals.any { it.tuneSymbolRateKs == 33 })
        // The tune rate is a standard one even where the measured width is not.
        q.signals.forEach { assertTrue(it.tuneSymbolRateKs in SR_QO100) }
    }

    @Test
    fun aSignalIsTunedAtItsFrequencyAndStandardRateThroughTheQo100Lnb() {
        // A receiver on a terrestrial frequency goes onto the QO-100 LNB's LO.
        val t = qo100Tune(rx(), sig(10494.75, 10494.5, 10495.0, 333), 9750.0)
        assertEquals(RxTune(1, 10494.75, 333.0, 9750.0), t)
    }

    @Test
    fun theBeaconIsTunedAtFifteenHundredInTheSameTune() {
        // Brian's Fire TV: 10491.5 with the receiver left at 333 kS never
        // locks. Frequency, rate and LO go together in one POST /api/rx/tune.
        val beacon = fixture("qo100-live.json").signals.single { it.beacon }
        assertEquals(RxTune(1, 10491.5, 1500.0, 9750.0), qo100Tune(rx(sr = 333.0), beacon, 9750.0))
        assertEquals(RxTune(1, 10491.5, 1500.0, 9750.0), qo100Tune(rx(sr = 333.0), QO100_BEACON_MHZ, QO100_BEACON_KS, 9750.0))
    }

    @Test
    fun aReceiverAlreadyOnQo100KeepsItsOwnLo() {
        // An LNB set to something other than 9750 (a drifting or different
        // LNB) stays: the console keeps cfg.rxOffsetMHz while onQo().
        val t = qo100Tune(rx(mhz = 10491.5, offset = 9749.98), sig(10498.25, 10498.1, 10498.4, 125), 9750.0)
        assertEquals(9749.98, t.offsetMHz, 0.0)
        assertEquals(125.0, t.symbolRateKs, 0.0)
    }

    @Test
    fun anOffsetBelowTenGigahertzIsNotAnLnb() {
        // HamTV's downconverter is an offset too: not to be taken for the LNB.
        val hamtv = rx(id = 2, mhz = 2395.0, offset = 1000.0)
        assertFalse(onQo100(hamtv))
        assertEquals(9750.0, qo100Tune(hamtv, 10494.75, 333, 9750.0).offsetMHz, 0.0)
        assertEquals(2, qo100Tune(hamtv, 10494.75, 333, 9750.0).id)
        // And the box's own LNB setting is used when it is not 9750.
        assertEquals(9739.0, qo100Tune(rx(), 10494.75, 333, 9739.0).offsetMHz, 0.0)
    }

    @Test
    fun aTapFindsTheSignalWithinAHundredKilohertz() {
        val sigs = listOf(sig(10491.5, 10490.8, 10492.26, 1500, beacon = true), sig(10497.75, 10497.73, 10497.77, 33))
        assertEquals(10497.75, qo100SignalAt(sigs, 10497.84)!!.freqMHz, 0.0)
        assertEquals(10497.75, qo100SignalAt(sigs, 10497.66)!!.freqMHz, 0.0)
        assertNull(qo100SignalAt(sigs, 10497.9))
        assertTrue(qo100SignalAt(sigs, 10492.3)!!.beacon)
        assertNull(qo100SignalAt(emptyList(), 10494.0))
    }

    @Test
    fun aTapOnAnEmptyWaterfallOffersTheRasterAndAStationsRate() {
        // 12.5 kHz raster; the receiver's own rate...
        assertEquals(10494.7375 to 250, qo100Offer(10494.741, rx(sr = 250.0)))
        // ...unless it is still on the beacon's 1500 kS: most stations are 333 or less.
        assertEquals(333, qo100Offer(10494.741, rx(sr = 1500.0)).second)
        assertEquals(333, qo100Offer(10494.741, rx(sr = null)).second)
    }

    @Test
    fun aSignalSaysWhichReceiversAreOnIt() {
        val s = sig(10494.75, 10494.5, 10495.0, 333)
        val on = rx(id = 1, mhz = 10494.76, offset = 9750.0, sr = 333.0)
        val wrongRate = rx(id = 2, mhz = 10494.75, offset = 9750.0, sr = 250.0)
        val noLnb = rx(id = 3, mhz = 10494.75, offset = 0.0, sr = 333.0)
        assertEquals(listOf(1), qo100TunedOn(s, listOf(on, wrongRate, noLnb)))
    }

    @Test
    fun labelsAreTheConsoles() {
        val rateAt = { sr: Int, f: String -> "$sr kS $f" }
        assertEquals("Beacon", qo100GraphLabel(sig(10491.5, 1.0, 2.0, 1500, beacon = true), "Beacon", "[over-power]", rateAt))
        assertEquals("125 kS 10498.250", qo100GraphLabel(sig(10498.25, 1.0, 2.0, 125), "Beacon", "[over-power]", rateAt))
        assertEquals(Qo100State.Live(1, false), qo100State(true, "", 0.2, 1, false, true))
        assertEquals(Qo100State.Failed("No internet: cannot look up eshail.batc.org.uk"),
            qo100State(false, "No internet: cannot look up eshail.batc.org.uk", null, 0, false, true))
        assertEquals(Qo100State.Stale(9), qo100State(false, "", 9.2, 0, false, true))
        assertEquals(Qo100State.Connecting, qo100State(false, "", null, 0, false, false))
    }

    @Test
    fun theWaterfallPutsTheNewestRowAtTheTopAndStartsAgainWhenTold() {
        val q = fixture("qo100-live.json")
        val wf = Qo100Waterfall()
        wf.add(q.waterfall!!)
        assertEquals(461, wf.width)
        assertEquals(q.waterfall!!.maxRows, wf.height)
        assertEquals(q.waterfall!!.seq, wf.seq)
        val newest = q.waterfall!!.rows.last()
        assertEquals(Qo100Waterfall.LUT[newest[0].toInt() and 0xFF], wf.pixels[0])
        // Below the rows received, nothing yet.
        assertEquals(Qo100Waterfall.BACKGROUND, wf.pixels[wf.width * (q.waterfall!!.rows.size + 1)])
        // One more row: everything moves down one.
        val before = wf.pixels.copyOf()
        val next = q.waterfall!!.copy(seq = q.waterfall!!.seq + 1, full = false, rows = listOf(ByteArray(461) { 200.toByte() }))
        wf.add(next)
        assertEquals(Qo100Waterfall.LUT[200], wf.pixels[5])
        assertEquals(before[7], wf.pixels[wf.width + 7])
        // full: the box no longer has what follows our seq - start again.
        wf.add(next.copy(full = true, rows = emptyList()))
        assertTrue(wf.pixels.all { it == Qo100Waterfall.BACKGROUND })
    }
}
