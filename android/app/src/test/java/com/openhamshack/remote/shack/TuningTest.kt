package com.openhamshack.remote.shack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The D-pad's tuning arithmetic, the scope's folding, and how a typed address is tried as a controller. */
class TuningTest {
    @Test fun stepsLandOnTheGrid() {
        assertEquals(10_489_800_000.0, Tuning.stepped(10_489_703_000.0, 100_000, 1), 0.0)
        assertEquals(10_489_700_000.0, Tuning.stepped(10_489_703_000.0, 100_000, -1), 0.0)
        assertEquals(10_489_700_100.0, Tuning.stepped(10_489_700_000.0, 100, 1), 0.0)
        assertEquals(10_489_699_900.0, Tuning.stepped(10_489_700_000.0, 100, -1), 0.0)
        assertEquals(145_525_000.0, Tuning.stepped(145_500_000.0, 12_500, 2), 0.0)
    }

    @Test fun stepsStopAtTheDialLimits() {
        // NB's spec: the dial is the transponder (10 489.48-10 490.02 MHz).
        assertEquals(10_490_020_000.0, Tuning.stepped(10_490_019_000.0, 5_000, 1, 10_489_480_000.0, 10_490_020_000.0), 0.0)
        assertEquals(10_489_480_000.0, Tuning.stepped(10_489_480_050.0, 100, -3, 10_489_480_000.0, 10_490_020_000.0), 0.0)
    }

    @Test fun stepSizesPerKindHeldAtTheEnds() {
        assertEquals(100, Tuning.defaultStep(Kind.QO100NB))
        assertEquals(1_000, Tuning.defaultStep(Kind.RADIO))
        assertEquals(500, Tuning.nextStep(Kind.QO100NB, 100, 1))
        assertEquals(5_000, Tuning.nextStep(Kind.QO100NB, 5_000, 1))
        assertEquals(10, Tuning.nextStep(Kind.QO100NB, 10, -1))
        assertEquals(12_500, Tuning.nextStep(Kind.RADIO, 5_000, 1))
        // A step not in this kind's list starts again from the default.
        assertEquals(1_000, Tuning.nextStep(Kind.QO100NB, 12_500, 2))
    }

    /**
     * Brian, Tab S9, 13/10/2026: "I can change the step up but not down" on
     * Radio. nextStep was right both ways; the screen had one button that
     * only called it with +1. What a press of each of the screen's two
     * buttons does, from every rung, for each kind.
     */
    @Test fun everyStepLadderWalksUpToItsEndAndBackDown() {
        for (kind in listOf(Kind.RADIO, Kind.QO100NB)) {
            val ladder = Tuning.steps(kind)
            assertEquals(ladder, ladder.sorted())
            // Up from the smallest reaches every rung in order, then holds at the top.
            var s = ladder.first()
            val up = mutableListOf(s)
            repeat(ladder.size + 2) { s = Tuning.nextStep(kind, s, 1); up += s }
            assertEquals(ladder + List(3) { ladder.last() }, up)
            // Down from the top reaches every rung in reverse, then holds at the bottom.
            val down = mutableListOf(s)
            repeat(ladder.size + 2) { s = Tuning.nextStep(kind, s, -1); down += s }
            assertEquals(ladder.reversed() + List(3) { ladder.first() }, down)
            // And one rung down from any rung but the first is the rung below.
            for (i in 1 until ladder.size) assertEquals(ladder[i - 1], Tuning.nextStep(kind, ladder[i], -1))
        }
    }

    @Test fun modesCycleThroughTheSpecChoices() {
        val m = listOf("usb", "lsb", "cw")
        assertEquals("lsb", Tuning.nextMode(m, "usb", 1))
        assertEquals("usb", Tuning.nextMode(m, "cw", 1))
        assertEquals("cw", Tuning.nextMode(m, "usb", -1))
        assertEquals("usb", Tuning.nextMode(m, "fm", 1))
        assertEquals("fm", Tuning.nextMode(emptyList(), "fm", 1))
    }

    @Test fun foldKeepsTheStrongestBinOfEachColumn() {
        val db = FloatArray(8) { -100f }.also { it[5] = -40f }
        val f = Scope.fold(db, 4)
        assertEquals(listOf(-100f, -100f, -40f, -100f), f.toList())
        // Fewer bins than columns: every column is filled.
        assertEquals(listOf(-1f, -1f, -2f, -2f), Scope.fold(floatArrayOf(-1f, -2f), 4).toList())
    }

    @Test fun scopeRowsScrollAndRestartWhenTheSpanMoves() {
        val sc = Scope(width = 4, height = 3)
        val f1 = SpectrumFrame("wide", 1000.0, 10.0, floatArrayOf(-100f, -100f, -100f, -40f))
        sc.add(f1)
        sc.add(f1)
        assertTrue(sc.pixels[4] != Scope.BACKGROUND)          // row 1 is the first frame
        assertEquals(1000.0 + 40.0 * 0.5, 1000.0 + sc.xOf(1020.0)!! * 40.0, 1e-9)
        assertNull(sc.xOf(2000.0))
        // A shift under half a column (the beacon correction drifting) keeps the rows.
        sc.add(SpectrumFrame("wide", 1004.0, 10.0, floatArrayOf(-100f, -100f, -100f, -40f)))
        assertTrue(sc.pixels[8] != Scope.BACKGROUND)
        sc.add(SpectrumFrame("zoom", 1010.0, 5.0, floatArrayOf(-100f, -100f, -100f, -40f)))
        assertEquals(Scope.BACKGROUND, sc.pixels[4])           // older rows cleared
        assertEquals(4, sc.version)
    }

    @Test fun anAddressWithoutAPortIsTriedAt80() {
        assertFalse(ShackNav.hasPort("198.51.100.106"))
        assertFalse(ShackNav.hasPort("http://ohs-box.local/"))
        assertTrue(ShackNav.hasPort("10.0.2.2:18600"))
        assertTrue(ShackNav.hasPort("[fd00::1]:80"))
        assertFalse(ShackNav.hasPort("fd00::1"))
    }
}
