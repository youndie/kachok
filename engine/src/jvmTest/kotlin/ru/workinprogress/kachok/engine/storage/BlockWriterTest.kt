package ru.workinprogress.kachok.engine.storage

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.workinprogress.kachok.engine.PieceIndex
import ru.workinprogress.kachok.engine.hash.JvmBlock
import ru.workinprogress.kachok.engine.hash.MessageDigestPieceHasher
import ru.workinprogress.kachok.engine.io.BufferPool
import ru.workinprogress.kachok.engine.io.EngineDispatchers
import ru.workinprogress.kachok.engine.metainfo.MetainfoParser
import ru.workinprogress.kachok.engine.peer.Block
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The acceptance criteria of B-11: how many writes a piece costs, that a corrupt piece never
 * reaches the disk, and that every buffer comes back.
 *
 * The fixture is three files of 1000, 1 and 999 bytes at a piece length of 512, so piece 1 spans
 * all three — the case that makes a gathering write worth having — and the torrent's own piece
 * hashes are computed here rather than hard-coded, because what is under test is the writer, not
 * SHA-1.
 */
class BlockWriterTest {
    private val dispatchers = EngineDispatchers()
    private val content = ByteArray(2000) { (it and 0x7F).toByte() }

    @AfterTest
    fun shutDown() {
        dispatchers.close()
    }

    /** Three files, 512-byte pieces, with the piece hashes of [content] baked in. */
    private fun metainfo(corruptPieceHash: Int = -1) = MetainfoParser.parse(torrent(corruptPieceHash))

    private fun torrent(corruptPieceHash: Int): ByteArray {
        val digest = MessageDigest.getInstance("SHA-1")
        val hashes = ByteArray(4 * 20)
        (0 until 4).forEach { index ->
            val from = index * 512
            val to = minOf(from + 512, content.size)
            digest.reset()
            digest.update(content, from, to - from)
            val piece = digest.digest()
            if (index == corruptPieceHash) piece[0] = (piece[0] + 1).toByte()
            piece.copyInto(hashes, index * 20)
        }
        val prefix =
            "d4:infod5:filesld6:lengthi1000e4:pathl5:a.bineed6:lengthi1e4:pathl3:sub5:b.binee" +
                "d6:lengthi999e4:pathl3:sub5:c.bineee4:name6:bundle12:piece lengthi512e6:pieces80:"
        return prefix.encodeToByteArray() + hashes + "ee".encodeToByteArray()
    }

    private class CountingSink : SpanSink {
        val calls = mutableListOf<Triple<Int, Long, Int>>()
        val files = mutableMapOf<Int, ByteArray>()
        var flushes = 0

        override fun writeSpan(
            file: Int,
            position: Long,
            buffers: Array<ByteBuffer>,
        ) {
            val length = buffers.sumOf { it.remaining() }
            calls += Triple(file, position, length)
            val target = files.getOrPut(file) { ByteArray(4096) }
            var at = position.toInt()
            buffers.forEach { buffer ->
                val slice = ByteArray(buffer.remaining())
                buffer.duplicate().get(slice)
                slice.copyInto(target, at)
                at += slice.size
            }
        }

        override fun transferSpan(
            file: Int,
            position: Long,
            length: Int,
            target: java.nio.channels.WritableByteChannel,
        ): Long {
            val bytes = ByteBuffer.wrap(files.getValue(file), position.toInt(), length)
            transfers += Triple(file, position, length)
            return target.write(bytes).toLong()
        }

        /** Reads served, so a test can count `transferTo` calls the way it counts writes. */
        val transfers = mutableListOf<Triple<Int, Long, Int>>()

        override fun readSpan(
            file: Int,
            position: Long,
            buffer: ByteBuffer,
        ): Int {
            val source = files[file] ?: return -1
            val length = minOf(buffer.remaining(), source.size - position.toInt())
            if (length <= 0) return -1
            buffer.put(source, position.toInt(), length)
            return length
        }

