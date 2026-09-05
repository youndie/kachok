package ru.workinprogress.kachok.engine.picker

import ru.workinprogress.kachok.engine.PieceIndex
import ru.workinprogress.kachok.engine.metainfo.Metainfo
import ru.workinprogress.kachok.engine.metainfo.MetainfoParser
import ru.workinprogress.kachok.engine.peer.PeerAddress
import ru.workinprogress.kachok.engine.wire.PeerWire
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The acceptance criteria of B-16.
 *
 * The torrent is ten pieces of one block each, which keeps every assertion about *which* piece
 * rather than about block arithmetic; one test uses a multi-block piece for the arithmetic.
 */
class PiecePickerTest {
    private val a = PeerAddress("10.0.0.1", 6881)
    private val b = PeerAddress("10.0.0.2", 6881)
    private val c = PeerAddress("10.0.0.3", 6881)

    /** Ten pieces of exactly one 16 KiB block. */
    private val tenPieces: Metainfo = torrent(pieces = 10, pieceLength = PeerWire.BLOCK_SIZE)

    private fun torrent(
        pieces: Int,
        pieceLength: Int,
    ): Metainfo {
        val hashes = "A".repeat(pieces * 20)
        val total = pieces.toLong() * pieceLength
        val source =
            "d4:infod6:lengthi${total}e4:name1:x12:piece lengthi${pieceLength}e" +
                "6:pieces${pieces * 20}:$hashes" + "ee"
        return MetainfoParser.parse(source.encodeToByteArray())
    }

    /** A peer that has exactly these pieces. */
    private fun PiecePicker.peerWith(
        peer: PeerAddress,
        vararg pieces: Int,
    ) {
        val bitfield = Bitfield(tenPieces.pieceCount)
        pieces.forEach { bitfield.set(it) }
        setBitfield(peer, bitfield.toBytes())
    }

    @Test
    fun theRarestPieceIsTakenFirst() {
        // Deterministic random so the "first piece is random" rule does not decide this test: one
        // piece is taken to get past it, then piece 7 is the only rare one left.
        val picker = PiecePicker(tenPieces, random = Random(1))
        picker.peerWith(a, *(0..9).toList().toIntArray())
        picker.peerWith(b, *(0..9).filter { it != 7 }.toIntArray())
        picker.peerWith(c, *(0..9).filter { it != 7 }.toIntArray())

        // Get the random first piece out of the way and complete it.
        val first = picker.next(a, 1).single()
        picker.blockReceived(a, first.piece, 0)
        picker.pieceVerified(first.piece)

        assertEquals(1, picker.availabilityOf(PieceIndex(7)))
        assertEquals(
            7,
            picker
                .next(a, 1)
                .single()
                .piece.value,
        )
    }

    @Test
    fun theFirstPieceOfATorrentIsChosenAtRandom() {
        // Every client starting at piece 0 makes piece 0 the only piece anyone has.
        val chosen =
            (0 until 40).map { seed ->
                val picker = PiecePicker(tenPieces, random = Random(seed))
                picker.peerWith(a, *(0..9).toList().toIntArray())
                picker
                    .next(a, 1)
                    .single()
                    .piece.value
            }
        assertTrue(chosen.toSet().size > 1, "forty seeds all chose piece ${chosen.first()}")
    }

    @Test
    fun aStartedPieceIsFinishedBeforeANewOneIsBegun() {
        val multiBlock = torrent(pieces = 4, pieceLength = PeerWire.BLOCK_SIZE * 4)
        val picker = PiecePicker(multiBlock, random = Random(7))
        val bitfield = Bitfield(multiBlock.pieceCount)
        (0 until multiBlock.pieceCount).forEach { bitfield.set(it) }
        picker.setBitfield(a, bitfield.toBytes())

        val opening = picker.next(a, 1).single()
        val next = picker.next(a, 3)
        assertTrue(
            next.all { it.piece.value == opening.piece.value },
            "asked for ${next.map { it.piece.value }} while piece ${opening.piece.value} was unfinished",
        )
        assertContentEquals(
            listOf(PeerWire.BLOCK_SIZE, PeerWire.BLOCK_SIZE * 2, PeerWire.BLOCK_SIZE * 3),
            next.map { it.begin },
        )
    }

    @Test
    fun theNumberOfStartedPiecesNeverExceedsTheBound() {
        val picker = PiecePicker(tenPieces, maxStartedPieces = 3, random = Random(3))
        picker.peerWith(a, *(0..9).toList().toIntArray())
        val requests = picker.next(a, 10)
        assertEquals(3, requests.size, "the bound is three pieces of one block each")
        assertEquals(3, requests.map { it.piece.value }.toSet().size)
    }

    @Test
    fun beingAtTheStartedPieceBoundIsNotEndgame() {
        // Both peers have everything and the bound is two pieces. Once a has been asked for both,
        // b gets nothing: there is plenty left to download, and duplicating a request while eight
        // pieces wait would spend bandwidth on bytes already on their way.
        val picker = PiecePicker(tenPieces, maxStartedPieces = 2, random = Random(5))
        picker.peerWith(a, *(0..9).toList().toIntArray())
        picker.peerWith(b, *(0..9).toList().toIntArray())

        val fromA = picker.next(a, 2)
        assertEquals(2, fromA.size)
        assertTrue(!picker.isEndgame, "eight pieces are still unstarted; this is the bound, not endgame")
        assertTrue(picker.next(b, 2).isEmpty(), "no duplicate requests outside endgame")
    }

