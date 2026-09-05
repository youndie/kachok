package ru.workinprogress.kachok.engine.storage

import ru.workinprogress.kachok.engine.PieceIndex
import ru.workinprogress.kachok.engine.metainfo.Metainfo

/** A contiguous run of bytes inside one file of the torrent. */
public class FileSpan(
    /** Index into [Metainfo.files]. */
    public val file: Int,
    /** Offset within that file, not within the torrent. */
    public val position: Long,
    public val length: Int,
) {
    override fun toString(): String = "file $file @$position +$length"
}

/**
 * Where a piece — or one block of one — lives on disk.
 *
 * A torrent is one byte stream that happens to be cut into files, so a piece can start in the
 * middle of one file and end in the middle of the next. Everything above this class thinks in
 * pieces and blocks; everything below it thinks in files and positions, and this is the only place
 * that knows both.
 *
 * The mapping is computed from cumulative offsets rather than by walking the file list per block:
 * a torrent with ten thousand files would otherwise pay for that walk on every one of its blocks.
 */
public class PieceLayout(
    public val metainfo: Metainfo,
) {
    /** Where each file starts in the torrent's byte stream. Ascending, so it can be searched. */
    private val starts: LongArray = LongArray(metainfo.files.size) { metainfo.files[it].offset }

    /** The spans one whole piece occupies, in order. */
    public fun spansOfPiece(piece: PieceIndex): List<FileSpan> = spans(piece, 0, metainfo.pieceLengthAt(piece))

    /** The spans one block occupies: `begin` bytes into `piece`, `length` bytes long. */
    public fun spans(
        piece: PieceIndex,
        begin: Int,
        length: Int,
    ): List<FileSpan> {
        val pieceLength = metainfo.pieceLengthAt(piece)
        require(begin >= 0 && length > 0 && begin + length <= pieceLength) {
            "block ($begin, $length) does not fit in piece ${piece.value} of $pieceLength bytes"
        }
        val start = piece.value.toLong() * metainfo.pieceLength + begin
        return spansAt(start, length)
    }

    /** The spans covering `length` bytes from `start` in the torrent's byte stream. */
    public fun spansAt(
        start: Long,
        length: Int,
    ): List<FileSpan> {
        require(start >= 0 && start + length <= metainfo.totalLength) {
            "range $start..${start + length} is outside the torrent's ${metainfo.totalLength} bytes"
        }
        val spans = mutableListOf<FileSpan>()
        var offset = start
        var remaining = length
        var index = fileIndexAt(start)
        while (remaining > 0) {
            check(index < metainfo.files.size) { "ran past the last file with $remaining bytes left" }
            val file = metainfo.files[index]
            val within = offset - file.offset
            val available = minOf(file.length - within, remaining.toLong()).toInt()
            // A zero-length file is legal in a torrent and covers nothing; step over it.
            if (available > 0) {
                spans += FileSpan(index, within, available)
                offset += available
                remaining -= available
            }
            index++
        }
        return spans
    }

    /** The last file whose start is at or before [offset]. */
    private fun fileIndexAt(offset: Long): Int {
        var low = 0
        var high = starts.size - 1
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (starts[middle] <= offset) low = middle else high = middle - 1
        }
        return low
    }
}
