package ru.workinprogress.kachok.engine.choke

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The choker's input: what a peer is doing now, not what it once did. */
class RateMeterTest {
    @Test
    fun aSteadyStreamReadsAsItsRate() {
        val meter = RateMeter(windowSeconds = 10)
        (0 until 10).forEach { second -> meter.add(1_000, second * 1_000L) }
        assertEquals(1_000, meter.bytesPerSecond(9_000))
    }

    @Test
    fun aPeerThatStopsDecaysToNothing() {
        // The whole reason for a window: a peer that was fast an hour ago should lose its slot.
        val meter = RateMeter(windowSeconds = 10)
        (0 until 10).forEach { second -> meter.add(1_000, second * 1_000L) }
        assertTrue(meter.bytesPerSecond(9_000) > 0)
        assertEquals(0, meter.bytesPerSecond(30_000), "ten seconds of silence is a rate of zero")
    }

    @Test
    fun aBurstIsAveragedOverTheWindowRatherThanReportedWhole() {
        val meter = RateMeter(windowSeconds = 10)
        meter.add(10_000, 0)
        assertEquals(1_000, meter.bytesPerSecond(0), "10 000 bytes in a ten-second window is 1 000 a second")
    }

    @Test
    fun aMeterNobodyUsedReadsZero() {
        assertEquals(0, RateMeter().bytesPerSecond(123_456))
    }

    @Test
    fun timeMovingBackwardsDoesNotCorruptTheWindow() {
        // Not a hypothetical: the session's clock is elapsed-since-start, and a caller that passes
        // a stale value should get a stale answer rather than a wrong one.
        val meter = RateMeter(windowSeconds = 5)
        meter.add(500, 10_000)
        val before = meter.bytesPerSecond(10_000)
        assertEquals(before, meter.bytesPerSecond(9_000))
    }
}
