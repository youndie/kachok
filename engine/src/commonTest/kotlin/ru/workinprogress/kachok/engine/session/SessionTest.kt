package ru.workinprogress.kachok.engine.session

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.runTest
import ru.workinprogress.kachok.engine.PeerId
import ru.workinprogress.kachok.engine.PieceIndex
import ru.workinprogress.kachok.engine.bencode.BDictionary
import ru.workinprogress.kachok.engine.bencode.BInteger
import ru.workinprogress.kachok.engine.bencode.BString
import ru.workinprogress.kachok.engine.bencode.Bencode
import ru.workinprogress.kachok.engine.metainfo.Metainfo
import ru.workinprogress.kachok.engine.metainfo.MetainfoParser
import ru.workinprogress.kachok.engine.peer.Block
import ru.workinprogress.kachok.engine.peer.PeerAddress
import ru.workinprogress.kachok.engine.peer.PeerConnection
import ru.workinprogress.kachok.engine.peer.PeerDialer
import ru.workinprogress.kachok.engine.peer.PeerEvent
import ru.workinprogress.kachok.engine.picker.Bitfield
import ru.workinprogress.kachok.engine.storage.PieceHasher
import ru.workinprogress.kachok.engine.storage.Storage
import ru.workinprogress.kachok.engine.tracker.AnnounceEvent
import ru.workinprogress.kachok.engine.tracker.AnnounceRequest
import ru.workinprogress.kachok.engine.tracker.AnnounceResponse
import ru.workinprogress.kachok.engine.tracker.TrackerClient
import ru.workinprogress.kachok.engine.tracker.TrackerException
import ru.workinprogress.kachok.engine.wire.Handshake
import ru.workinprogress.kachok.engine.wire.Message
import ru.workinprogress.kachok.engine.wire.PeerWire
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The acceptance criteria of B-17, entirely on fakes.
 *
 * Nothing here touches a socket or a disk, which is the point of the interfaces the session is
 * written against: every rule in it — interest, the pipeline, cancels, the shutdown order — is a
 * decision the session makes, and a decision is testable without the thing it decides about.
 *
 * The torrent fixture is built with the engine's own bencoder rather than written out by hand.
 * Hand-written bencode has been wrong three times in this repository, and the metainfo parser is
 * not what these tests are about.
 */
class SessionTest {
    /**
     * Anything a session coroutine throws lands here instead of in the test framework's
     * platform-dependent uncaught-exception path, where it surfaces as "uncaught exceptions before
     * the test started" in whichever test happens to run next.
     */
    private val escaped = mutableListOf<Throwable>()

    private val handler =
        kotlinx.coroutines.CoroutineExceptionHandler { _, failure ->
            escaped += failure
        }

    @kotlin.test.AfterTest
    fun nothingEscaped() {
        kotlin.test.assertTrue(escaped.isEmpty(), "a session coroutine threw: ${escaped.joinToString()}")
        kotlin.test.assertTrue(
            sessions.all { it.state.value.sessionError == null },
            "a session loop failed: ${sessions.mapNotNull { it.state.value.sessionError }}",
        )
    }

    /** Every session these tests build, so that the check above can see all of them. */
    private val sessions = mutableListOf<Session>()

    private val peerA = PeerAddress("10.0.0.1", 6881)
    private val peerB = PeerAddress("10.0.0.2", 6881)
    private val ourPeerId = PeerId("-KA0001-0123456789AB".encodeToByteArray())

