package io.github.youndie.kachok.engine.runtime

import io.github.youndie.kachok.engine.choke.RateBudget
import kotlin.time.TimeSource

/**
 * One rate limit for every torrent of a set
 * ([B-151](../../../../../../../../docs/backlog/B-151-one-limit-for-the-whole-client.md)).
 *
 * A [io.github.youndie.kachok.engine.choke.TokenBucket] belongs to one session and is refilled by
 * that session's timer. This one is spent by all of them at once, from as many threads as there are
 * sessions, so it is locked, and it refills itself from the clock on every question rather than on
 * anybody's tick — eleven sessions each adding a tick's worth would be eleven times the limit.
 *
 * The depth is one second of the rate, as the session's own bucket: a burst after an idle minute is
 * a second's worth and no more.
 */
public class SharedRateLimit(
    bytesPerSecond: Long,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : RateBudget {
    @Volatile
    public var bytesPerSecond: Long = bytesPerSecond
        private set

    private var balance: Long = capacityFor(bytesPerSecond)
    private var refilled = timeSource.markNow()

    override val isUnlimited: Boolean get() = bytesPerSecond <= 0

    override val available: Long
        @Synchronized get() {
            if (isUnlimited) return Long.MAX_VALUE
            refill()
            return balance
        }

    @Synchronized
    override fun take(bytes: Long): Boolean {
        if (isUnlimited) return true
        refill()
        if (bytes > balance) return false
        balance -= bytes
        return true
    }

    @Synchronized
    override fun giveBack(bytes: Long) {
        if (isUnlimited) return
        balance = minOf(capacityFor(bytesPerSecond), balance + bytes)
    }

    /** A new rate; the balance is clamped to the new depth rather than reset, as the session's. */
    @Synchronized
    public fun retune(newBytesPerSecond: Long) {
        if (newBytesPerSecond == bytesPerSecond) return
        refill()
        bytesPerSecond = newBytesPerSecond
        balance = balance.coerceAtMost(capacityFor(newBytesPerSecond))
    }

    private fun refill() {
        val now = timeSource.markNow()
        val nanos = (now - refilled).inWholeNanoseconds
        if (nanos <= 0) return
        val gained = (bytesPerSecond.toDouble() * nanos / NANOS_PER_SECOND).toLong()
        // Only time that bought something is spent: a question asked a microsecond after the last
        // would otherwise round its share to nothing and move the mark past it, and a busy client
        // would never refill at all.
        if (gained <= 0) return
        balance = minOf(capacityFor(bytesPerSecond), balance + gained)
        refilled = now
    }

    private fun capacityFor(rate: Long): Long = if (rate <= 0) 0 else rate

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
