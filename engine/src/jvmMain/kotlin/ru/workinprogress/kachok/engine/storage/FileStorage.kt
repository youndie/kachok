package ru.workinprogress.kachok.engine.storage

import ru.workinprogress.kachok.engine.PieceIndex
import ru.workinprogress.kachok.engine.hash.JvmBlock
import ru.workinprogress.kachok.engine.peer.Block
import java.nio.ByteBuffer

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

    override suspend fun flush() {
        sink.flushAll()
    }
}