    /** [pieces] pieces of exactly one block each. */
    private fun torrent(pieces: Int): Metainfo {
        val info =
            BDictionary(
                mapOf(
                    BString("length") to BInteger(pieces.toLong() * PeerWire.BLOCK_SIZE),
                    BString("name") to BString("fixture"),
                    BString("piece length") to BInteger(PeerWire.BLOCK_SIZE.toLong()),
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

    private class FakeConnection(
        override val address: PeerAddress,
        infoHash: ru.workinprogress.kachok.engine.InfoHash,
    ) : PeerConnection {
        val incoming = Channel<PeerEvent>(Channel.UNLIMITED)
        val sent = mutableListOf<Message>()
        var closes = 0

        override val handshake: Handshake =
            Handshake(infoHash, PeerId("-FAKE01-000000000000".encodeToByteArray()))

        override val events: ReceiveChannel<PeerEvent> get() = incoming

        override suspend fun send(message: Message) {
            sent += message
        }

        /** Blocks this peer was served, and how many bytes of them. */
        val servedBlocks = mutableListOf<Triple<Int, Int, Int>>()

        override var uploaded: Long = 0L
            private set

        override suspend fun sendBlock(
            piece: ru.workinprogress.kachok.engine.PieceIndex,
            begin: Int,
            length: Int,
        ) {
            servedBlocks += Triple(piece.value, begin, length)
            uploaded += length.toLong()
        }

        override fun close() {
            closes++
            incoming.close()
        }
    }

    private class FakeDialer(
        private val infoHash: ru.workinprogress.kachok.engine.InfoHash,
        private val refuse: Set<PeerAddress> = emptySet(),
    ) : PeerDialer {
        val connections = LinkedHashMap<PeerAddress, FakeConnection>()
        val dialled = mutableListOf<PeerAddress>()

        override suspend fun connect(address: PeerAddress): PeerConnection {
            dialled += address
            if (address in refuse) throw TrackerException("refused")
            return connections.getOrPut(address) { FakeConnection(address, infoHash) }
        }
    }

    private class FakeTracker(
        private val peers: List<PeerAddress>,
        private val failWith: String? = null,
    ) : TrackerClient {
        val events = mutableListOf<AnnounceEvent?>()

        override suspend fun announce(
            tracker: String,
            request: AnnounceRequest,
        ): AnnounceResponse {
            events += request.event
            if (failWith != null) throw TrackerException(failWith)
            return AnnounceResponse(interval = 1800, peers = peers)
        }
    }

    private class FakeStorage : Storage {
        val written = mutableListOf<PieceIndex>()
        var flushes = 0

        override suspend fun write(
            piece: PieceIndex,
            blocks: List<Block>,
        ) {
            written += piece
        }

        /** Pieces this fake claims to hold, so a restore test can put data on the "disk". */
        val present = mutableSetOf<Int>()

        override suspend fun readPiece(piece: PieceIndex): List<Block>? =
            if (piece.value in present) listOf(FakeBlock(piece, 0, PeerWire.BLOCK_SIZE)) else null

        override suspend fun flush() {
            flushes++
        }
    }

    /** Returns the torrent's own hash for the piece, so every piece verifies. */
    private class AgreeableHasher(
        private val metainfo: Metainfo,
    ) : PieceHasher {
        override suspend fun hash(blocks: List<Block>): ByteArray = metainfo.pieceHash(blocks.first().piece)
    }

    /** Returns something the torrent never claimed, so every piece fails. */
    private class DisagreeableHasher : PieceHasher {
        override suspend fun hash(blocks: List<Block>): ByteArray = ByteArray(Metainfo.HASH_SIZE) { 0xFF.toByte() }
    }

    private class FakeBlock(
        override val piece: PieceIndex,
        override val begin: Int,
        override val length: Int,
    ) : Block {
        var released = false

        override fun release() {
            released = true
        }
    }

    private fun session(
        metainfo: Metainfo,
        dialer: PeerDialer,
        tracker: TrackerClient,
        storage: Storage,
        hasher: PieceHasher,
        config: SessionConfig = SessionConfig(maxStartedPieces = 4, pipelineDepth = 2, maxPeers = 10),
    ) = Session(
        metainfo = metainfo,
        peerId = ourPeerId,
        listenPort = 6881,
        dialer = dialer,
        trackerClient = tracker,
        hasher = hasher,
        storage = storage,
        config = config,
    ).also { sessions += it }

    @Test
    fun addingATorrentAnnouncesConnectsAndRequests() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val dialer = FakeDialer(metainfo.infoHash)
            val tracker = FakeTracker(listOf(peerA, peerB))
            val storage = FakeStorage()
            val session = session(metainfo, dialer, tracker, storage, AgreeableHasher(metainfo))

            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            assertEquals(
                listOf<AnnounceEvent?>(AnnounceEvent.STARTED),
                tracker.events,
                "the first announce says started",
            )
            assertEquals(listOf(peerA, peerB), dialer.dialled, "both peers the tracker named were dialled")
            assertEquals(2, session.state.value.connectedPeers)

            // The peer says what it has and unchokes us; only then may we ask for anything.
            val connection = dialer.connections.getValue(peerA)
            val everything =
                Bitfield(metainfo.pieceCount).also { bits ->
                    (0 until metainfo.pieceCount).forEach { bits.set(it) }
                }
            connection.incoming.send(PeerEvent.Received(Message.Bitfield(everything.toBytes())))
            connection.incoming.send(PeerEvent.Received(Message.Unchoke))
            testScheduler.runCurrent()

            assertTrue(connection.sent.first() is Message.Interested, "interest comes before requests")
            val requests = connection.sent.filterIsInstance<Message.Request>()
            assertEquals(2, requests.size, "the pipeline depth is two, so two requests are outstanding")
            assertEquals(PeerWire.BLOCK_SIZE, requests.first().length)

            job.cancelAndJoin()
        }

    @Test
    fun aVerifiedPieceIsAnnouncedToEveryPeerAndCountedOnce() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val dialer = FakeDialer(metainfo.infoHash)
            val tracker = FakeTracker(listOf(peerA, peerB))
            val storage = FakeStorage()
            val session = session(metainfo, dialer, tracker, storage, AgreeableHasher(metainfo))

            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            val connection = dialer.connections.getValue(peerA)
            val block = FakeBlock(PieceIndex(1), 0, PeerWire.BLOCK_SIZE)
            connection.incoming.send(PeerEvent.BlockReceived(block))
            testScheduler.runCurrent()

            assertEquals(listOf(PieceIndex(1).value), storage.written.map { it.value })
            assertTrue(block.released, "the writer released the block after writing it")

            val state = session.state.value
            assertEquals(1, state.completedPieces)
            assertEquals(PeerWire.BLOCK_SIZE.toLong(), state.downloaded)
            assertEquals(metainfo.totalLength - PeerWire.BLOCK_SIZE, state.left)
            assertTrue(!state.isComplete)

            dialer.connections.values.forEach { peer ->
                assertTrue(
                    peer.sent.filterIsInstance<Message.Have>().any { it.piece.value == 1 },
                    "${peer.address} was not told we have piece 1",
                )
            }
            job.cancelAndJoin()
        }

    @Test
    fun theDownloadedCountOnlyEverRises() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val dialer = FakeDialer(metainfo.infoHash)
            val session =
                session(
                    metainfo,
                    dialer,
                    FakeTracker(listOf(peerA)),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            val seen = mutableListOf<Long>()
            val connection = dialer.connections.getValue(peerA)
            (0 until metainfo.pieceCount).forEach { index ->
                connection.incoming.send(PeerEvent.BlockReceived(FakeBlock(PieceIndex(index), 0, PeerWire.BLOCK_SIZE)))
                testScheduler.runCurrent()
                seen += session.state.value.downloaded
            }

            assertEquals(seen.sorted(), seen, "the downloaded count went backwards: $seen")
            assertEquals(metainfo.totalLength, seen.last())
            assertTrue(session.state.value.isComplete)
            assertEquals(0L, session.state.value.left)
            job.cancelAndJoin()
        }

    @Test
    fun aHashMismatchIsCountedAndNothingIsWritten() =
        runTest {
            val metainfo = torrent(pieces = 2)
            val dialer = FakeDialer(metainfo.infoHash)
            val storage = FakeStorage()
            val session = session(metainfo, dialer, FakeTracker(listOf(peerA)), storage, DisagreeableHasher())

            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            val connection = dialer.connections.getValue(peerA)
            connection.incoming.send(PeerEvent.BlockReceived(FakeBlock(PieceIndex(0), 0, PeerWire.BLOCK_SIZE)))
            testScheduler.runCurrent()

            assertEquals(1, session.state.value.hashFailures)
            assertEquals(0, session.state.value.completedPieces)
            assertEquals(0L, session.state.value.downloaded)
            assertTrue(storage.written.isEmpty(), "a piece that failed its hash reached the storage")
            job.cancelAndJoin()
        }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun theTimerFlushesOnItsIntervalAndNotPerPiece() =
        runTest {
            // B-14: `force()` on a schedule, never per piece. A flush per piece turns every piece
            // into a synchronous disk round trip; the operating system's page cache chooses the
            // moment better, and the resume record — which vouches only for hashed pieces — is
            // what makes deferring it safe.
            val metainfo = torrent(pieces = 4)
            val dialer = FakeDialer(metainfo.infoHash)
            val storage = FakeStorage()
            val session =
                session(
                    metainfo,
                    dialer,
                    FakeTracker(listOf(peerA)),
                    storage,
                    AgreeableHasher(metainfo),
                    config =
                        SessionConfig(
                            maxStartedPieces = 4,
                            pipelineDepth = 2,
                            maxPeers = 10,
                            tick = 1.seconds,
                            flushInterval = 5.seconds,
                        ),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            assertEquals(0, storage.flushes, "nothing is flushed before the interval")

            val connection = dialer.connections.getValue(peerA)
            (0 until metainfo.pieceCount).forEach { index ->
                connection.incoming.send(
                    PeerEvent.BlockReceived(FakeBlock(PieceIndex(index), 0, PeerWire.BLOCK_SIZE)),
                )
            }
            testScheduler.runCurrent()
            assertEquals(4, storage.written.size, "four pieces reached the disk")
            assertEquals(0, storage.flushes, "and not one of them cost a flush")

            testScheduler.advanceTimeBy(5_500)
            testScheduler.runCurrent()
            assertEquals(1, storage.flushes, "one flush, on the interval")

            testScheduler.advanceTimeBy(5_500)
            testScheduler.runCurrent()
            assertEquals(2, storage.flushes)

            session.send(Command.Stop)
            testScheduler.runCurrent()
            assertEquals(3, storage.flushes, "and one more at the end, after the peers are closed")
            assertTrue(job.isCancelled || job.isCompleted)
        }

    private class FakeResumeStore : ru.workinprogress.kachok.engine.resume.ResumeStore {
        val saved = mutableListOf<ru.workinprogress.kachok.engine.resume.ResumeRecord>()

        override suspend fun load(): ru.workinprogress.kachok.engine.resume.ResumeRecord? = null

        override suspend fun save(record: ru.workinprogress.kachok.engine.resume.ResumeRecord) {
            saved += record
        }
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun progressIsRecordedOnTheIntervalAndAfterTheFinalFlush() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val dialer = FakeDialer(metainfo.infoHash)
            val storage = FakeStorage()
            val store = FakeResumeStore()
            val session =
                Session(
                    metainfo = metainfo,
                    peerId = ourPeerId,
                    listenPort = 6881,
                    dialer = dialer,
                    trackerClient = FakeTracker(listOf(peerA)),
                    hasher = AgreeableHasher(metainfo),
                    storage = storage,
                    resume = store,
                    config =
                        SessionConfig(
                            maxStartedPieces = 4,
                            pipelineDepth = 2,
                            maxPeers = 10,
                            tick = 1.seconds,
                            flushInterval = 5.seconds,
                            resumeInterval = 5.seconds,
                        ),
                ).also { sessions += it }

            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            val connection = dialer.connections.getValue(peerA)
            connection.incoming.send(PeerEvent.BlockReceived(FakeBlock(PieceIndex(2), 0, PeerWire.BLOCK_SIZE)))
            testScheduler.runCurrent()

            testScheduler.advanceTimeBy(5_500)
            testScheduler.runCurrent()
            assertEquals(1, store.saved.size, "one record, on the interval")
            assertTrue(store.saved.last().verified[2], "the piece that was verified is in the record")
            assertEquals(
                1,
                store.saved
                    .last()
                    .verified.cardinality,
                "and nothing else is",
            )

            session.send(Command.Stop)
            testScheduler.runCurrent()
            assertEquals(2, store.saved.size, "and one more at the end")
            assertTrue(
                storage.flushes >= 1,
                "the record must be written after the flush, never before: it vouches for what is on the disk",
            )
            assertTrue(job.isCancelled || job.isCompleted)
        }

    @Test
    fun stoppingTellsTheTrackerAndClosesEveryPeer() =
        runTest {
            val metainfo = torrent(pieces = 2)
            val dialer = FakeDialer(metainfo.infoHash)
            val tracker = FakeTracker(listOf(peerA, peerB))
            val storage = FakeStorage()
            val session = session(metainfo, dialer, tracker, storage, AgreeableHasher(metainfo))

            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            assertEquals(2, session.state.value.connectedPeers)

            session.send(Command.Stop)
            testScheduler.runCurrent()

            assertEquals(
                listOf<AnnounceEvent?>(AnnounceEvent.STARTED, AnnounceEvent.STOPPED),
                tracker.events,
                "the tracker heard stopped while the peers were still up",
            )
            dialer.connections.values.forEach { peer ->
                assertTrue(peer.closes >= 1, "${peer.address} was never closed")
            }
            assertTrue(storage.flushes >= 1, "the data was not made durable before the session ended")
            assertTrue(job.isCancelled || job.isCompleted)
        }

    @Test
    fun cancellingTheScopeClosesEveryPeer() =
        runTest {
            val metainfo = torrent(pieces = 2)
            val dialer = FakeDialer(metainfo.infoHash)
            val session =
                session(
                    metainfo,
                    dialer,
                    FakeTracker(listOf(peerA, peerB)),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            job.cancelAndJoin()
            testScheduler.runCurrent()

            dialer.connections.values.forEach { peer ->
                assertTrue(peer.closes >= 1, "${peer.address} outlived the session it belonged to")
            }
        }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun anInterestedPeerIsUnchokedAndServedThePiecesWeHave() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val dialer = FakeDialer(metainfo.infoHash)
            val session =
                session(metainfo, dialer, FakeTracker(listOf(peerA)), FakeStorage(), AgreeableHasher(metainfo))
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            val connection = dialer.connections.getValue(peerA)
            // We have piece 1 and nothing else.
            connection.incoming.send(PeerEvent.BlockReceived(FakeBlock(PieceIndex(1), 0, PeerWire.BLOCK_SIZE)))
            connection.incoming.send(PeerEvent.Received(Message.Interested))
            testScheduler.runCurrent()
            assertTrue(
                connection.sent.none { it === Message.Unchoke },
                "interest alone unchokes nobody: the algorithm runs on the timer, not on a peer's word",
            )

            testScheduler.advanceTimeBy(11_000)
            testScheduler.runCurrent()
            assertTrue(
                connection.sent.any { it === Message.Unchoke },
                "the ten-second pass never unchoked an interested peer, so nothing can be served",
            )

            connection.incoming.send(PeerEvent.Received(Message.Request(PieceIndex(1), 0, PeerWire.BLOCK_SIZE)))
            connection.incoming.send(PeerEvent.Received(Message.Request(PieceIndex(2), 0, PeerWire.BLOCK_SIZE)))
            testScheduler.runCurrent()

            assertEquals(
                listOf(Triple(1, 0, PeerWire.BLOCK_SIZE)),
                connection.servedBlocks,
                "only the piece we actually have is served",
            )
            assertEquals(PeerWire.BLOCK_SIZE.toLong(), session.state.value.uploaded)
            job.cancelAndJoin()
        }

    @Test
    fun aRequestLargerThanABlockClosesTheConnection() =
        runTest {
            // BEP 3: "all current implementations … close connections which request an amount
            // greater than that". A peer asking for a megabyte is broken or trying something.
            val metainfo = torrent(pieces = 2)
            val dialer = FakeDialer(metainfo.infoHash)
            val session =
                session(metainfo, dialer, FakeTracker(listOf(peerA)), FakeStorage(), AgreeableHasher(metainfo))
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            val connection = dialer.connections.getValue(peerA)
            connection.incoming.send(
                PeerEvent.Received(Message.Request(PieceIndex(0), 0, PeerWire.BLOCK_SIZE * 2)),
            )
            testScheduler.runCurrent()

            assertTrue(connection.closes >= 1, "the connection should have been closed")
            assertTrue(connection.servedBlocks.isEmpty())
            job.cancelAndJoin()
        }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun onlySoManyPeersAreServedAtOnce() =
        runTest {
            val metainfo = torrent(pieces = 2)
            val dialer = FakeDialer(metainfo.infoHash)
            val session =
                session(
                    metainfo,
                    dialer,
                    FakeTracker(listOf(peerA, peerB)),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                    config =
                        SessionConfig(
                            maxStartedPieces = 4,
                            pipelineDepth = 2,
                            maxPeers = 10,
                            maxUnchoked = 1,
                        ),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            dialer.connections.values.forEach { it.incoming.send(PeerEvent.Received(Message.Interested)) }
            testScheduler.advanceTimeBy(11_000)
            testScheduler.runCurrent()

            val unchoked = dialer.connections.values.count { peer -> peer.sent.any { it === Message.Unchoke } }
            assertEquals(
                1,
                unchoked,
                "one regular slot taken by an interested optimistic peer leaves room for nobody else",
            )
            job.cancelAndJoin()
        }

    @Test
    fun aTrackerRefusalIsRecordedInItsOwnWords() =
        runTest {
            val metainfo = torrent(pieces = 2)
            val dialer = FakeDialer(metainfo.infoHash)
            val session =
                session(
                    metainfo,
                    dialer,
                    FakeTracker(emptyList(), failWith = "forbidden"),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            assertContains(session.state.value.trackerError ?: "", "forbidden")
            assertEquals(0, session.state.value.connectedPeers)
            job.cancelAndJoin()
        }

    @Test
    fun peersFromACommandAreDialledToo() =
        runTest {
            // Peers arrive from magnets, incoming connections and PEX as well as from a tracker; the
            // command channel is the one door for all of them.
            val metainfo = torrent(pieces = 2)
            val dialer = FakeDialer(metainfo.infoHash)
            val session =
                session(
                    metainfo,
                    dialer,
                    FakeTracker(emptyList()),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            assertTrue(dialer.dialled.isEmpty())

            session.send(Command.AddPeers(listOf(peerB)))
            testScheduler.runCurrent()

            assertEquals(listOf(peerB), dialer.dialled)
            assertEquals(1, session.state.value.knownPeers)
            job.cancelAndJoin()
        }

    @Test
    fun aChokeDropsTheOutstandingRequestsRatherThanWaitingForThem() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val dialer = FakeDialer(metainfo.infoHash)
            val session =
                session(
                    metainfo,
                    dialer,
                    FakeTracker(listOf(peerA, peerB)),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            val everything =
                Bitfield(metainfo.pieceCount).also { bits ->
                    (0 until metainfo.pieceCount).forEach { bits.set(it) }
                }
            val first = dialer.connections.getValue(peerA)
            first.incoming.send(PeerEvent.Received(Message.Bitfield(everything.toBytes())))
            first.incoming.send(PeerEvent.Received(Message.Unchoke))
            testScheduler.runCurrent()
            val taken = first.sent.filterIsInstance<Message.Request>().map { it.piece.value }
            assertEquals(2, taken.size)

            first.incoming.send(PeerEvent.Received(Message.Choke))
            testScheduler.runCurrent()

            // The second peer may now be asked for exactly what the first one was choked out of.
            val second = dialer.connections.getValue(peerB)
            second.incoming.send(PeerEvent.Received(Message.Bitfield(everything.toBytes())))
            second.incoming.send(PeerEvent.Received(Message.Unchoke))
            testScheduler.runCurrent()

            val retaken = second.sent.filterIsInstance<Message.Request>().map { it.piece.value }
            assertTrue(
                retaken.containsAll(taken),
                "the blocks peer A was choked out of were not offered to peer B: $taken then $retaken",
            )
            job.cancelAndJoin()
        }
}
