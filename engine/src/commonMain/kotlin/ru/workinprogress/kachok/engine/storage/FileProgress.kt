package ru.workinprogress.kachok.engine.storage

import ru.workinprogress.kachok.engine.metainfo.Metainfo
import ru.workinprogress.kachok.engine.picker.Bitfield

/**
 * How much of each file is verified, worked out from the pieces that are.
 *
 * **Derived and never stored.** The engine knows which *pieces* it has; which *files* those add up
 * to is arithmetic on the layout, and a second counter kept per file would be a second thing to get
 * wrong every time a piece lands.
 *
 * **A file's pieces are contiguous**, so the work is one pass over each file's own piece range
 * rather than a pass over every piece for every file. The ranges overlap only at the boundaries a
 * piece straddles, which is why the whole thing is O(pieces + files).
 *
 * The last piece is short and the first piece of a file usually starts mid-piece; both are handled
 * by clamping to the file's byte range rather than by counting whole pieces, which is the mistake
 * that makes a 700-byte file show as 100% before anything has been fetched.
 */
public fun verifiedBytesPerFile(
    metainfo: Metainfo,
    completed: Bitfield,
): LongArray {
    val pieceLength = metainfo.pieceLength
    return LongArray(metainfo.files.size) { index ->
        val file = metainfo.files[index]
        if (file.length == 0L) {
            return@LongArray 0
        }
        val first = (file.offset / pieceLength).toInt()
        val last = ((file.offset + file.length - 1) / pieceLength).toInt()
        var verified = 0L
        for (piece in first..last) {
            if (piece >= completed.size || !completed[piece]) continue
            val pieceStart = piece.toLong() * pieceLength
            val pieceEnd = minOf(pieceStart + pieceLength, metainfo.totalLength)
            val from = maxOf(pieceStart, file.offset)
            val to = minOf(pieceEnd, file.offset + file.length)
            if (to > from) verified += to - from
        }
        verified
    }
}
