package io.github.youndie.kachok.engine.runtime

import io.github.youndie.kachok.engine.bencode.BDictionary
import io.github.youndie.kachok.engine.bencode.BInteger
import io.github.youndie.kachok.engine.bencode.BString
import io.github.youndie.kachok.engine.bencode.Bencode
import io.github.youndie.kachok.engine.metainfo.Metainfo
import io.github.youndie.kachok.engine.metainfo.MetainfoParser
import io.github.youndie.kachok.engine.wire.PeerWire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B-105: the download window is what the peers can ask for, not a constant.
 *
 * It was a constant 8, and on a real swarm that constant *was* the client's speed: `window 8
 * pieces @ 959ms` is 2.13 MB/s and the run measured 2.03. It also decided how many peers stayed —
 * with the window full the picker hands out nothing, and 40 of that run's 44 disconnections were
 * peers this client had never asked for anything.
 */
class DownloadWindowTest {
    private fun torrent(
        pieceLength: Long,
        pieces: Int,
    ): Metainfo {
        val info =
            BDictionary(
                mapOf(
                    BString("length") to BInteger(pieces * pieceLength),
                    BString("name") to BString("fixture"),
                    BString("piece length") to BInteger(pieceLength),
                    BString("pieces") to BString(ByteArray(pieces * Metainfo.HASH_SIZE) { it.toByte() }),
                ),
            )
        val root =
            BDictionary(
                mapOf(
                    BString("announce") to BString("http://tracker.example/annc"),
                    BString("info") to info,
                ),
            )
        return MetainfoParser.parse(Bencode.encode(root))
    }

    @Test
    fun theWindowHoldsEveryPeersPipeline() {
        // The measured case: 256 KiB pieces, so sixteen blocks each; fifty peers of sixteen
        // requests need eight hundred blocks, which is fifty pieces.
        val metainfo = torrent(pieceLength = 16L * PeerWire.BLOCK_SIZE, pieces = 4_000)
        val window = TorrentRuntime.startedPieces(metainfo, maxPeers = 50, pipelineDepth = 16)
        assertEquals(50, window, "fifty peers with a pipeline of sixteen need fifty pieces open")

        val blocksInWindow = window * 16
        assertTrue(
            blocksInWindow >= 50 * 16,
            "the window holds $blocksInWindow blocks and fifty peers can ask for ${50 * 16}",
        )
    }

    @Test
    fun aTorrentWithHugePiecesNeedsFewerSlotsForTheSamePeers() {
        // 4 MiB pieces are 256 blocks each, so the same eight hundred blocks fit in four pieces —
        // and the floor, not the arithmetic, is what decides.
        val metainfo = torrent(pieceLength = 256L * PeerWire.BLOCK_SIZE, pieces = 500)
        val window = TorrentRuntime.startedPieces(metainfo, maxPeers = 50, pipelineDepth = 16)
        assertEquals(8, window, "four pieces would do; the floor keeps eight")
    }

    @Test
    fun theWindowGrowsWithThePeerCountAndIsCapped() {
        val metainfo = torrent(pieceLength = 16L * PeerWire.BLOCK_SIZE, pieces = 40_000)
        val small = TorrentRuntime.startedPieces(metainfo, maxPeers = 10, pipelineDepth = 16)
        val large = TorrentRuntime.startedPieces(metainfo, maxPeers = 200, pipelineDepth = 16)
        assertTrue(large > small, "raising the peer count did not widen the window: $small then $large")
        // **A cap and not an unbounded window.** Every started piece is a partially written one,
        // and a client that opens thousands turns one sequential write into a scattered many.
        val absurd = TorrentRuntime.startedPieces(metainfo, maxPeers = 10_000, pipelineDepth = 64)
        assertEquals(256, absurd, "the window is not allowed to grow without bound")
    }
}
