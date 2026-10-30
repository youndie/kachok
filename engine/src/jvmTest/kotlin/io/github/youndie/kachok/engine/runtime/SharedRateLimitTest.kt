package io.github.youndie.kachok.engine.runtime

import io.github.youndie.kachok.engine.choke.BothBudgets
import io.github.youndie.kachok.engine.choke.TokenBucket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/**
 * B-151: one limit spent by every torrent, refilled by the clock rather than by anybody's tick.
 */
class SharedRateLimitTest {
    private val clock = TestTimeSource()

    @Test
    fun aSecondsWorthIsSpentAndThenRefused() {
        val limit = SharedRateLimit(bytesPerSecond = 32_768, timeSource = clock)
        assertTrue(limit.take(16_384))
        assertTrue(limit.take(16_384))
        assertFalse(limit.take(1), "more than a second's worth went out at once")
    }

    @Test
    fun theClockRefillsItWhoeverAsks() {
        val limit = SharedRateLimit(bytesPerSecond = 32_768, timeSource = clock)
        assertTrue(limit.take(32_768))
        clock += 500.milliseconds
        assertEquals(16_384, limit.available, "half a second is half the rate")
        clock += 10_000.milliseconds
        assertEquals(32_768, limit.available, "an idle spell is worth one second and no more")
    }

    @Test
    fun aLowerLimitClampsWhatIsLeftAndZeroIsNone() {
        val limit = SharedRateLimit(bytesPerSecond = 1_000_000, timeSource = clock)
        limit.retune(10_000)
        assertEquals(10_000, limit.available)
        limit.retune(0)
        assertTrue(limit.isUnlimited)
        assertTrue(limit.take(Long.MAX_VALUE / 2))
    }

    @Test
    fun whenTheClientsLimitRefusesTheSessionKeepsItsOwnTokens() {
        val own = TokenBucket(bytesPerSecond = 100_000)
        val shared = SharedRateLimit(bytesPerSecond = 10_000, timeSource = clock)
        val both = BothBudgets(own, shared)
        assertTrue(both.take(10_000))
        assertFalse(both.take(10_000), "the client's limit was not applied")
        assertEquals(90_000, own.available, "the refused bytes were taken from the session anyway")
        assertEquals(0, both.available, "the smaller of the two is what may be spent")
    }
}