    @Test
    fun endgameAsksSeveralPeersAndNamesTheOnesToCancel() {
        // The swarm holds two pieces between it; both are requested from a, so there is nothing
        // left to ask anyone for a first time and the download is waiting on its slowest peer —
        // which is what endgame exists to fix. Pieces 2..9 are held by nobody and do not count:
        // endgame waits for the last *reachable* blocks.
        val picker = PiecePicker(tenPieces, maxStartedPieces = 2, random = Random(5))
        picker.peerWith(a, 0, 1)
        picker.peerWith(b, 0, 1)
        val fromA = picker.next(a, 2)
        assertTrue(picker.isEndgame, "every block this swarm can serve is asked for")

        val fromB = picker.next(b, 2)
        assertEquals(fromA.map { it.piece.value }.toSet(), fromB.map { it.piece.value }.toSet())

        val toCancel = picker.blockReceived(b, fromB.first().piece, 0)
        assertEquals(listOf(a), toCancel, "the peer that lost the race is told to stop")
    }

    @Test
    fun aRequestNobodyAnsweredIsOfferedToSomebodyElse() {
        // The failure this prevents was measured, not imagined: against the Debian swarm the
        // download stalled at 960 pieces of 3020 with twenty-five connections, every one of them
        // waiting on a request its peer was never going to answer.
        val picker = PiecePicker(tenPieces, maxStartedPieces = 1, random = Random(5))
        picker.peerWith(a, 0, 1)
        picker.peerWith(b, 0, 1)
        val taken = picker.next(a, 1, nowMillis = 1_000).single()

        assertTrue(picker.expireRequests(beforeMillis = 500).isEmpty(), "not yet due")
        assertTrue(picker.next(b, 1, nowMillis = 1_100).isEmpty(), "a's claim still stands")

        val expired = picker.expireRequests(beforeMillis = 31_000)
        assertEquals(listOf(a), expired.map { it.peer })
        assertEquals(taken.piece.value, expired.single().piece.value)

        val retaken = picker.next(b, 1, nowMillis = 31_100).single()
        assertEquals(taken.piece.value, retaken.piece.value, "the freed block went to the other peer")
    }

    @Test
    fun anExpiredRequestIsNotOfferedTwiceToTheSamePeer() {
        val picker = PiecePicker(tenPieces, maxStartedPieces = 1, random = Random(5))
        picker.peerWith(a, 0)
        picker.next(a, 1, nowMillis = 0)
        assertEquals(1, picker.expireRequests(beforeMillis = 1_000).size)
        assertEquals(0, picker.expireRequests(beforeMillis = 1_000).size, "expiring twice frees nothing twice")
    }

    @Test
    fun aChokedPeersRequestsComeBackToThePool() {
        val picker = PiecePicker(tenPieces, maxStartedPieces = 1, random = Random(5))
        picker.peerWith(a, 0, 1)
        picker.peerWith(b, 0, 1)
        val taken = picker.next(a, 1).single()

        picker.requestsDropped(a)
        val retaken = picker.next(b, 1).single()
        assertEquals(taken.piece.value, retaken.piece.value, "the block a was asked for is free again")
    }

    @Test
    fun aVerifiedPieceIsNeverAskedForAgain() {
        val picker = PiecePicker(tenPieces, maxStartedPieces = 1, random = Random(5))
        picker.peerWith(a, 0, 1)
        val taken = picker.next(a, 1).single()
        picker.blockReceived(a, taken.piece, 0)
        picker.pieceVerified(taken.piece)

        assertTrue(picker.completed[taken.piece.value])
        val next = picker.next(a, 1)
        assertTrue(next.none { it.piece.value == taken.piece.value })
    }

    @Test
    fun aFailedPieceIsAskedForAgainFromTheStart() {
        val picker = PiecePicker(tenPieces, maxStartedPieces = 1, random = Random(5))
        picker.peerWith(a, 0)
        val taken = picker.next(a, 1).single()
        picker.blockReceived(a, taken.piece, 0)
        picker.pieceFailed(taken.piece)

        assertTrue(!picker.completed[taken.piece.value])
        assertEquals(
            taken.piece.value,
            picker
                .next(a, 1)
                .single()
                .piece.value,
        )
    }

    @Test
    fun aPeerThatLeavesTakesItsAvailabilityWithIt() {
        val picker = PiecePicker(tenPieces)
        picker.peerWith(a, 3)
        picker.peerWith(b, 3)
        assertEquals(2, picker.availabilityOf(PieceIndex(3)))
        picker.removePeer(b)
        assertEquals(1, picker.availabilityOf(PieceIndex(3)))
        assertTrue(picker.next(b, 1).isEmpty(), "a peer that is gone is asked for nothing")
    }

    @Test
    fun aHaveMessageRaisesAvailabilityWithoutABitfield() {
        val picker = PiecePicker(tenPieces)
        picker.addPeer(a)
        assertTrue(!picker.isInteresting(a), "a peer with nothing is not interesting")
        picker.addHave(a, PieceIndex(4))
        assertEquals(1, picker.availabilityOf(PieceIndex(4)))
        assertTrue(picker.isInteresting(a))
        // Twice is once: BEP 3 lets a peer repeat itself and availability must not drift.
        picker.addHave(a, PieceIndex(4))
        assertEquals(1, picker.availabilityOf(PieceIndex(4)))
    }

    @Test
    fun theLastBlockOfAShortPieceIsShort() {
        // 40 000 bytes at a 40 000-byte piece length: three blocks, the last of 7 232 bytes.
        val odd = torrent(pieces = 1, pieceLength = 40_000)
        val picker = PiecePicker(odd, random = Random(1))
        val bitfield = Bitfield(1).also { it.set(0) }
        picker.setBitfield(a, bitfield.toBytes())
        val requests = picker.next(a, 10)
        assertContentEquals(listOf(0, 16384, 32768), requests.map { it.begin })
        assertContentEquals(listOf(16384, 16384, 7232), requests.map { it.length })
    }
}
