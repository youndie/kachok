package io.github.youndie.kachok.engine.hash

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import io.github.youndie.kachok.engine.PieceIndex
import io.github.youndie.kachok.engine.io.EngineDispatchers
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The acceptance criteria of B-13.
 *
 * The expected digests come from Python's `hashlib`, over the same byte pattern: a hasher checked
 * against its own `MessageDigest` would be checking that SHA-1 equals SHA-1.
 */
class MessageDigestPieceHasherTest {
    private val dispatchers = EngineDispatchers()

    /** 32 KiB of `i and 0x7F`, as two blocks — one piece of two blocks, the ordinary case. */
    private val pattern = ByteArray(32768) { (it and 0x7F).toByte() }
    private val wholePieceDigest = "315c2a7511c9ea8f52f692221435b6ac19e5a33f"
    private val firstBlockDigest = "36728d9ed05950550332bdfd7c219f483789fe0d"

    @AfterTest
    fun shutDown() {
        dispatchers.close()
    }

    private fun blocks(vararg ranges: IntRange): List<TestBlock> =
        ranges.mapIndexed { index, range ->
            TestBlock(
                piece = PieceIndex(0),
                begin = range.first,
                bytes = ByteBuffer.wrap(pattern, range.first, range.last - range.first + 1).slice(),
            ).also { check(index >= 0) }
        }

    @Test
    fun aPieceHashesToWhatAnIndependentToolSays(): Unit =
        runBlocking {
            val hasher = MessageDigestPieceHasher(dispatchers.io)
            assertEquals(wholePieceDigest, hasher.hash(blocks(0..16383, 16384..32767)).toHex())
            assertEquals(firstBlockDigest, hasher.hash(blocks(0..16383)).toHex())
        }

    @Test
    fun hashingDoesNotConsumeTheBlocksTheWriterStillNeeds(): Unit =
        runBlocking {
            val hasher = MessageDigestPieceHasher(dispatchers.io)
            val pieceBlocks = blocks(0..16383, 16384..32767)
            hasher.hash(pieceBlocks)
            assertEquals(
                listOf(16384, 16384),
                pieceBlocks.map { it.bytes.remaining() },
                "the buffers must still hold their bytes; the writer has not run yet",
            )
            assertEquals(wholePieceDigest, hasher.hash(pieceBlocks).toHex(), "and hashing twice agrees")
        }

    @Test
    fun concurrencyIsBoundedByTheParallelismItWasGiven(): Unit =
        runBlocking {
            val bound = 3
            val inFlight = AtomicInteger()
            val peak = AtomicInteger()
            val hasher =
                MessageDigestPieceHasher(dispatchers.io, parallelism = bound) {
                    CountingDigest(inFlight, peak)
                }
            (1..64).map { async { hasher.hash(blocks(0..16383)) } }.awaitAll()
            assertTrue(
                peak.get() <= bound,
                "$bound was the limit but ${peak.get()} pieces were hashed at once",
            )
            assertTrue(peak.get() > 0, "nothing was hashed, so the bound proves nothing")
        }

    @Test
    fun digestsAreReusedRatherThanCreatedPerPiece(): Unit =
        runBlocking {
            // The point of the pool: a ThreadLocal would allocate one digest per virtual thread, and
            // a virtual thread is created per task, so 64 pieces would mean 64 digests.
            val created = AtomicInteger()
            val used = ConcurrentHashMap.newKeySet<Int>()
            val hasher =
                MessageDigestPieceHasher(dispatchers.io, parallelism = 4) {
                    created.incrementAndGet()
                    IdentifyingDigest(used)
                }
            (1..64).map { async { hasher.hash(blocks(0..16383)) } }.awaitAll()
            assertEquals(4, created.get(), "one digest per unit of parallelism, made once")
            assertTrue(used.size <= 4, "64 pieces went through ${used.size} digests")
        }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private class TestBlock(
        override val piece: PieceIndex,
        override val begin: Int,
        override val bytes: ByteBuffer,
    ) : JvmBlock {
        override val length: Int get() = bytes.remaining()

        override fun release() = Unit
    }

    /** Counts how many digests are updating at the same moment. */
    private class CountingDigest(
        private val inFlight: AtomicInteger,
        private val peak: AtomicInteger,
    ) : MessageDigest(MessageDigestPieceHasher.ALGORITHM) {
        private val delegate = getInstance(MessageDigestPieceHasher.ALGORITHM)

        override fun engineUpdate(input: Byte) = delegate.update(input)

        override fun engineUpdate(
            input: ByteArray,
            offset: Int,
            length: Int,
        ) {
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            Thread.sleep(1)
            delegate.update(input, offset, length)
            inFlight.decrementAndGet()
        }

        override fun engineDigest(): ByteArray = delegate.digest()

        override fun engineReset() = delegate.reset()
    }

    /** Records its own identity, so a test can count how many distinct digests did the work. */
    private class IdentifyingDigest(
        private val seen: MutableSet<Int>,
    ) : MessageDigest(MessageDigestPieceHasher.ALGORITHM) {
        private val delegate = getInstance(MessageDigestPieceHasher.ALGORITHM)

        override fun engineUpdate(input: Byte) = delegate.update(input)

        override fun engineUpdate(
            input: ByteArray,
            offset: Int,
            length: Int,
        ) {
            seen += System.identityHashCode(this)
            delegate.update(input, offset, length)
        }

        override fun engineDigest(): ByteArray = delegate.digest()

        override fun engineReset() = delegate.reset()
    }
}