        override fun flushAll() {
            flushes++
        }
    }

    private class TestBlock(
        override val piece: PieceIndex,
        override val begin: Int,
        override val bytes: ByteBuffer,
        private val onRelease: () -> Unit,
    ) : JvmBlock {
        override val length: Int get() = bytes.remaining()

        override fun release() = onRelease()
    }

    /** Blocks of one piece, cut at [blockSize], carrying the real bytes of [content]. */
    private fun blocksOf(
        piece: Int,
        pieceLength: Int,
        blockSize: Int,
        pool: BufferPool,
    ): List<Block> {
        val start = piece * 512
        return (0 until pieceLength step blockSize).map { begin ->
            val size = minOf(blockSize, pieceLength - begin)
            val pooled = checkNotNull(pool.tryAcquire())
            pooled.buffer.clear().limit(size)
            pooled.buffer.put(content, start + begin, size)
            pooled.buffer.flip()
            TestBlock(PieceIndex(piece), begin, pooled.buffer) { pooled.release() }
        }
    }

    @Test
    fun aPieceSpanningThreeFilesCostsThreeWrites(): Unit =
        runBlocking {
            val pool = BufferPool(capacity = 8)
            val info = metainfo()
            val sink = CountingSink()
            val writer =
                BlockWriter(info, MessageDigestPieceHasher(dispatchers.io), FileStorage(PieceLayout(info), sink))
            launch(dispatchers.io) { writer.run() }

            // Piece 1 is bytes 512..1023: 488 of a.bin, all 1 of b.bin, 23 of c.bin.
            blocksOf(piece = 1, pieceLength = 512, blockSize = 128, pool = pool).forEach { writer.blocks.send(it) }
            val outcome = withTimeout(TIMEOUT) { writer.outcomes.receive() }

            assertTrue(outcome is PieceOutcome.Verified, "got $outcome")
            assertEquals(3, sink.calls.size, "one gathering write per file span, not one per block")
            assertEquals(listOf(0, 1, 2), sink.calls.map { it.first })
            assertEquals(listOf(512L, 0L, 0L), sink.calls.map { it.second })
            assertEquals(listOf(488, 1, 23), sink.calls.map { it.third })
            assertEquals(0, pool.outstanding, "every buffer of the piece went back to the pool")
            writer.blocks.close()
        }

    @Test
    fun fourBlocksOfOnePieceInsideOneFileCostOneWrite(): Unit =
        runBlocking {
            val pool = BufferPool(capacity = 8)
            val info = metainfo()
            val sink = CountingSink()
            val writer =
                BlockWriter(info, MessageDigestPieceHasher(dispatchers.io), FileStorage(PieceLayout(info), sink))
            launch(dispatchers.io) { writer.run() }

            blocksOf(piece = 0, pieceLength = 512, blockSize = 128, pool = pool).forEach { writer.blocks.send(it) }
            withTimeout(TIMEOUT) { writer.outcomes.receive() }

            assertEquals(1, sink.calls.size, "four blocks in one file are one system call, not four")
            assertContentEquals(
                content.copyOfRange(0, 512),
                sink.files.getValue(0).copyOfRange(0, 512),
                "and the bytes that landed are the bytes that arrived",
            )
            writer.blocks.close()
        }

    @Test
    fun aCorruptPieceNeverReachesTheDisk(): Unit =
        runBlocking {
            val pool = BufferPool(capacity = 8)
            // The torrent claims a hash piece 0 does not have, which is what a lying peer produces.
            val info = metainfo(corruptPieceHash = 0)
            val sink = CountingSink()
            val writer =
                BlockWriter(info, MessageDigestPieceHasher(dispatchers.io), FileStorage(PieceLayout(info), sink))
            launch(dispatchers.io) { writer.run() }

            blocksOf(piece = 0, pieceLength = 512, blockSize = 256, pool = pool).forEach { writer.blocks.send(it) }
            val outcome = withTimeout(TIMEOUT) { writer.outcomes.receive() }

            assertTrue(outcome is PieceOutcome.HashMismatch, "got $outcome")
            assertEquals(0, sink.calls.size, "nothing was written")
            assertEquals(0, pool.outstanding, "and the buffers still came back")
            writer.blocks.close()
        }

