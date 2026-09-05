package ru.workinprogress.kachok.engine.io

import kotlinx.coroutines.sync.Semaphore
import ru.workinprogress.kachok.engine.wire.PeerWire
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * A buffer borrowed from a [BufferPool], and the only way to hand it back.
 *
 * A handle rather than a bare `ByteBuffer` for one reason: releasing the same buffer twice would
 * hand it to two peers at once, and the second peer would overwrite the first peer's block on its
 * way to the disk. That is a corruption bug with no stack trace and no reproduction; the flag here
 * turns it into an exception at the point of the mistake.
 */
public class PooledBuffer internal constructor(
    public val buffer: ByteBuffer,
    private val pool: BufferPool,
) {
    internal var inUse: Boolean = false

    /** Returns the buffer to its pool. Releasing twice is an error, not a no-op. */
    public fun release() {
        pool.release(this)
    }
}

/**
 * A capped pool of direct [ByteBuffer]s, one block each.
 *
 * This is the structure the whole memory design rests on (research D3, in
 * `docs/research/research-architecture.md`). A block is read from a socket into one of these and
 * the *same* buffer is later handed to the gathering write, so the
 * hot path has no heap copy and allocates nothing per block. Direct is not an optimisation here:
 * the JDK copies every heap buffer into a temporary direct one on its way to the kernel anyway —
 * verified in `sun/nio/ch/IOUtil.java`, research §1.1 — so a pooled direct buffer is simply the
 * version of that with the copy and the temporary removed.
 *
 * **The cap is the back-pressure.** [acquire] suspends when every buffer is out, so a peer that
 * cannot get one stops reading, and the operating system's receive window does the rest. There is
 * no other flow control in the download path and there does not need to be.
 *
 * **Buffers are allocated lazily and never freed.** A session with three peers does not commit the
 * whole cap of off-heap memory, and a session that has once reached its cap keeps the buffers
 * rather than churning them; direct buffers are expensive to allocate and free.
 */
public class BufferPool(
    public val capacity: Int,
    public val bufferSize: Int = PeerWire.BLOCK_SIZE,
) {
    init {
        require(capacity > 0) { "a pool of $capacity buffers can hand out nothing" }
        require(bufferSize > 0) { "buffer size $bufferSize" }
    }

    private val permits = Semaphore(capacity)
    private val free = ConcurrentLinkedQueue<PooledBuffer>()
    private val allocatedCount = AtomicInteger()
    private val outstandingCount = AtomicInteger()

    /** How many buffers are out on loan right now. Part of the session state a UI renders. */
    public val outstanding: Int get() = outstandingCount.get()

    /** How many buffers have ever been allocated; never more than [capacity], never decreases. */
    public val allocated: Int get() = allocatedCount.get()

    /** How many more could be handed out without waiting. The picker reads this before requesting. */
    public val available: Int get() = capacity - outstandingCount.get()

    /** Suspends while every buffer is out. The returned buffer is cleared and ready to be filled. */
    public suspend fun acquire(): PooledBuffer {
        permits.acquire()
        return checkOut()
    }

    /** Takes a buffer only if one is free. Never suspends, never allocates beyond the cap. */
    public fun tryAcquire(): PooledBuffer? {
        if (!permits.tryAcquire()) return null
        return checkOut()
    }

    private fun checkOut(): PooledBuffer {
        val pooled =
            free.poll() ?: PooledBuffer(ByteBuffer.allocateDirect(bufferSize), this)
                .also { allocatedCount.incrementAndGet() }
        check(!pooled.inUse) { "the pool handed out a buffer that was already on loan" }
        pooled.inUse = true
        pooled.buffer.clear()
        outstandingCount.incrementAndGet()
        return pooled
    }

    internal fun release(pooled: PooledBuffer) {
        require(pooled.buffer.capacity() == bufferSize) {
            "this buffer is ${pooled.buffer.capacity()} bytes and does not belong to a pool of $bufferSize"
        }
        check(pooled.inUse) { "this buffer has already been released" }
        pooled.inUse = false
        pooled.buffer.clear()
        outstandingCount.decrementAndGet()
        free.add(pooled)
        permits.release()
    }
}
