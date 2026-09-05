package ru.workinprogress.kachok.engine.storage

import ru.workinprogress.kachok.engine.PieceIndex
import ru.workinprogress.kachok.engine.hash.JvmBlock
import ru.workinprogress.kachok.engine.io.BufferPool
import ru.workinprogress.kachok.engine.io.PooledBlock
import ru.workinprogress.kachok.engine.peer.Block
import ru.workinprogress.kachok.engine.wire.PeerWire
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel

/**
 * One gathering write, aimed at one place in one file.
 *
 * The seam exists because the thing worth asserting about the writer is *how many system calls a
 * piece costs*, and a `FileChannel` cannot be asked that. [FileSet] is the real implementation; a
 * test counts calls.
 */
public interface SpanSink {
    /**
     * Writes [buffers] end to end at [position] in file [file], in as few calls as the platform
     * allows — one, unless the operating system writes short.
     */
    public fun writeSpan(
        file: Int,
        position: Long,
        buffers: Array<ByteBuffer>,
    )

    /**
     * Sends [length] bytes from [position] in file [file] straight to [target].
     *
     * The upload path, and the reason it is a method here rather than a `read` returning bytes:
     * `FileChannel.transferTo` hands the file to the socket inside the kernel, so an uploaded byte
     * is never copied into this process at all — not into the heap, not into a direct buffer
     * (research D5). A `read` returning a `ByteArray` could not express that.
     *
     * Returns what was transferred; the operating system may transfer short.
     */
    public fun transferSpan(
        file: Int,
        position: Long,
        length: Int,
        target: WritableByteChannel,
    ): Long

    /**
     * Fills [buffer] to its limit from [position] in file [file].
     *
     * Returns the bytes read, or -1 when the file ends before the buffer is full — which is how a
     * verification pass learns that a piece is not on the disk at all.
     */
    public fun readSpan(
        file: Int,
        position: Long,
        buffer: ByteBuffer,
    ): Int

    public fun flushAll()
}

/**
 * A piece on the disk: one gathering write per file span it covers.
 *
 * A 4 MiB piece is 256 blocks. Written block by block that is 256 system calls; written as spans
 * it is one call per file the piece touches, which for the common case of a piece inside one file
 * is exactly one. The blocks are handed to the kernel where they already are — pooled direct
 * buffers — so no byte of a torrent is copied inside this process on its way to the disk.
 */
public class FileStorage(
    private val layout: PieceLayout,
    private val sink: SpanSink,
    /**
     * Where a verification pass gets its buffers, or null for a storage that only writes.
     *
     * The same pool the download uses, so re-hashing a torrent at start-up costs the memory of a
     * few blocks rather than the memory of a piece list.
     */
    private val pool: BufferPool? = null,
) : Storage {
    override suspend fun write(
        piece: PieceIndex,
        blocks: List<Block>,
    ) {
        val slices =
            blocks.map { block ->
                val jvmBlock =
                    block as? JvmBlock
                        ?: error("this storage writes JVM blocks; got ${block::class.simpleName}")
                // Duplicates so the caller's buffers keep their positions: the same blocks may be
                // hashed again, and are certainly released afterwards.
                jvmBlock.bytes.duplicate()
            }
        val sizes = slices.map { it.remaining() }
        require(sizes.sum() == layout.metainfo.pieceLengthAt(piece)) {
            "piece ${piece.value} is ${layout.metainfo.pieceLengthAt(piece)} bytes but its blocks are ${sizes.sum()}"
        }

        var block = 0
        var consumed = 0
        layout.spansOfPiece(piece).forEach { span ->
            val parts = ArrayList<ByteBuffer>(2)
            var remaining = span.length
            while (remaining > 0) {
                val available = minOf(sizes[block] - consumed, remaining)
                val part = slices[block].duplicate()
                part.position(part.position() + consumed).limit(part.position() + available)
                parts += part
                consumed += available
                remaining -= available
                if (consumed == sizes[block]) {
                    block++
                    consumed = 0
                }
            }
            sink.writeSpan(span.file, span.position, parts.toTypedArray())
        }
    }

    override suspend fun readPiece(piece: PieceIndex): List<Block>? {
        val buffers = pool ?: return null
        val pieceLength = layout.metainfo.pieceLengthAt(piece)
        val blocks = mutableListOf<PooledBlock>()
        try {
            var begin = 0
            while (begin < pieceLength) {
                val size = minOf(PeerWire.BLOCK_SIZE, pieceLength - begin)
                val pooled = buffers.acquire()
                blocks += PooledBlock(piece, begin, pooled)
                pooled.buffer.clear().limit(size)
                var filled = 0
                layout.spans(piece, begin, size).forEach { span ->
                    val slice = pooled.buffer.duplicate()
                    slice.position(filled).limit(filled + span.length)
                    if (sink.readSpan(span.file, span.position, slice) < span.length) {
                        // Short read: the file is not as long as the torrent says, so this piece
                        // is not on the disk. Not an error — it is the ordinary state of a piece
                        // nobody has downloaded yet.
                        return releaseAndFail(blocks)
                    }
                    filled += span.length
                }
                pooled.buffer.position(0).limit(size)
                begin += size
            }
        } catch (failure: Throwable) {
            releaseAndFail(blocks)
            throw failure
        }
        return blocks
    }

    private fun releaseAndFail(blocks: List<PooledBlock>): List<Block>? {
        blocks.forEach { it.release() }
        return null
    }

    override suspend fun flush() {
        sink.flushAll()
    }

    /**
     * Serves one block to a socket, without the bytes entering this process.
     *
     * A block can straddle a file boundary exactly as a written one can, so this walks the same
     * spans the writer does — one `transferTo` per span, the read-side mirror of the gathering
     * write.
     */
    public fun transferBlock(
        piece: PieceIndex,
        begin: Int,
        length: Int,
        target: WritableByteChannel,
    ): Long {
        var sent = 0L
        layout.spans(piece, begin, length).forEach { span ->
            var remaining = span.length
            var at = span.position
            while (remaining > 0) {
                val moved = sink.transferSpan(span.file, at, remaining, target)
                check(moved > 0) { "the file for span ${span.file} transferred nothing" }
                remaining -= moved.toInt()
                at += moved
                sent += moved
            }
        }
        return sent
    }
}
