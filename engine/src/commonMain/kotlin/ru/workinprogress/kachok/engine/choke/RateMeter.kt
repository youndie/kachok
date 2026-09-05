package ru.workinprogress.kachok.engine.choke

/**
 * Bytes per second over a rolling window.
 *
 * One per direction per peer, and the choker's whole input. A window rather than a running total
 * because what the choking algorithm asks is "what is this peer doing for me *now*" — a peer that
 * was fast an hour ago and is idle today should lose its slot, and a total cannot say that.
 *
 * Buckets of one second, zeroed as time moves past them. No allocation, no timestamps kept: at
 * fifty peers and two directions this runs a hundred times a second and must cost nothing.
 */
public class RateMeter(
    private val windowSeconds: Int = DEFAULT_WINDOW,
) {
    private val buckets = LongArray(windowSeconds)
    private var lastSecond = Long.MIN_VALUE

    public fun add(
        bytes: Long,
        nowMillis: Long,
    ) {
        advance(nowMillis)
        buckets[index(nowMillis)] += bytes
    }

    /** The window's total divided by its length. Zero for a peer that has done nothing. */
    public fun bytesPerSecond(nowMillis: Long): Long {
        advance(nowMillis)
        return buckets.sum() / windowSeconds
    }

    private fun index(nowMillis: Long): Int = ((nowMillis / MILLIS) % windowSeconds).toInt()

    /** Zeroes the buckets time has moved past, so a silent peer's rate decays to nothing. */
    private fun advance(nowMillis: Long) {
        val second = nowMillis / MILLIS
        if (lastSecond == Long.MIN_VALUE) {
            lastSecond = second
            return
        }
        if (second <= lastSecond) return
        val elapsed = minOf(second - lastSecond, windowSeconds.toLong()).toInt()
        repeat(elapsed) { step ->
            buckets[(((lastSecond + step + 1) % windowSeconds) + windowSeconds).toInt() % windowSeconds] = 0
        }
        lastSecond = second
    }

    private companion object {
        const val DEFAULT_WINDOW = 20
        const val MILLIS = 1000L
    }
}
