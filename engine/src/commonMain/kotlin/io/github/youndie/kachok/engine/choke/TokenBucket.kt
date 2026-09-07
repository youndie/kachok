package io.github.youndie.kachok.engine.choke

import kotlin.time.Duration

/**
 * A rate limit, as tokens that accrue with time and are spent by bytes.
 *
 * The alternative — slowing the socket down — does not work in either direction and for two
 * different reasons. Reading slowly from a peer does not stop it sending: the bytes arrive at the
 * kernel regardless and sit in the receive buffer, so the uplink is used and the only thing saved
 * is the copy into this process. Writing slowly to a peer blocks a virtual thread inside a socket
 * write, which is a connection this client cannot cancel and a buffer it cannot reclaim. What a
 * BitTorrent client actually controls is *asking*: a block never requested is never sent, and a
 * `request` never answered costs the peer a timeout and nothing else.
 *
 * Not thread safe, and does not need to be: it lives inside the session's confined dispatcher
 * alongside the peer table and the picker.
 */
public class TokenBucket(
    /** Bytes a second. Zero or less means no limit at all, and every [take] then succeeds. */
    bytesPerSecond: Long,
    /**
     * How much may be spent at once after an idle period, as a multiple of one second's worth.
     *
     * One second, because the burst a limit is asked to tolerate here is a single tick's worth of
     * blocks and nothing larger. A deeper bucket would let a paused download resume at several
     * times the limit for as long as it had been paused, which is the behaviour a user setting a
     * limit is trying to prevent.
     */
    burstSeconds: Double = 1.0,
) {
    public var bytesPerSecond: Long = bytesPerSecond
        private set

    private val burst = burstSeconds

    public val isUnlimited: Boolean get() = bytesPerSecond <= 0

    private var capacity: Long = capacityFor(bytesPerSecond)

    private var balance: Long = capacity

    /**
     * A new rate, on a bucket that is already running.
     *
     * The balance is clamped rather than reset: raising a limit must not hand out a second's worth
     * of the *old* rate on top of what is already there, and lowering one must not leave a bucket
     * holding more than its new depth. Setting the rate a bucket already has does nothing at all,
     * so a settings screen republishing every field on every keystroke costs a comparison.
     */
    public fun retune(newBytesPerSecond: Long) {
        if (newBytesPerSecond == bytesPerSecond) return
        bytesPerSecond = newBytesPerSecond
        capacity = capacityFor(newBytesPerSecond)
        balance = balance.coerceAtMost(capacity)
    }

    private fun capacityFor(rate: Long): Long = if (rate <= 0) 0 else (rate * burst).toLong().coerceAtLeast(1)

    /** What may be spent right now. [Long.MAX_VALUE] when there is no limit. */
    public val available: Long get() = if (isUnlimited) Long.MAX_VALUE else balance

    /** Adds the tokens [elapsed] is worth, up to the bucket's depth. */
    public fun refill(elapsed: Duration) {
        if (isUnlimited) return
        val gained = bytesPerSecond * elapsed.inWholeMilliseconds / MILLIS_PER_SECOND
        balance = minOf(capacity, balance + gained)
    }

    /**
     * Spends [bytes] if the bucket holds them, and says whether it did.
     *
     * All or nothing on purpose. A partial take would mean half a block, and there is no such
     * message in BEP 3 — the caller's question is always "may I send this whole block".
     */
    public fun take(bytes: Long): Boolean {
        if (isUnlimited) return true
        if (bytes > balance) return false
        balance -= bytes
        return true
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
