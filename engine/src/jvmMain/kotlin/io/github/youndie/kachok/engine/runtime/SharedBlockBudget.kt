package io.github.youndie.kachok.engine.runtime

import io.github.youndie.kachok.engine.picker.PieceBudget
import io.github.youndie.kachok.engine.wire.PeerWire
import java.lang.management.ManagementFactory
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The blocks every torrent in a set may hold for started pieces, together (B-174).
 *
 * **Why it exists.** Each torrent's window was derived from its own peers — 500 peers at a pipeline
 * of 64 is 32 000 blocks, half a gigabyte — while the process had 192 MiB of direct memory for
 * all of them. Three torrents downloading at 100 Mbit/s held 8 959 blocks of open pieces when every
 * one of them stopped, with no room left for the blocks that would have finished any of them.
 *
 * **What it promises.** The blocks reserved never exceed [capacity], and a piece is reserved whole
 * before it begins, so every piece that is open has room for the rest of its blocks. A torrent
 * that holds nothing may always begin one piece when there is room for it, so none is shut out;
 * beyond that it may hold its share, [capacity] divided by the torrents holding any or asking for
 * one, so the first torrent to ask does not take the rest. A torrent over its share after another
 * joins begins nothing new until its open pieces close, which is how the shares settle without
 * anything being taken back.
 *
 * **Asking counts, for a while.** A torrent refused while it holds nothing is still a torrent that
 * wants a piece, and leaving it out of the count would hand every freed piece straight back to the
 * one holding the rest. It counts for [ASKING] after it last asked: a session asks every tick, so a
 * torrent that stopped asking — paused, finished — stops shrinking everybody else's share.
 */
public class SharedBlockBudget(
    /** Blocks that may be reserved across the set. */
    public val capacity: Int,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    init {
        require(capacity > 0) { "a budget of $capacity blocks can begin nothing" }
    }

    private var reserved = 0
    private val held = HashMap<Holder, Int>()
    private val asking = HashMap<Holder, ComparableTimeMark>()

    /** Blocks reserved now, by every torrent. */
    public val reservedBlocks: Int
        @Synchronized get() = reserved

    /** One torrent's account. Close it when the torrent is gone; what it still held is freed. */
    public fun holder(): Holder = Holder()

    public inner class Holder internal constructor() :
        PieceBudget,
        AutoCloseable {
            /** Blocks this torrent holds now. */
            public val blocks: Int get() = synchronized(this@SharedBlockBudget) { held[this] ?: 0 }

            override fun tryReserve(blocks: Int): Boolean =
                synchronized(this@SharedBlockBudget) {
                    val mine = held[this] ?: 0
                    // A piece larger than the whole budget still begins, alone: refusing it would be a
                    // torrent that can never finish, which is worse than one piece over the line.
                    val room = reserved + blocks <= capacity || reserved == 0
                    if (!room) {
                        if (mine == 0) asking[this] = timeSource.markNow()
                        return false
                    }
                    if (mine > 0) {
                        val now = timeSource.markNow()
                        asking.values.removeAll { now - it > ASKING }
                        val sharing = held.count { it.value > 0 } + asking.keys.count { (held[it] ?: 0) == 0 }
                        if (mine + blocks > capacity / sharing) return false
                    }
                    asking.remove(this)
                    held[this] = mine + blocks
                    reserved += blocks
                    true
                }

            override fun release(blocks: Int): Unit =
                synchronized(this@SharedBlockBudget) {
                    // Nothing to give back once closed: a session still closing its pieces after its
                    // torrent was removed returns blocks the close already returned.
                    val mine = held[this] ?: return
                    val freed = minOf(blocks, mine)
                    held[this] = mine - freed
                    reserved -= freed
                }

            override fun close(): Unit =
                synchronized(this@SharedBlockBudget) {
                    asking.remove(this)
                    reserved -= held.remove(this) ?: 0
                }
        }

    public companion object {
        /** How long a refused torrent counts as asking: a few of a session's one-second ticks. */
        private val ASKING = 5.seconds

        /**
         * How the process's direct memory is split, in blocks: the buffers there are, and the
         * blocks of them started pieces may reserve.
         *
         * A quarter is left out of the pool altogether for the JDK's own direct buffers — every
         * heap buffer a channel writes goes through a temporary direct one — and of the pool a
         * block per connection is left out of the reservations, because a connection reads into a
         * buffer before anyone knows which piece the block is for, and an endgame duplicate holds
         * one until it is dropped.
         */
        public fun split(
            directBytes: Long,
            maxConnections: Int,
        ): Pair<Int, Int> {
            val buffers =
                (directBytes / 4 * 3 / PeerWire.BLOCK_SIZE)
                    .coerceIn(MIN_BUFFERS.toLong(), Int.MAX_VALUE.toLong())
                    .toInt()
            val headroom = maxConnections.coerceAtLeast(0).coerceAtMost(buffers / 2)
            return buffers to buffers - headroom
        }

        /**
         * The VM's ceiling on direct memory: `-XX:MaxDirectMemorySize` when it was given, which
         * the CLI and the window both pin, and otherwise the heap's ceiling, which is the VM's own
         * default for it.
         */
        public fun maxDirectMemory(): Long {
            val flag = "-XX:MaxDirectMemorySize="
            val given =
                try {
                    ManagementFactory
                        .getRuntimeMXBean()
                        .inputArguments
                        .lastOrNull { it.startsWith(flag) }
                        ?.removePrefix(flag)
                        ?.let(::bytesOf)
                } catch (missing: LinkageError) {
                    // A run-time image linked without `java.management`. The first window built
                    // with this class was one, and it opened no torrent: a smaller budget than the
                    // VM has is a slower client, which is better than none.
                    null
                }
            return given ?: Runtime.getRuntime().maxMemory()
        }

        private fun bytesOf(text: String): Long? {
            val unit =
                when (text.lastOrNull()?.lowercaseChar()) {
                    'k' -> KIB
                    'm' -> KIB * KIB
                    'g' -> KIB * KIB * KIB
                    't' -> KIB * KIB * KIB * KIB
                    else -> 1L
                }
            val digits = if (unit == 1L) text else text.dropLast(1)
            return digits.toLongOrNull()?.times(unit)?.takeIf { it > 0 }
        }

        private const val KIB = 1024L

        /** Enough for one 4 MiB piece and a few connections, whatever the VM was given. */
        private const val MIN_BUFFERS = 512
    }
}
