package ru.workinprogress.kachok.engine.choke

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The arithmetic B-22's limits rest on. */
class TokenBucketTest {
    private val megabyte = 1024L * 1024L

    @Test
    fun aBucketStartsFullAndSpendsWhatItHolds() {
        val bucket = TokenBucket(megabyte)

        assertTrue(bucket.take(megabyte), "a second's worth is what a full bucket holds")
        assertFalse(bucket.take(1), "and there is nothing left after it")
    }

    @Test
    fun timePaysForTheNextSecond() {
        val bucket = TokenBucket(megabyte)
        bucket.take(megabyte)

        bucket.refill(500.milliseconds)

        assertEquals(megabyte / 2, bucket.available, "half a second buys half a second's bytes")
        assertTrue(bucket.take(megabyte / 2))
        assertFalse(bucket.take(1))
    }

    @Test
    fun anIdleBucketDoesNotSaveUpForABurst() {
        // The reason for the cap: a download paused for an hour would otherwise resume at an hour's
        // worth of bytes at once, which is exactly what someone setting a limit is preventing.
        val bucket = TokenBucket(megabyte)
        bucket.take(megabyte)

        bucket.refill(1.seconds * 60)

        assertEquals(megabyte, bucket.available, "a minute of idling is still worth one second")
    }

    @Test
    fun takingIsAllOrNothingBecauseThereIsNoHalfABlock() {
        val bucket = TokenBucket(1000)
        assertTrue(bucket.take(600))

        assertFalse(bucket.take(600), "600 of the 400 left is not 400 bytes sent")
        assertEquals(400, bucket.available, "and the refusal spent nothing")
        assertTrue(bucket.take(400))
    }

    @Test
    fun zeroMeansNoLimitRatherThanNoBytes() {
        // The default, and the difference matters: read as "zero bytes a second" it would be a
        // client that never asks for anything and never serves anything.
        val bucket = TokenBucket(0)

        assertTrue(bucket.isUnlimited)
        assertTrue(bucket.take(Long.MAX_VALUE / 2))
        assertTrue(bucket.take(Long.MAX_VALUE / 2))
        assertEquals(Long.MAX_VALUE, bucket.available)
    }

    /**
     * A new rate on a bucket that is already running, which is what a settings screen does.
     *
     * The balance is clamped rather than reset: raising a limit must not hand out a second's worth
     * of the *old* rate on top of what is already there, and lowering one must not leave a bucket
     * holding more than its new depth.
     */
    @Test
    fun retuningLowersTheDepthAndWhatIsInIt() {
        val bucket = TokenBucket(bytesPerSecond = 1_000)
        assertEquals(1_000, bucket.available, "it starts full")
        bucket.retune(100)
        assertEquals(100, bucket.bytesPerSecond)
        assertEquals(100, bucket.available, "a smaller bucket cannot hold what the bigger one did")
    }

    @Test
    fun retuningUpwardsDoesNotHandOutTheOldRateAsWell() {
        val bucket = TokenBucket(bytesPerSecond = 100)
        assertTrue(bucket.take(100), "spend what it holds")
        assertEquals(0, bucket.available)
        bucket.retune(1_000)
        assertEquals(0, bucket.available, "raising the limit refilled the bucket for free")
        bucket.refill(1.seconds)
        assertEquals(1_000, bucket.available, "and a second at the new rate fills it")
    }

    /** Retuning to zero is lifting the limit, not setting it to nothing. */
    @Test
    fun retuningToZeroLiftsTheLimit() {
        val bucket = TokenBucket(bytesPerSecond = 100)
        assertTrue(!bucket.isUnlimited)
        bucket.retune(0)
        assertTrue(bucket.isUnlimited)
        assertTrue(bucket.take(Long.MAX_VALUE / 2), "an unlimited bucket refuses nothing")
    }

    /** And the rate a bucket already has costs a comparison and changes nothing. */
    @Test
    fun retuningToTheSameRateLeavesTheBalanceAlone() {
        val bucket = TokenBucket(bytesPerSecond = 1_000)
        assertTrue(bucket.take(400))
        val before = bucket.available
        bucket.retune(1_000)
        assertEquals(before, bucket.available)
    }
}
