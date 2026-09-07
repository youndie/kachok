package io.github.youndie.kachok.engine.storage

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.metainfo.Metainfo
import io.github.youndie.kachok.engine.metainfo.TorrentFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which pieces a skipped file lets this client off, and the one it does not.
 *
 * The rule that matters is the straddle: the swarm serves pieces, so a piece holding one wanted
 * byte has to be fetched whole. Getting it wrong the other way — skipping it — is a download that
 * reports itself finished with a hole in a file somebody asked for.
 */
class UnwantedPiecesTest {
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

    @Test
    fun wantingEverythingSkipsNothing() {
        val metainfo = torrent(40, 24)
        assertEquals(0, unwantedPieces(metainfo, emptySet()).cardinality)
    }

    /**
     * The straddling piece is fetched, and it is the only one of the skipped file's that is.
     *
     * Two files of 40 and 24 bytes in 16-byte pieces: the second starts at byte 40, which is inside
     * piece 2. Skipping it leaves pieces 3 wholly unwanted and piece 2 fetched anyway.
     */
    @Test
    fun aPieceStraddlingAWantedFileIsStillFetched() {
        val metainfo = torrent(40, 24)
        val skipped = unwantedPieces(metainfo, setOf(1))
        assertFalse(skipped[0], "the first file's own pieces are wanted")
        assertFalse(skipped[1])
        assertFalse(skipped[2], "piece 2 holds eight bytes of a wanted file and must be fetched")
        assertTrue(skipped[3], "piece 3 is entirely inside the skipped file")
    }

    @Test
    fun skippingEveryFileSkipsEveryPiece() {
        val metainfo = torrent(40, 24)
        assertEquals(metainfo.pieceCount, unwantedPieces(metainfo, setOf(0, 1)).cardinality)
    }

    /** Three files in one piece: skipping the middle one lets nothing off. */
    @Test
    fun skippingAFileSmallerThanAPieceLetsNothingOff() {
        val metainfo = torrent(4, 4, 8)
        assertEquals(0, unwantedPieces(metainfo, setOf(1)).cardinality)
    }

    /** A zero-length file covers no byte, so wanting or skipping it changes nothing. */
    @Test
    fun aZeroLengthFileChangesNothingEitherWay() {
        val metainfo = torrent(16, 0, 16)
        assertEquals(0, unwantedPieces(metainfo, setOf(1)).cardinality)
        assertEquals(unwantedPieces(metainfo, setOf(0)).cardinality, unwantedPieces(metainfo, setOf(0, 1)).cardinality)
    }

    @Test
    fun theWantedBytesAreTheFilesThatWereKept() {
        val metainfo = torrent(40, 24)
        assertEquals(64, wantedBytes(metainfo, emptySet()))
        assertEquals(40, wantedBytes(metainfo, setOf(1)))
        assertEquals(0, wantedBytes(metainfo, setOf(0, 1)))
    }

    private companion object {
        const val PIECE = 16
        const val HASH_BYTES = 20
    }
}
