package io.github.youndie.kachok.engine.storage

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.metainfo.Metainfo
import io.github.youndie.kachok.engine.metainfo.TorrentFile
import io.github.youndie.kachok.engine.picker.Bitfield
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Per-file progress, and the two places the obvious implementation is wrong.
 *
 * Counting whole pieces makes a 700-byte file inside a 256 KiB piece show 100% the moment that
 * piece lands — which is true of the piece and false of the file beside it in the same one.
 */
class FileProgressTest {
    private fun torrent(vararg lengths: Long): Metainfo {
        var offset = 0L
        val files =
            lengths.mapIndexed { at, length ->
                TorrentFile(listOf("f$at"), length, offset).also { offset += length }
            }
        val total = lengths.sum()
        val pieces = ((total + PIECE - 1) / PIECE).toInt()
        return Metainfo(
            infoHash = InfoHash(ByteArray(HASH_BYTES)),
            name = "t",
            pieceLength = PIECE,
            totalLength = total,
            files = files,
            pieceHashes = ByteArray(pieces * Metainfo.HASH_SIZE),
            trackers = emptyList(),
            isPrivate = false,
            isSingleFile = files.size == 1,
            infoBytes = ByteArray(0),
        )
    }

    private fun have(
        pieces: Int,
        vararg indices: Int,
    ) = Bitfield(pieces).apply { indices.forEach { set(it) } }

    private fun all(metainfo: Metainfo) =
        have(metainfo.pieceCount, *(0 until metainfo.pieceCount).toList().toIntArray())

    @Test
    fun nothingVerifiedIsZeroForEveryFile() {
        val metainfo = torrent(40, 24)
        assertEquals(
            listOf(0L, 0L),
            verifiedBytesPerFile(metainfo, have(metainfo.pieceCount)).toList(),
        )
    }

    @Test
    fun everyPieceVerifiedIsEveryFileWhole() {
        val metainfo = torrent(40, 24)
        assertEquals(listOf(40L, 24L), verifiedBytesPerFile(metainfo, all(metainfo)).toList())
    }

    /**
     * The piece that straddles two files counts towards both, by the bytes it actually holds.
     *
     * Piece 2 covers bytes 32..47: eight of them belong to the first file and eight to the second.
     */
    @Test
    fun aStraddlingPieceIsSplitByBytesAndNotByPieces() {
        val metainfo = torrent(40, 24)
        assertEquals(listOf(8L, 8L), verifiedBytesPerFile(metainfo, have(metainfo.pieceCount, 2)).toList())
    }

    /** A tiny file wholly inside one piece is complete only when that piece is, and no sooner. */
    @Test
    fun aFileSmallerThanAPieceIsNotWholeUntilItsPieceIs() {
        val metainfo = torrent(16, 4, 12)
        assertEquals(
            listOf(16L, 0L, 0L),
            verifiedBytesPerFile(metainfo, have(metainfo.pieceCount, 0)).toList(),
        )
        assertEquals(
            listOf(0L, 4L, 12L),
            verifiedBytesPerFile(metainfo, have(metainfo.pieceCount, 1)).toList(),
        )
    }

    /** The last piece is short, and a file that ends in it must not be credited with the padding. */
    @Test
    fun theShortLastPieceCreditsOnlyTheBytesItHolds() {
        val metainfo = torrent(20)
        val last = metainfo.pieceCount - 1
        assertEquals(4L, verifiedBytesPerFile(metainfo, have(metainfo.pieceCount, last)).single())
    }

    /** A zero-length file is legal in a torrent and is never anything but zero. */
    @Test
    fun aZeroLengthFileStaysZero() {
        val metainfo = torrent(16, 0, 16)
        assertEquals(listOf(16L, 0L, 16L), verifiedBytesPerFile(metainfo, all(metainfo)).toList())
    }

    /** However the pieces fall, the parts add up to the whole. */
    @Test
    fun theFilesAddUpToTheTorrent() {
        val metainfo = torrent(7, 33, 1, 100, 15)
        assertEquals(metainfo.totalLength, verifiedBytesPerFile(metainfo, all(metainfo)).sum())
    }

    private companion object {
        const val PIECE = 16
        const val HASH_BYTES = 20
    }
}
