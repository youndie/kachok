package io.github.youndie.kachok.engine.runtime

import io.github.youndie.kachok.engine.bencode.BDictionary
import io.github.youndie.kachok.engine.bencode.BInteger
import io.github.youndie.kachok.engine.bencode.BString
import io.github.youndie.kachok.engine.bencode.Bencode
import io.github.youndie.kachok.engine.metainfo.Metainfo
import io.github.youndie.kachok.engine.metainfo.MetainfoParser
import io.github.youndie.kachok.engine.peer.PeerAddress
import io.github.youndie.kachok.engine.picker.Bitfield
import io.github.youndie.kachok.engine.picker.PiecePicker
import io.github.youndie.kachok.engine.wire.PeerWire
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The acceptance criteria of B-174: the client's started pieces fit the memory they live in. */
class SharedBlockBudgetTest {
    @Test
    fun nothingIsReservedBeyondTheCapacity() {
        val budget = SharedBlockBudget(capacity = 8)
        val holder = budget.holder()
        repeat(4) { assertTrue(holder.tryReserve(2), "piece $it of four fits") }
        assertFalse(holder.tryReserve(2), "a fifth would be over the line")
        assertEquals(8, budget.reservedBlocks)
    }

    @Test
    fun aSecondTorrentGetsTheNextPieceThatCloses() {
        // The first torrent took everything while it was alone, which is right. Once the second
        // asks, the first is over its share: the next piece that closes goes to the second, and
        // the first begins nothing until it is back under half.
        val budget = SharedBlockBudget(capacity = 8)
        val first = budget.holder()
        val second = budget.holder()
        repeat(4) { assertTrue(first.tryReserve(2)) }
        assertFalse(second.tryReserve(2), "no room is no room, even for a torrent holding nothing")

        first.release(2)
        assertFalse(first.tryReserve(2), "six of eight is over a share of four once two torrents hold pieces")
        assertTrue(second.tryReserve(2), "the torrent holding nothing gets the freed piece")
        first.release(2)
        assertFalse(first.tryReserve(2), "four is the share, and a fifth and sixth block would pass it")
        assertTrue(second.tryReserve(2), "and the second may grow to its share")
    }

    @Test
    fun aTorrentHoldingNothingAlwaysGetsOnePieceWhenThereIsRoom() {
        val budget = SharedBlockBudget(capacity = 9)
        val holders = List(3) { budget.holder() }
        assertTrue(holders[0].tryReserve(3))
        assertTrue(holders[0].tryReserve(3))
        assertTrue(holders[1].tryReserve(3), "the second's first piece, in the last of the room")
        assertFalse(holders[2].tryReserve(3), "full")
    }

    @Test
    fun aPieceLargerThanTheBudgetStillBeginsAlone() {
        val budget = SharedBlockBudget(capacity = 4)
        val holder = budget.holder()
        assertTrue(holder.tryReserve(6), "a torrent that could never begin a piece could never finish")
        assertFalse(budget.holder().tryReserve(1), "and nothing else begins beside it")
        holder.release(6)
        assertEquals(0, budget.reservedBlocks)
    }

    @Test
    fun closingATorrentFreesWhatItHeld() {
        val budget = SharedBlockBudget(capacity = 8)
        val holder = budget.holder()
        holder.tryReserve(4)
        holder.close()
        assertEquals(0, budget.reservedBlocks)
        holder.release(4)
        assertEquals(0, budget.reservedBlocks, "a session closing its pieces after the close gives back nothing twice")
    }

    @Test
    fun theWindowsMemoryIsSplitIntoBuffersAndReservations() {
        // The window and the CLI pin 192 MiB; a quarter stays the JDK's, and a block per
        // connection is kept out of the reservations for reads in progress.
        val (buffers, reservable) = SharedBlockBudget.split(192L * 1024 * 1024, maxConnections = 200)
        assertEquals(9216, buffers)
        assertEquals(9016, reservable)
    }

    /**
     * The shape the window stood still in on 2026-10-31: three torrents in 8 MiB pieces, each
     * with a window wide enough for 500 peers, and memory for a fraction of that. Without the
     * budget every picker opens its whole window; with it, the pieces open across the three never
     * need more blocks than there are buffers to hold them, and every torrent has one open.
     */
    @Test
    fun threeTorrentsWideWindowsFitTheMemoryTogether() {
        val (buffers, reservable) = SharedBlockBudget.split(192L * 1024 * 1024, maxConnections = 200)
        val budget = SharedBlockBudget(reservable)
        val pieceLength = 8 * 1024 * 1024
        val blocksPerPiece = pieceLength / PeerWire.BLOCK_SIZE
        val torrent = torrent(pieces = 400, pieceLength = pieceLength)
        val peers = List(20) { PeerAddress("10.0.0.${it + 1}", 6881) }
        val everything = Bitfield(torrent.pieceCount).also { all -> (0 until torrent.pieceCount).forEach(all::set) }

        fun picker(
            seed: Int,
            budget: SharedBlockBudget.Holder?,
        ): PiecePicker =
            PiecePicker(torrent, maxStartedPieces = 256, random = Random(seed), budget = budget).also { picker ->
                peers.forEach { peer -> picker.setBitfield(peer, everything.toBytes()) }
            }

        fun PiecePicker.askEveryPeer() = repeat(3) { round -> peers.forEach { next(it, 64, round.toLong()) } }

        val pickers = List(3) { picker(it, budget.holder()) }
        pickers.forEach { it.askEveryPeer() }
        val open = pickers.sumOf { it.startedPieces }
        assertTrue(
            open * blocksPerPiece <= buffers,
            "$open open pieces need ${open * blocksPerPiece} blocks of $buffers",
        )
        assertTrue(
            pickers.all { it.startedPieces >= 1 },
            "every torrent has a piece open: ${pickers.map { it.startedPieces }}",
        )
        val unbounded = picker(9, budget = null).also { it.askEveryPeer() }
        assertTrue(
            unbounded.startedPieces * blocksPerPiece * 3 > buffers,
            "without the budget the same three would have needed more than there is — the test shows nothing otherwise",
        )
    }

    private fun torrent(
        pieces: Int,
        pieceLength: Int,
    ): Metainfo {
        val info =
            BDictionary(
                mapOf(
                    BString("length") to BInteger(pieces.toLong() * pieceLength),
                    BString("name") to BString("x"),
                    BString("piece length") to BInteger(pieceLength.toLong()),
                    BString("pieces") to BString(ByteArray(pieces * 20)),
                ),
            )
        return MetainfoParser.parse(Bencode.encode(BDictionary(mapOf(BString("info") to info))))
    }
}
