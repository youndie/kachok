package ru.workinprogress.kachok.ui.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * The strings the design draws, produced from the numbers behind them.
 *
 * Every expectation here was read out of `docs/design/kachok Phase 2 Desktop.dc.html`, and the
 * inputs are what the engine would have had to hold for the design to have written that. A
 * formatter checked against its own idea of the rules is a formatter checked against nothing.
 */
class FiguresTest {
    private val kib = 1024L
    private val mib = kib * kib
    private val gib = mib * kib

    @Test
    fun aByteSizeIsThreeSignificantFigures() {
        // The design's own eight, in the order its rows carry them.
        assertEquals("3.70 GiB", Figures.bytes((3.70 * gib).toLong()))
        assertEquals("1.22 GiB", Figures.bytes((1.22 * gib).toLong()))
        assertEquals("14.6 GiB", Figures.bytes((14.6 * gib).toLong()))
        assertEquals("2.41 GiB", Figures.bytes((2.41 * gib).toLong()))
        assertEquals("8.03 GiB", Figures.bytes((8.03 * gib).toLong()))
        assertEquals("412 MiB", Figures.bytes(412 * mib))
        assertEquals("48.2 MiB", Figures.bytes((48.2 * mib).toLong()))
        assertEquals("63.0 MiB", Figures.bytes((63.0 * mib).toLong()))
    }

    /** `63.0 MiB`, not `63 MiB`: three figures means the zero is drawn. */
    @Test
    fun aRoundSizeKeepsItsThirdFigure() {
        assertEquals("63.0 MiB", Figures.bytes(63 * mib))
        assertEquals("1.00 GiB", Figures.bytes(gib))
    }

    @Test
    fun bytesBelowAKibibyteAreJustBytes() {
        assertEquals("0 B", Figures.bytes(0))
        assertEquals("999 B", Figures.bytes(999))
    }

    @Test
    fun aRateIsAWholeNumberGroupedInThrees() {
        assertEquals("15 736", Figures.rate(15_736 * kib))
        assertEquals("4 312", Figures.rate(4_312 * kib))
        assertEquals("812", Figures.rate(812 * kib))
        assertEquals("96", Figures.rate(96 * kib))
        assertEquals("0", Figures.rate(0))
        assertEquals("1 480", Figures.rate(1_480 * kib))
    }

    @Test
    fun aRatioLosesADecimalOnceItReachesTen() {
        assertEquals("0.11", Figures.ratio(11, 100))
        assertEquals("2.07", Figures.ratio(207, 100))
        assertEquals("11.4", Figures.ratio(1140, 100))
        assertEquals("18.9", Figures.ratio(1890, 100))
        assertEquals("0.00", Figures.ratio(0, 100))
    }

    /** Nothing downloaded is not a ratio of zero; there is no denominator yet. */
    @Test
    fun aRatioWithNothingDownloadedIsADash() {
        assertEquals(Figures.DASH, Figures.ratio(uploaded = 1000, downloaded = 0))
    }

    @Test
    fun anEtaPadsItsSecondsOnlyBehindMinutes() {
        assertEquals("3m 20s", Figures.eta(200.seconds))
        assertEquals("8m 04s", Figures.eta(484.seconds))
        assertEquals("14s", Figures.eta(14.seconds))
        assertEquals("47s", Figures.eta(47.seconds))
        assertEquals("1h 01m", Figures.eta(3660.seconds))
    }

    @Test
    fun theStatusBarsHeapIsUsedOverMax() {
        assertEquals("heap 41 / 128 MiB", Figures.heap(41 * mib, 128 * mib))
    }

    @Test
    fun thePeersCellIsTheDesignsThreeNumbers() {
        assertEquals("24/4 · 61", Figures.peers(24, 4, 61))
        assertEquals("0/0 · 0", Figures.peers(0, 0, 0))
    }

    @Test
    fun aPercentageOfNothingIsADashRatherThanZero() {
        assertEquals(Figures.DASH, Figures.percent(0, 0))
        assertEquals("78%", Figures.percent(78, 100))
        assertEquals(0f, Figures.fraction(0, 0))
    }
}
