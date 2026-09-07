package io.github.youndie.kachok.engine.io

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import io.github.youndie.kachok.engine.wire.PeerWire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The acceptance criteria of B-08. */
class BufferPoolTest {
    @Test
    fun buffersAreDirectAndExactlyOneBlock() {
        val pool = BufferPool(capacity = 2)
        val pooled = assertNotNull(pool.tryAcquire())
        assertTrue(pooled.buffer.isDirect, "a heap buffer would be copied into a direct one anyway")
        assertEquals(PeerWire.BLOCK_SIZE, pooled.buffer.capacity())
        assertEquals(16384, pooled.buffer.capacity())
    }

    @Test
    fun aReleasedBufferComesBackClearedAndIsReused() {
        val pool = BufferPool(capacity = 1)
        val first = assertNotNull(pool.tryAcquire())
        first.buffer.put(0, 7)
        first.buffer.position(100)
        first.release()

        val second = assertNotNull(pool.tryAcquire())
        assertSame(first, second, "the pool reuses its buffers rather than allocating new ones")
        assertEquals(0, second.buffer.position())
        assertEquals(PeerWire.BLOCK_SIZE, second.buffer.limit())
        assertEquals(1, pool.allocated)
    }

    @Test
    fun theCapIsTheBackPressure() =
        runTest {
            val pool = BufferPool(capacity = 1)
            val first = pool.acquire()
            assertEquals(1, pool.outstanding)
            assertEquals(0, pool.available)
            assertNull(pool.tryAcquire(), "the cap is reached")

            var second: PooledBuffer? = null
            val waiter = launch { second = pool.acquire() }
            testScheduler.runCurrent()
            assertNull(second, "acquire suspends while every buffer is out")

            first.release()
            waiter.join()
            assertNotNull(second)
            assertEquals(1, pool.outstanding)
        }

    @Test
    fun allocationIsLazyAndBoundedByTheCap() {
        val pool = BufferPool(capacity = 4)
        assertEquals(0, pool.allocated, "a pool with no borrowers commits no off-heap memory")
        val taken = List(4) { assertNotNull(pool.tryAcquire()) }
        assertEquals(4, pool.allocated)
        assertNull(pool.tryAcquire())
        taken.forEach { it.release() }
        assertEquals(0, pool.outstanding)
        assertEquals(4, pool.allocated, "released buffers are kept, not freed")
    }

    @Test
    fun releasingTwiceIsAnError() {
        // Two peers holding the same buffer would overwrite each other's block on the way to the
        // disk: corruption with no stack trace. This turns it into one at the point of the mistake.
        val pool = BufferPool(capacity = 2)
        val pooled = assertNotNull(pool.tryAcquire())
        pooled.release()
        assertFailsWith<IllegalStateException> { pooled.release() }
    }

    @Test
    fun aPoolOfZeroBuffersIsRefused() {
        assertFailsWith<IllegalArgumentException> { BufferPool(capacity = 0) }
    }

    @Test
    fun outstandingCountsWhatIsOnLoan() {
        val pool = BufferPool(capacity = 3)
        val a = assertNotNull(pool.tryAcquire())
        val b = assertNotNull(pool.tryAcquire())
        assertEquals(2, pool.outstanding)
        assertEquals(1, pool.available)
        a.release()
        assertEquals(1, pool.outstanding)
        b.release()
        assertEquals(0, pool.outstanding)
        assertEquals(3, pool.available)
    }
}