    @Test
    fun anIncompletePieceHoldsItsBuffersAndReleasesThemWhenTheWriterStops(): Unit =
        runBlocking {
            val pool = BufferPool(capacity = 8)
            val info = metainfo()
            val sink = CountingSink()
            val writer =
                BlockWriter(info, MessageDigestPieceHasher(dispatchers.io), FileStorage(PieceLayout(info), sink))
            val loop = launch(dispatchers.io) { writer.run() }

            // Two blocks of a piece that needs four: asking for 256 bytes' worth acquires exactly
            // two buffers. Taking two from a four-block list would acquire four and leak two.
            val partial = blocksOf(piece = 0, pieceLength = 256, blockSize = 128, pool = pool)
            partial.forEach { writer.blocks.send(it) }
            withTimeout(TIMEOUT) {
                while (pool.outstanding < 2) Thread.sleep(1)
            }
            assertEquals(2, pool.outstanding, "a piece in flight holds its blocks out of the pool")
            assertEquals(0, sink.calls.size)

            writer.blocks.close()
            loop.join()
            assertEquals(0, pool.outstanding, "a writer that stops mid-piece leaves no buffer on loan")
        }

    @Test
    fun aDuplicateBlockIsReleasedRatherThanCounted(): Unit =
        runBlocking {
            val pool = BufferPool(capacity = 8)
            val info = metainfo()
            val sink = CountingSink()
            val writer =
                BlockWriter(info, MessageDigestPieceHasher(dispatchers.io), FileStorage(PieceLayout(info), sink))
            launch(dispatchers.io) { writer.run() }

            // Endgame asks several peers for the same block on purpose; the second copy must not be
            // counted towards the piece, or a piece would "complete" while a hole in it was unwritten.
            val blocks = blocksOf(piece = 0, pieceLength = 512, blockSize = 256, pool = pool)
            writer.blocks.send(blocks[0])
            val duplicate = blocksOf(piece = 0, pieceLength = 256, blockSize = 256, pool = pool).first()
            writer.blocks.send(duplicate)
            writer.blocks.send(blocks[1])

            val outcome = withTimeout(TIMEOUT) { writer.outcomes.receive() }
            assertTrue(outcome is PieceOutcome.Verified, "got $outcome")
            assertEquals(1, sink.calls.size)
            assertEquals(0, pool.outstanding, "the duplicate's buffer came back too")
            writer.blocks.close()
        }

    @Test
    fun aBlockIsServedByTransferringSpansRatherThanReadingBytes(): Unit =
        runBlocking {
            // B-20: the upload path is the read-side mirror of the gathering write — one
            // `transferTo` per file span, and the bytes never enter this process.
            val info = metainfo()
            val sink = CountingSink()
            sink.files[0] = ByteArray(1024) { (it and 0x7F).toByte() }
            sink.files[1] = ByteArray(1) { 42 }
            sink.files[2] = ByteArray(1024) { (it and 0x3F).toByte() }
            val storage = FileStorage(PieceLayout(info), sink)

            val received = java.io.ByteArrayOutputStream()
            val target =
                java.nio.channels.Channels
                    .newChannel(received)
            // Piece 1 spans all three files: 488 bytes of a.bin, the single byte of b.bin, 23 of c.bin.
            val sent = storage.transferBlock(PieceIndex(1), 0, 512, target)

            assertEquals(512L, sent)
            assertEquals(listOf(0, 1, 2), sink.transfers.map { it.first })
            assertEquals(listOf(512L, 0L, 0L), sink.transfers.map { it.second })
            assertEquals(listOf(488, 1, 23), sink.transfers.map { it.third })
            assertEquals(512, received.size())
        }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
