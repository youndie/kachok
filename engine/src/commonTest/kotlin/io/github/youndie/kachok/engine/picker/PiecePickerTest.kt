package io.github.youndie.kachok.engine.picker

import io.github.youndie.kachok.engine.PieceIndex
import io.github.youndie.kachok.engine.metainfo.Metainfo
import io.github.youndie.kachok.engine.metainfo.MetainfoParser
import io.github.youndie.kachok.engine.peer.PeerAddress
import io.github.youndie.kachok.engine.wire.PeerWire
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    /**
     * A torrent of several files, for the rules that are about the layout rather than the pieces.
     *
     * The files are given as name to length; the piece count follows from the total, as it does in
     * a real torrent, so a file's two ends land where the arithmetic puts them and not where a test
     * would like them.
     */
    private fun torrent(
        files: List<Pair<String, Long>>,
        pieceLength: Int,
    ): Metainfo {
        val total = files.sumOf { it.second }
        val pieces = ((total + pieceLength - 1) / pieceLength).toInt()
        val hashes = "A".repeat(pieces * 20)
        val entries =
            files.joinToString("") { (name, length) ->
                "d6:lengthi${length}e4:pathl${name.length}:${name}ee"
            }
        val source =
            "d4:infod5:filesl${entries}e4:name1:x12:piece lengthi${pieceLength}e" +
                "6:pieces${pieces * 20}:$hashes" + "ee"
        return MetainfoParser.parse(source.encodeToByteArray())
    }

    /** A peer that has exactly these pieces. */
    private fun PiecePicker.peerWith(
        peer: PeerAddress,
        vararg pieces: Int,
    ): Unit = peerWithIn(peer, tenPieces.pieceCount, *pieces)

    /** The same, for a torrent that is not the ten-piece one. */
    private fun PiecePicker.peerWithIn(
        peer: PeerAddress,
        pieceCount: Int,
        vararg pieces: Int,
    ) {
        val bitfield = Bitfield(pieceCount)
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

    /**
     * A skipped piece is never asked for, by any route.
     *
     * Three routes reach the picker: the ordinary one, BEP 6's allowed-fast, and the endgame. The
     * first two are here; the endgame draws from started pieces, and a skipped piece is never
     * started.
     */
    @Test
    fun aSkippedPieceIsNeverAskedFor() {
        val picker = PiecePicker(tenPieces, random = Random(1))
        val skip = Bitfield(tenPieces.pieceCount).apply { (0..4).forEach { set(it) } }
        picker.skip(skip)
        picker.peerWith(a, *(0..9).toList().toIntArray())

        val asked = (1..20).flatMap { picker.next(a, 4) }.map { it.piece.value }.toSet()
        assertTrue(asked.isNotEmpty(), "nothing was asked for at all")
        assertEquals(emptySet(), asked.filter { it <= 4 }.toSet(), "a skipped piece was requested")

        assertEquals(
            emptyList(),
            picker.nextFrom(a, PieceIndex(0), 4),
            "a peer offering a skipped piece as allowed-fast was taken up on it",
        )
    }

    /** And the torrent is complete when the *wanted* pieces are, not when every piece is. */
    @Test
    fun completeMeansEveryWantedPiece() {
        val picker = PiecePicker(tenPieces, random = Random(1))
        picker.skip(Bitfield(tenPieces.pieceCount).apply { (0..4).forEach { set(it) } })
        picker.peerWith(a, *(0..9).toList().toIntArray())

        (5..9).forEach { picker.pieceVerified(PieceIndex(it)) }

        assertTrue(picker.isComplete, "five wanted pieces of five is not complete")
        assertEquals(5, picker.completed.cardinality, "and it still holds only what it fetched")
    }

    /**
     * A restore that finds skipped pieces already on the disk does not make the torrent complete.
     *
     * This is why the count is kept rather than derived: `have.cardinality + skipped >= size` is
     * true here while four wanted pieces are missing.
     */
    @Test
    fun skippedPiecesAlreadyOnTheDiskDoNotFinishTheTorrent() {
        val picker = PiecePicker(tenPieces, random = Random(1))
        picker.skip(Bitfield(tenPieces.pieceCount).apply { (0..4).forEach { set(it) } })
        picker.restore(Bitfield(tenPieces.pieceCount).apply { (0..5).forEach { set(it) } })

        assertFalse(picker.isComplete, "four wanted pieces are still missing")
        picker.peerWith(a, *(0..9).toList().toIntArray())
        (6..9).forEach { picker.pieceVerified(PieceIndex(it)) }
        assertTrue(picker.isComplete)
    }

    /** Skipping is a decision taken before anything is asked for, and says so. */
    @Test
    fun skippingRefusesAPickerThatHasAlreadyStartedAPiece() {
        val picker = PiecePicker(tenPieces, random = Random(1))
        picker.peerWith(a, *(0..9).toList().toIntArray())
        picker.next(a, 1)
        assertFailsWith<IllegalStateException> {
            picker.skip(Bitfield(tenPieces.pieceCount).apply { set(0) })
        }
    }

    /**
     * In order, after the two ends of the file, and those two are the whole exception.
     *
     * Not "rarest first with a window": that window exists to keep a player fed and needs an N
     * nobody here has a player to measure. The ends need no N — an MP4's `moov` is at the end of
     * the file, and a player that cannot read it will not start
     * ([B-118](../../../../../../../../docs/backlog/B-121-sequential-does-not-serve-a-player.md)).
     */
    @Test
    fun sequentialAsksForTheEndsOfTheFileAndThenTheOrder() {
        val picker = PiecePicker(tenPieces, maxStartedPieces = 1, random = Random(1), sequential = true)
        picker.peerWith(a, *(0..9).toList().toIntArray())

        val order =
            (0..4).map {
                val request = picker.next(a, 1).single()
                picker.blockReceived(a, request.piece, 0)
                picker.pieceVerified(request.piece)
                request.piece.value
            }
        assertEquals(listOf(0, 9, 1, 2, 3), order)
    }

    /**
     * Both ends of every file, ascending, before any of the middles.
     *
     * Three files of four pieces each: the ends are 0 and 3, 4 and 7, 8 and 11, and the order they
     * come in is the order a player wants them — a file's header, then its index, then the next
     * file's. What is asserted after them is that the middle resumes where it always was.
     */
    @Test
    fun sequentialTakesBothEndsOfEveryFileFirst() {
        val block = PeerWire.BLOCK_SIZE.toLong()
        val metainfo =
            torrent(
                files = listOf("one.mp4" to block * 4, "two.mp4" to block * 4, "three.mp4" to block * 4),
                pieceLength = PeerWire.BLOCK_SIZE,
            )
        val picker = PiecePicker(metainfo, maxStartedPieces = 1, random = Random(1), sequential = true)
        picker.peerWithIn(a, metainfo.pieceCount, *(0 until metainfo.pieceCount).toList().toIntArray())

        val order =
            (0..7).map {
                val request = picker.next(a, 1).single()
                picker.blockReceived(a, request.piece, 0)
                picker.pieceVerified(request.piece)
                request.piece.value
            }
        assertEquals(listOf(0, 3, 4, 7, 8, 11, 1, 2), order)
    }

    /**
     * A file that does not end on a piece boundary gets the piece before its last one too.
     *
     * **This is the case a real download found and the first rule missed.** The file ended 19 KB
     * into its last piece and its `moov` atom was 52 KB, so the atom started in the piece before:
     * with only the piece holding the last byte, `ffprobe` on the partial file said `moov atom not
     * found`. What is asked for is a piece-length of bytes at each end, which is one piece when the
     * boundary is aligned and two when it is not
     * ([B-118](../../../../../../../../docs/backlog/B-121-sequential-does-not-serve-a-player.md)).
     */
    @Test
    fun theTailCoversAWholePieceLengthWhereverTheFileEnds() {
        val block = PeerWire.BLOCK_SIZE.toLong()
        val metainfo =
            torrent(
                // Three and a half pieces: the file ends halfway through piece 3, so a piece-length
                // of tail reaches back into piece 2.
                files = listOf("film.mp4" to block * 7 / 2, "notes.txt" to block / 2),
                pieceLength = PeerWire.BLOCK_SIZE,
            )
        val picker = PiecePicker(metainfo, maxStartedPieces = 1, random = Random(1), sequential = true)
        picker.peerWithIn(a, metainfo.pieceCount, *(0 until metainfo.pieceCount).toList().toIntArray())

        val order =
            (0..3).map {
                val request = picker.next(a, 1).single()
                picker.blockReceived(a, request.piece, 0)
                picker.pieceVerified(request.piece)
                request.piece.value
            }
        assertEquals(listOf(0, 2, 3, 1), order, "the piece the file's index started in was not asked for")
    }

    /** A file nobody wants gets no head start: skip decides before priority does. */
    @Test
    fun theEndsOfASkippedFileAreNotFetched() {
        val block = PeerWire.BLOCK_SIZE.toLong()
        val metainfo =
            torrent(files = listOf("one.mp4" to block * 4, "two.mp4" to block * 4), pieceLength = PeerWire.BLOCK_SIZE)
        val picker = PiecePicker(metainfo, maxStartedPieces = 1, random = Random(1), sequential = true)
        picker.skip(Bitfield(metainfo.pieceCount).apply { (4..7).forEach { set(it) } })
        picker.peerWithIn(a, metainfo.pieceCount, *(0 until metainfo.pieceCount).toList().toIntArray())

        val order =
            (0..3).map {
                val request = picker.next(a, 1).single()
                picker.blockReceived(a, request.piece, 0)
                picker.pieceVerified(request.piece)
                request.piece.value
            }
        assertEquals(listOf(0, 3, 1, 2), order)
    }

    /**
     * And with the order off the ends are ordinary pieces.
     *
     * The default is rarest-first and every measured number assumes it, so a boundary piece that
     * five peers have must lose to a middle piece that one peer has.
     */
    @Test
    fun theEndsAreOnlyRaisedUnderSequential() {
        val picker = PiecePicker(tenPieces, maxStartedPieces = 1, random = Random(1))
        // Past the first-piece randomisation, so that rarity is what decides the next choice.
        picker.restore(Bitfield(tenPieces.pieceCount).apply { set(2) })
        picker.peerWith(a, *(0..9).toList().toIntArray())
        picker.peerWith(b, *(0..9).filter { it != 5 }.toIntArray())

        assertEquals(
            5,
            picker
                .next(a, 1)
                .single()
                .piece.value,
            "rarest-first took a boundary piece over the one piece only one peer has",
        )
    }

    /**
     * Turned on halfway, which is when anybody wants it.
     *
     * Somebody asks for order because they have started watching, and they start watching after the
     * download has started — so a picker whose order was fixed when the session was built could only
     * be told before the event that makes anybody want it
     * ([B-89](../../../../../../../../docs/backlog/B-89-sequential-on-a-running-torrent.md)). What
     * is asserted here is that the *next* choice changes and nothing already verified is asked for
     * again.
     */
    @Test
    fun theOrderCanBeChangedUnderARunningPicker() {
        val picker = PiecePicker(tenPieces, maxStartedPieces = 1, random = Random(1))
        picker.peerWith(a, *(0..9).toList().toIntArray())
        // Two pieces are already on the disk, wherever rarest-first put them.
        val alreadyHad = mutableSetOf<Int>()
        repeat(2) {
            val request = picker.next(a, 1).single()
            picker.blockReceived(a, request.piece, 0)
            picker.pieceVerified(request.piece)
            alreadyHad += request.piece.value
        }

        picker.sequential = true

        val next =
            (0..3).map {
                picker
                    .next(a, 1)
                    .single()
                    .piece.value
                    .also { piece -> picker.pieceVerified(PieceIndex(piece)) }
            }
        // The two ends of the file go first, minus whichever of them is already on the disk, and
        // what follows them is the order (B-121).
        val ends = listOf(0, 9).filterNot { it in alreadyHad }
        assertEquals(ends, next.take(ends.size), "the ends of the file were not asked for first")
        val middle = next.drop(ends.size)
        assertEquals(middle.sorted(), middle, "the order did not change under the running picker")
        assertTrue(
            next.none { it in alreadyHad },
            "a piece already verified was asked for again: had $alreadyHad, then asked for $next",
        )
    }

    /** And off again: somebody who has finished watching is back to being a good swarm member. */
    @Test
    fun theOrderCanBeChangedBack() {
        // Two, because the first piece stays started: `next` refuses to begin a second one at a
        // bound of one, and this is asking what it *chooses*, not what it is allowed to hold.
        val picker = PiecePicker(tenPieces, maxStartedPieces = 2, random = Random(1), sequential = true)
        picker.peerWith(a, *(0..9).toList().toIntArray())
        assertEquals(
            0,
            picker
                .next(a, 1)
                .single()
                .piece.value,
        )

        picker.sequential = false
        // Rarest-first with one peer holding everything picks at random on an empty picker; what
        // this asserts is only that it is no longer forced to the lowest, which is the claim.
        picker.peerWith(b, 9)
        assertEquals(
            9,
            picker
                .next(b, 1)
                .single()
                .piece.value,
            "with the order off, a peer holding only piece 9 is still asked for piece 9",
        )
    }

    /** A piece the peer has not got is skipped rather than waited for — an end of a file included. */
    @Test
    fun sequentialTakesTheLowestThatIsActuallyAvailable() {
        val picker = PiecePicker(tenPieces, maxStartedPieces = 1, random = Random(1), sequential = true)
        // Piece 9 is the end of the file and would go first; this peer has not got it, which makes
        // it exactly as useless as piece 0, which it has not got either.
        picker.peerWith(a, 3, 4)
        assertEquals(
            3,
            picker
                .next(a, 1)
                .single()
                .piece.value,
        )
    }

    /** And it obeys the same skip list a wanted-file selection sets. */
    @Test
    fun sequentialStillSkipsUnwantedPieces() {
        val picker = PiecePicker(tenPieces, maxStartedPieces = 1, random = Random(1), sequential = true)
        picker.skip(Bitfield(tenPieces.pieceCount).apply { (0..2).forEach { set(it) } })
        picker.peerWith(a, *(0..9).toList().toIntArray())
        // Piece 9 is the file's other end and is wanted; piece 0 is its first and is not, which is
        // the skip being obeyed by the rule that would otherwise have taken it before anything.
        val order =
            (0..1).map {
                val request = picker.next(a, 1).single()
                picker.blockReceived(a, request.piece, 0)
                picker.pieceVerified(request.piece)
                request.piece.value
            }
        assertEquals(listOf(9, 3), order)
    }

    /**
     * A tie between equally rare pieces is **drawn**, and this is the test that pins it.
     *
     * It used to go to the lowest index, and on a fresh swarm that is every tie there is: a piece
     * nobody holds yet has availability 1, so all of them are equally rare, every client resolves
     * the tie identically, and four clients end up asking for the same piece as each other for the
     * whole download. A swarm in lock step has nothing to trade — measured at **1.9 %** of the data
     * moving between four clients against **69 %** with the tie drawn, and three times the makespan
     * for it ([B-128](../../../../../../../../docs/backlog/B-128-ties-among-equally-rare-pieces.md),
     * research §1.2c6).
     *
     * Asserted across seeds rather than within one: what matters is that the answer depends on the
     * draw at all. Put the lowest index back and every seed gives the same piece, and this fails.
     */
    @Test
    fun aTieBetweenEquallyRarePiecesIsDrawnRatherThanTakenInOrder() {
        val drawn =
            (1..8).map { seed ->
                val picker = PiecePicker(tenPieces, maxStartedPieces = 1, random = Random(seed))
                // Past the first-piece rule, so what is under test is the tie and not that.
                picker.restore(Bitfield(tenPieces.pieceCount).apply { set(0) })
                picker.peerWith(a, *(0..9).toList().toIntArray())
                picker
                    .next(a, 1)
                    .single()
                    .piece.value
            }

        assertTrue(
            drawn.toSet().size > 1,
            "every seed asked for the same piece, so the tie is being resolved by position: $drawn",
        )
    }

    /**
     * And the draw is only ever among the *rarest*: rarity still decides, the draw only breaks it.
     *
     * The mutation this catches is the one that would make the change harmful — drawing among all
     * candidates instead of among the equally rare, which is not rarest-first at all.
     */
    @Test
    fun theDrawIsOnlyEverAmongTheRarest() {
        (1..8).forEach { seed ->
            val picker = PiecePicker(tenPieces, maxStartedPieces = 1, random = Random(seed))
            picker.restore(Bitfield(tenPieces.pieceCount).apply { set(0) })
            // Everybody has everything except piece 7, which one peer has: it is the only rare one.
            picker.peerWith(a, *(0..9).toList().toIntArray())
            picker.peerWith(b, *(0..9).filter { it != 7 }.toIntArray())
            picker.peerWith(c, *(0..9).filter { it != 7 }.toIntArray())

            assertEquals(
                7,
                picker
                    .next(a, 1)
                    .single()
                    .piece.value,
                "seed $seed drew a piece three peers have over the one only one peer has",
            )
        }
    }

    /** Rarest-first is untouched and stays the default: every measured number assumes it. */
    @Test
    fun theDefaultIsStillRarestFirst() {
        val picker = PiecePicker(tenPieces, random = Random(1))
        picker.peerWith(a, *(0..9).toList().toIntArray())
        picker.peerWith(b, *(0..9).filter { it != 7 }.toIntArray())
        picker.peerWith(c, *(0..9).filter { it != 7 }.toIntArray())
        val first = picker.next(a, 1).single()
        picker.blockReceived(a, first.piece, 0)
        picker.pieceVerified(first.piece)
        assertEquals(
            7,
            picker
                .next(a, 1)
                .single()
                .piece.value,
            "the rare piece was not preferred",
        )
    }

    // B-106: a raised file is a pool the picker empties first, and rarest-first still rules inside it.

    /** The raised pool is exhausted before the rarest ordinary piece is even considered. */
    @Test
    fun aRaisedPieceIsTakenBeforeARarerOrdinaryOne() {
        val picker = PiecePicker(tenPieces, maxStartedPieces = 1, random = Random(1))
        // Past the first-piece randomisation: the picker already has piece 0.
        picker.restore(Bitfield(tenPieces.pieceCount).apply { set(0) })
        picker.peerWith(a, *(0..9).toList().toIntArray())
        picker.peerWith(b, *(0..9).filter { it != 7 }.toIntArray())
        picker.peerWith(c, *(0..9).filter { it != 7 }.toIntArray())
        picker.prioritise(Bitfield(tenPieces.pieceCount), Bitfield(tenPieces.pieceCount).apply { set(3) })

        val order =
            (0..1).map {
                val request = picker.next(a, 1).single()
                picker.blockReceived(a, request.piece, 0)
                picker.pieceVerified(request.piece)
                request.piece.value
            }
        assertEquals(listOf(3, 7), order, "the raised piece was not taken first, or the rarest did not follow it")
    }

    /**
     * Inside the pool the rule is the rule: the rarest of the raised pieces, not the lowest.
     *
     * This is the mutation the item asks for. A tier that became an *order* — lowest raised piece
     * first — is strict sequential with a smaller scope, and every peer asking for the same pieces
     * is the swarm harm [B-65](../../../../../../../../docs/backlog/B-65-sequential-download.md)
     * already paid for.
     */
    @Test
    fun withinTheRaisedPoolTheRarestStillWins() {
        val picker = PiecePicker(tenPieces, maxStartedPieces = 1, random = Random(1))
        picker.restore(Bitfield(tenPieces.pieceCount).apply { set(0) })
        picker.peerWith(a, *(0..9).toList().toIntArray())
        picker.peerWith(b, *(0..9).filter { it != 7 }.toIntArray())
        picker.peerWith(c, *(0..9).filter { it != 7 }.toIntArray())
        picker.prioritise(
            Bitfield(tenPieces.pieceCount),
            Bitfield(tenPieces.pieceCount).apply {
                set(2)
                set(7)
            },
        )

        val order =
            (0..1).map {
                val request = picker.next(a, 1).single()
                picker.blockReceived(a, request.piece, 0)
                picker.pieceVerified(request.piece)
                request.piece.value
            }
        assertEquals(listOf(7, 2), order, "the pool was taken in index order rather than rarest first")
    }

    /** Under sequential order the pool is still first, and lowest-first inside it. */
    @Test
    fun sequentialTakesTheRaisedPoolInOrderAndThenTheRest() {
        val picker = PiecePicker(tenPieces, maxStartedPieces = 1, random = Random(1), sequential = true)
        picker.peerWith(a, *(0..9).toList().toIntArray())
        picker.prioritise(
            Bitfield(tenPieces.pieceCount),
            Bitfield(tenPieces.pieceCount).apply {
                set(6)
                set(7)
            },
        )

        val order =
            (0..2).map {
                val request = picker.next(a, 1).single()
                picker.blockReceived(a, request.piece, 0)
                picker.pieceVerified(request.piece)
                request.piece.value
            }
        assertEquals(listOf(6, 7, 0), order)
    }

    /**
     * Raised on a running picker, which is when anybody does it, and only the *next* choice moves.
     *
     * The piece already begun keeps its slot and its outstanding request; a `prioritise` that
     * threw the way `skip` does would make the Files tab a control that works only before the
     * download starts.
     */
    @Test
    fun raisingAFileOnARunningPickerChangesOnlyWhatBeginsNext() {
        val picker = PiecePicker(tenPieces, maxStartedPieces = 2, random = Random(1))
        picker.restore(Bitfield(tenPieces.pieceCount).apply { set(0) })
        picker.peerWith(a, *(0..9).toList().toIntArray())
        val first = picker.next(a, 1).single().piece
        // Whichever it drew, and the test raises a different one: with every candidate equally rare
        // the tie is drawn, so naming the piece here would be naming the seed's first answer.
        val raised = (1..9).first { it != first.value }

        picker.prioritise(Bitfield(tenPieces.pieceCount), Bitfield(tenPieces.pieceCount).apply { set(raised) })

        val second = picker.next(a, 1).single().piece
        assertEquals(raised, second.value, "the raised piece was not the next one begun")
        assertEquals(2, picker.startedPieces, "the piece in flight was dropped by the change")
        picker.blockReceived(a, first, 0)
        picker.pieceVerified(first)
        assertTrue(picker.completed[first.value], "the piece begun before the change could not finish")
    }

    /** Skipped mid-run: the started piece finishes, and none of the rest of that file begins. */
    @Test
    fun skippingMidRunLetsTheStartedPieceFinishAndBeginsNoOther() {
        val picker = PiecePicker(tenPieces, maxStartedPieces = 1, random = Random(1))
        picker.restore(Bitfield(tenPieces.pieceCount).apply { set(0) })
        picker.peerWith(a, *(0..9).toList().toIntArray())
        val inFlight = picker.next(a, 1).single().piece
        // The skipped set is built around whatever was drawn, which makes this a stronger test than
        // the one that named piece 1: the piece in flight is *certainly* one of the skipped now.
        val skipped = (setOf(inFlight.value) + (1..9).filter { it != inFlight.value }.take(2)).toSet()

        picker.prioritise(
            Bitfield(tenPieces.pieceCount).apply { skipped.forEach { set(it) } },
            Bitfield(tenPieces.pieceCount),
        )
        picker.blockReceived(a, inFlight, 0)
        picker.pieceVerified(inFlight)
        assertTrue(picker.completed[inFlight.value], "the piece in flight could not finish after the skip")

        val next = picker.next(a, 1).single().piece.value
        assertFalse(next in skipped, "a piece of the skipped file was begun after the skip")
        assertFalse(picker.isComplete)
    }

    /** Un-skipped mid-run: the pieces come back as candidates, and the torrent is no longer complete. */
    @Test
    fun unskippingMidRunMakesThePiecesCandidatesAgain() {
        val picker = PiecePicker(tenPieces, maxStartedPieces = 1, random = Random(1))
        picker.skip(Bitfield(tenPieces.pieceCount).apply { (5..9).forEach { set(it) } })
        picker.restore(Bitfield(tenPieces.pieceCount).apply { (0..4).forEach { set(it) } })
        assertTrue(picker.isComplete, "everything wanted is on the disk")
        picker.peerWith(a, *(0..9).toList().toIntArray())
        assertTrue(picker.next(a, 1).isEmpty(), "a complete torrent asked for something")

        picker.prioritise(Bitfield(tenPieces.pieceCount), Bitfield(tenPieces.pieceCount))

        assertFalse(picker.isComplete, "un-skipping five files left the torrent complete")
        val next = picker.next(a, 1).single().piece.value
        assertTrue(next in 5..9, "the piece asked for after un-skipping was not one of the un-skipped: $next")
    }
}
