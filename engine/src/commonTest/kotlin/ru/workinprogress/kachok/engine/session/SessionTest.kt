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
import ru.workinprogress.kachok.engine.wire.ExtensionHandshake
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

    /** Enough that four peers together ask for more than a megabyte a second. */
    private val requestsPerPeerPerSecond = 32

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
        reserved: ByteArray = Handshake.reservedBits(),
    ) : PeerConnection {
        val incoming = Channel<PeerEvent>(Channel.UNLIMITED)
        val sent = mutableListOf<Message>()
        var closes = 0

        override val handshake: Handshake =
            Handshake(infoHash, PeerId("-FAKE01-000000000000".encodeToByteArray()), reserved)

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
        /** What the *peer* advertises. BEP 10's bit is the peer's, not ours. */
        private val reserved: ByteArray = Handshake.reservedBits(),
    ) : PeerDialer {
        val connections = LinkedHashMap<PeerAddress, FakeConnection>()
        val dialled = mutableListOf<PeerAddress>()

        override suspend fun connect(address: PeerAddress): PeerConnection {
            dialled += address
            if (address in refuse) throw TrackerException("refused")
            return connections.getOrPut(address) { FakeConnection(address, infoHash, reserved) }
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

    @Test
    fun aPeerThatAdvertisedBep10IsSentTheExtensionHandshakeFirst() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val dialer = FakeDialer(metainfo.infoHash, reserved = Handshake.reservedBits(extensionProtocol = true))
            val session =
                session(
                    metainfo,
                    dialer,
                    FakeTracker(listOf(peerA)),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                    config =
                        SessionConfig(
                            maxStartedPieces = 4,
                            pipelineDepth = 2,
                            maxPeers = 10,
                            extensions = mapOf(ExtensionHandshake.UT_PEX to 1),
                            clientVersion = "kachok test",
                        ),
                )

            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            val first =
                dialer.connections
                    .getValue(peerA)
                    .sent
                    .first()
            val extended = first as Message.Extended
            assertEquals(ExtensionHandshake.HANDSHAKE_ID, extended.extensionId, "BEP 10: the handshake is id 0")
            val ours = ExtensionHandshake.decode(extended.payload)
            assertEquals(1, ours.id(ExtensionHandshake.UT_PEX), "what this client offers")
            assertEquals("kachok test", ours.clientVersion)
            assertEquals(6881, ours.listenPort, "the port we listen on, which is not the one we dialled from")
            assertEquals(2, ours.requestQueueLength, "reqq is the pipeline depth this session actually uses")
            job.cancelAndJoin()
        }

    @Test
    fun aPeerWithoutTheBitNeverSeesAnExtendedMessage() =
        runTest {
            // The default reserved bytes are zero, which is a BEP 3 client. Message id 20 is
            // unknown to one, and an unknown id is a connection most clients close.
            val metainfo = torrent(pieces = 4)
            val dialer = FakeDialer(metainfo.infoHash)
            val session =
                session(metainfo, dialer, FakeTracker(listOf(peerA)), FakeStorage(), AgreeableHasher(metainfo))

            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            val connection = dialer.connections.getValue(peerA)
            connection.incoming.send(PeerEvent.Received(Message.Bitfield(allOf(metainfo.pieceCount))))
            connection.incoming.send(PeerEvent.Received(Message.Unchoke))
            testScheduler.runCurrent()

            assertTrue(
                connection.sent.none { it is Message.Extended },
                "a BEP 3 peer was sent ${connection.sent.filterIsInstance<Message.Extended>().size} extended messages",
            )
            assertEquals(0, session.state.value.extendedPeers)
            job.cancelAndJoin()
        }

    @Test
    fun thePeersExtensionIdsReachTheSessionAndAnUnknownOneIsIgnored() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val dialer = FakeDialer(metainfo.infoHash, reserved = Handshake.reservedBits(extensionProtocol = true))
            val session =
                session(metainfo, dialer, FakeTracker(listOf(peerA)), FakeStorage(), AgreeableHasher(metainfo))

            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            val connection = dialer.connections.getValue(peerA)
            val theirs =
                ExtensionHandshake(mapOf(ExtensionHandshake.UT_PEX to 3), clientVersion = "SomeClient 2.0")
            connection.incoming.send(
                PeerEvent.Received(Message.Extended(ExtensionHandshake.HANDSHAKE_ID, theirs.encode())),
            )
            testScheduler.runCurrent()

            assertEquals(1, session.state.value.extendedPeers, "the handshake was read and the peer counted")

            // An extension this client never offered, sent under an id it never gave out. Dropped
            // in silence: BEP 10 works because both sides ignore what they do not recognise.
            connection.incoming.send(PeerEvent.Received(Message.Extended(3, "anything at all".encodeToByteArray())))
            connection.incoming.send(PeerEvent.Received(Message.Bitfield(allOf(metainfo.pieceCount))))
            connection.incoming.send(PeerEvent.Received(Message.Unchoke))
            testScheduler.runCurrent()

            assertEquals(1, session.state.value.connectedPeers, "the connection survived the unknown extension")
            assertTrue(
                connection.sent.filterIsInstance<Message.Request>().isNotEmpty(),
                "and went on doing its job",
            )
            job.cancelAndJoin()
        }

    @Test
    fun anUnreadableExtensionHandshakeCostsTheExtensionsAndNotTheConnection() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val dialer = FakeDialer(metainfo.infoHash, reserved = Handshake.reservedBits(extensionProtocol = true))
            val session =
                session(metainfo, dialer, FakeTracker(listOf(peerA)), FakeStorage(), AgreeableHasher(metainfo))

            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            val connection = dialer.connections.getValue(peerA)
            connection.incoming.send(
                PeerEvent.Received(
                    Message.Extended(ExtensionHandshake.HANDSHAKE_ID, "not bencode".encodeToByteArray()),
                ),
            )
            connection.incoming.send(PeerEvent.Received(Message.Bitfield(allOf(metainfo.pieceCount))))
            connection.incoming.send(PeerEvent.Received(Message.Unchoke))
            testScheduler.runCurrent()

            assertEquals(0, session.state.value.extendedPeers)
            assertEquals(1, session.state.value.connectedPeers, "everything BEP 3 needs still works")
            assertContains(session.state.value.lastPeerError ?: "", "extension handshake")
            job.cancelAndJoin()
        }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun anUploadLimitIsWhatFourPeersPullingAtOnceShareBetweenThem() =
        runTest {
            // The acceptance criterion of B-22, and the reason the budget is one per session rather
            // than one per peer: four peers each allowed a megabyte is four megabytes on an uplink
            // the user said was worth one.
            val megabyte = 1024L * 1024L
            val metainfo = torrent(pieces = 4)
            val peers = listOf(peerA, peerB, PeerAddress("10.0.0.3", 6881), PeerAddress("10.0.0.4", 6881))
            val dialer = FakeDialer(metainfo.infoHash)
            val session =
                session(
                    metainfo,
                    dialer,
                    FakeTracker(peers),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                    config =
                        SessionConfig(
                            maxStartedPieces = 4,
                            pipelineDepth = 2,
                            maxPeers = 10,
                            maxUnchoked = 4,
                            uploadLimitBytesPerSecond = megabyte,
                        ),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            // One piece to serve, and four interested peers unchoked by the ten-second pass.
            val connections = peers.map { dialer.connections.getValue(it) }
            connections.first().incoming.send(
                PeerEvent.BlockReceived(FakeBlock(PieceIndex(1), 0, PeerWire.BLOCK_SIZE)),
            )
            connections.forEach { it.incoming.send(PeerEvent.Received(Message.Interested)) }
            testScheduler.advanceTimeBy(11_000)
            testScheduler.runCurrent()
            assertTrue(
                connections.all { peer -> peer.sent.any { it === Message.Unchoke } },
                "all four have to be unchoked or the limit is not what is being measured",
            )

            // Each peer asks for far more than the limit can pay for, every second.
            suspend fun everyoneAsks() {
                connections.forEach { peer ->
                    repeat(requestsPerPeerPerSecond) {
                        peer.incoming.send(PeerEvent.Received(Message.Request(PieceIndex(1), 0, PeerWire.BLOCK_SIZE)))
                    }
                }
            }

            // One second first, and not counted: a token bucket starts full, so the opening second
            // is a burst by design and measuring from it would measure the burst.
            everyoneAsks()
            testScheduler.advanceTimeBy(1_000)
            testScheduler.runCurrent()

            fun served(): Long = connections.sumOf { peer -> peer.servedBlocks.sumOf { it.third }.toLong() }
            val before = served()
            repeat(10) {
                everyoneAsks()
                testScheduler.advanceTimeBy(1_000)
                testScheduler.runCurrent()
            }
            val inTenSeconds = served() - before

            assertTrue(
                inTenSeconds in (9 * megabyte)..(11 * megabyte),
                "ten seconds at a megabyte a second served $inTenSeconds bytes, not 10 MiB ± 10 %",
            )
            assertTrue(
                connections.all { it.servedBlocks.isNotEmpty() },
                "one peer took the whole budget: ${connections.map { it.servedBlocks.size }}",
            )
            job.cancelAndJoin()
        }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun aDownloadLimitIsAppliedByNotAskingRatherThanByReadingSlowly() =
        runTest {
            // Reading slowly does not stop a peer sending; not requesting does. So the limit has to
            // be visible in the requests that go out, which is what this counts.
            val metainfo = torrent(pieces = 512)
            val dialer = FakeDialer(metainfo.infoHash)
            val blocksPerSecond = 64
            val session =
                session(
                    metainfo,
                    dialer,
                    FakeTracker(listOf(peerA)),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                    config =
                        SessionConfig(
                            maxStartedPieces = 400,
                            pipelineDepth = 400,
                            maxPeers = 10,
                            downloadLimitBytesPerSecond = blocksPerSecond.toLong() * PeerWire.BLOCK_SIZE,
                        ),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            val connection = dialer.connections.getValue(peerA)
            connection.incoming.send(PeerEvent.Received(Message.Bitfield(allOf(metainfo.pieceCount))))
            connection.incoming.send(PeerEvent.Received(Message.Unchoke))
            testScheduler.runCurrent()

            fun requests() = connection.sent.filterIsInstance<Message.Request>().size
            assertEquals(
                blocksPerSecond,
                requests(),
                "the opening burst is one second's worth, not the whole pipeline of 400",
            )

            testScheduler.advanceTimeBy(1_000)
            testScheduler.runCurrent()
            assertEquals(blocksPerSecond * 2, requests(), "the tick's refill buys exactly one more second")

            testScheduler.advanceTimeBy(3_000)
            testScheduler.runCurrent()
            assertEquals(blocksPerSecond * 5, requests(), "and keeps buying one second at a time")
            job.cancelAndJoin()
        }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun withNoLimitAPeerIsAnsweredAsItAsksAndNothingWaits() =
        runTest {
            // The default, and the path every other test in this file runs: a request is served
            // when it arrives, without waiting for a tick.
            val metainfo = torrent(pieces = 4)
            val dialer = FakeDialer(metainfo.infoHash)
            val session =
                session(metainfo, dialer, FakeTracker(listOf(peerA)), FakeStorage(), AgreeableHasher(metainfo))
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            val connection = dialer.connections.getValue(peerA)
            connection.incoming.send(PeerEvent.BlockReceived(FakeBlock(PieceIndex(1), 0, PeerWire.BLOCK_SIZE)))
            connection.incoming.send(PeerEvent.Received(Message.Interested))
            testScheduler.advanceTimeBy(11_000)
            testScheduler.runCurrent()

            val before = connection.servedBlocks.size
            repeat(50) {
                connection.incoming.send(PeerEvent.Received(Message.Request(PieceIndex(1), 0, PeerWire.BLOCK_SIZE)))
            }
            testScheduler.runCurrent()

            assertEquals(
                before + 50,
                connection.servedBlocks.size,
                "an unlimited session queues nothing and answers every request as it arrives",
            )
            job.cancelAndJoin()
        }

    /** Both sides advertising BEP 6, which is the only way its messages are legal. */
    private fun fastConfig(
        started: Int = 4,
        pipeline: Int = 2,
        uploadLimit: Long = 0,
    ) = SessionConfig(
        maxStartedPieces = started,
        pipelineDepth = pipeline,
        maxPeers = 10,
        maxUnchoked = 1,
        uploadLimitBytesPerSecond = uploadLimit,
        reserved = Handshake.reservedBits(fastExtension = true),
    )

    private fun fastDialer(metainfo: Metainfo) =
        FakeDialer(metainfo.infoHash, reserved = Handshake.reservedBits(fastExtension = true))

    @Test
    fun aSeedOpensWithHaveAllAndAnEmptyClientWithHaveNone() =
        runTest {
            // BEP 6: on a fast connection the first message is one of bitfield / have all / have
            // none, and never nothing — which is what a BEP 3 client with no pieces sends.
            val metainfo = torrent(pieces = 1)
            val empty = fastDialer(metainfo)
            val session =
                session(
                    metainfo,
                    empty,
                    FakeTracker(listOf(peerA)),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                    fastConfig(),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            assertTrue(
                empty.connections
                    .getValue(peerA)
                    .sent
                    .first() === Message.HaveNone,
                "a client with nothing must still say so: ${empty.connections.getValue(peerA).sent}",
            )
            // And the other end of it: a session that has the whole torrent by the time a peer
            // arrives. It comes in through the accepting door, which is the only way to reach
            // `serve` after the opening announce.
            val connection = empty.connections.getValue(peerA)
            connection.incoming.send(PeerEvent.BlockReceived(FakeBlock(PieceIndex(0), 0, PeerWire.BLOCK_SIZE)))
            testScheduler.runCurrent()
            assertTrue(session.state.value.isComplete, "the fixture did not complete, so nothing has everything")

            val arriving = FakeConnection(peerB, metainfo.infoHash, Handshake.reservedBits(fastExtension = true))
            session.send(Command.AcceptPeer(arriving))
            testScheduler.runCurrent()

            assertTrue(
                arriving.sent.first() === Message.HaveAll,
                "a seed sends one byte, not a bitfield of ones: ${arriving.sent}",
            )
            job.cancelAndJoin()
        }

    @Test
    fun haveAllFromAPeerMakesEveryPieceAskable() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val dialer = fastDialer(metainfo)
            val session =
                session(
                    metainfo,
                    dialer,
                    FakeTracker(listOf(peerA)),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                    fastConfig(),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            val connection = dialer.connections.getValue(peerA)
            connection.incoming.send(PeerEvent.Received(Message.HaveAll))
            connection.incoming.send(PeerEvent.Received(Message.Unchoke))
            testScheduler.runCurrent()

            assertTrue(connection.sent.any { it is Message.Interested }, "a peer that has everything is interesting")
            assertTrue(
                connection.sent.filterIsInstance<Message.Request>().isNotEmpty(),
                "have all was read as a bitfield of ones or nothing was asked for",
            )
            job.cancelAndJoin()
        }

    @Test
    fun aRejectFreesItsBlockAtOnceRatherThanInThirtySeconds() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val dialer = fastDialer(metainfo)
            val session =
                session(
                    metainfo,
                    dialer,
                    FakeTracker(listOf(peerA)),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                    fastConfig(),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            val connection = dialer.connections.getValue(peerA)
            connection.incoming.send(PeerEvent.Received(Message.HaveAll))
            connection.incoming.send(PeerEvent.Received(Message.Unchoke))
            testScheduler.runCurrent()
            val asked = connection.sent.filterIsInstance<Message.Request>()
            assertTrue(asked.isNotEmpty())

            val refused = asked.first()
            connection.incoming.send(
                PeerEvent.Received(Message.Reject(refused.piece, refused.begin, refused.length)),
            )
            testScheduler.runCurrent()

            // The block must be asked for again without the request timeout expiring first. The
            // clock has not moved, so an expiry-based recovery could not have produced this.
            val askedAgain =
                connection.sent
                    .filterIsInstance<Message.Request>()
                    .drop(asked.size)
                    .any { it.piece.value == refused.piece.value && it.begin == refused.begin }
            assertTrue(askedAgain, "the rejected block was not offered to anyone again: ${connection.sent}")
            job.cancelAndJoin()
        }

    @Test
    fun aRequestThisClientWillNotAnswerIsRejectedRatherThanDropped() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val dialer = fastDialer(metainfo)
            val session =
                session(
                    metainfo,
                    dialer,
                    FakeTracker(listOf(peerA)),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                    fastConfig(),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            val connection = dialer.connections.getValue(peerA)
            // Choked, and for a piece this client does not have either.
            connection.incoming.send(PeerEvent.Received(Message.Request(PieceIndex(2), 0, PeerWire.BLOCK_SIZE)))
            testScheduler.runCurrent()

            val rejected = connection.sent.filterIsInstance<Message.Reject>()
            assertEquals(1, rejected.size, "sent: ${connection.sent}")
            assertEquals(2, rejected.first().piece.value)
            assertEquals(PeerWire.BLOCK_SIZE, rejected.first().length)
            assertTrue(connection.servedBlocks.isEmpty())
            job.cancelAndJoin()
        }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun aChokeRejectsEveryRequestItLeavesUnanswered() =
        runTest {
            // The requests genuinely outstanding are the ones the upload limit made wait. A limit
            // below one block — a kibibyte a second, which a user may well set — means none of
            // them can ever be paid for, so all five are still waiting when the slot is taken away.
            val metainfo = torrent(pieces = 4)
            val dialer = fastDialer(metainfo)
            val session =
                session(
                    metainfo,
                    dialer,
                    FakeTracker(listOf(peerA, peerB)),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                    fastConfig(uploadLimit = 1024).let {
                        SessionConfig(
                            maxStartedPieces = 4,
                            pipelineDepth = 2,
                            maxPeers = 10,
                            maxUnchoked = 1,
                            // Rotating every pass, so the second pass is the one that takes the
                            // slot back rather than the fourth.
                            optimisticInterval = 10.seconds,
                            uploadLimitBytesPerSecond = 1024,
                            reserved = Handshake.reservedBits(fastExtension = true),
                        )
                    },
                    // First pick peers[0], then peers[1]: BEP 3's optimistic slot is chosen at
                    // random, and a test about losing it has to be able to say who loses it.
                    random = alternating(),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            val first = dialer.connections.getValue(peerA)
            val second = dialer.connections.getValue(peerB)
            first.incoming.send(PeerEvent.BlockReceived(FakeBlock(PieceIndex(1), 0, PeerWire.BLOCK_SIZE)))
            first.incoming.send(PeerEvent.Received(Message.Interested))
            second.incoming.send(PeerEvent.Received(Message.Interested))
            testScheduler.advanceTimeBy(11_000)
            testScheduler.runCurrent()
            assertTrue(first.sent.any { it === Message.Unchoke }, "the first peer never got the slot")

            repeat(5) {
                first.incoming.send(PeerEvent.Received(Message.Request(PieceIndex(1), 0, PeerWire.BLOCK_SIZE)))
            }
            testScheduler.runCurrent()
            assertTrue(first.servedBlocks.isEmpty(), "a kibibyte a second cannot pay for a 16 KiB block")

            // The next pass rotates the slot to the other peer, and every waiting request is
            // answered rather than dropped.
            testScheduler.advanceTimeBy(11_000)
            testScheduler.runCurrent()

            assertTrue(first.sent.any { it === Message.Choke }, "the peer was never choked: ${first.sent}")
            assertEquals(
                5,
                first.sent.filterIsInstance<Message.Reject>().size,
                "one reject per request left unanswered, and no more",
            )
            job.cancelAndJoin()
        }

    @Test
    fun anAllowedFastPieceIsAskedForWhileThePeerIsStillChoking() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val dialer = fastDialer(metainfo)
            val session =
                session(
                    metainfo,
                    dialer,
                    FakeTracker(listOf(peerA)),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                    fastConfig(),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            val connection = dialer.connections.getValue(peerA)
            connection.incoming.send(PeerEvent.Received(Message.HaveAll))
            testScheduler.runCurrent()
            assertTrue(
                connection.sent.filterIsInstance<Message.Request>().isEmpty(),
                "a choking peer is asked for nothing until it names a piece it will serve anyway",
            )

            connection.incoming.send(PeerEvent.Received(Message.AllowedFast(PieceIndex(3))))
            testScheduler.runCurrent()

            val asked = connection.sent.filterIsInstance<Message.Request>()
            assertTrue(asked.isNotEmpty(), "allowed fast was recorded and never used")
            assertTrue(asked.all { it.piece.value == 3 }, "only the named piece may be asked for: $asked")
            job.cancelAndJoin()
        }

    @Test
    fun aSuggestionIsReadAsAHaveBecauseThatIsWhatItImplies() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val dialer = fastDialer(metainfo)
            val session =
                session(
                    metainfo,
                    dialer,
                    FakeTracker(listOf(peerA)),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                    fastConfig(),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            val connection = dialer.connections.getValue(peerA)
            connection.incoming.send(PeerEvent.Received(Message.HaveNone))
            connection.incoming.send(PeerEvent.Received(Message.Unchoke))
            testScheduler.runCurrent()
            assertTrue(connection.sent.none { it is Message.Interested }, "a peer with nothing is not interesting")

            connection.incoming.send(PeerEvent.Received(Message.Suggest(PieceIndex(2))))
            testScheduler.runCurrent()

            assertTrue(
                connection.sent.any { it is Message.Interested },
                "a suggestion means the peer has that piece, so it became interesting",
            )
            job.cancelAndJoin()
        }

    @Test
    fun aPeerWithoutTheBitIsNeverSentAFastMessage() =
        runTest {
            // Both sides must advertise. This session does; the peer does not.
            val metainfo = torrent(pieces = 4)
            val dialer = FakeDialer(metainfo.infoHash)
            val session =
                session(
                    metainfo,
                    dialer,
                    FakeTracker(listOf(peerA)),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                    fastConfig(),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            val connection = dialer.connections.getValue(peerA)
            connection.incoming.send(PeerEvent.Received(Message.Request(PieceIndex(2), 0, PeerWire.BLOCK_SIZE)))
            testScheduler.runCurrent()

            assertTrue(
                connection.sent.none {
                    it is Message.Reject || it === Message.HaveAll || it === Message.HaveNone
                },
                "a BEP 3 peer was sent a BEP 6 message: ${connection.sent}",
            )
            job.cancelAndJoin()
        }

    /** 0, 1, 0, 1 …: the optimistic slot moves to the next peer on every rotation. */
    private fun alternating(): kotlin.random.Random =
        object : kotlin.random.Random() {
            private var next = 0
            private val bits = kotlin.random.Random(1)

            override fun nextBits(bitCount: Int): Int = bits.nextBits(bitCount)

            override fun nextInt(until: Int): Int {
                val value = next % until
                next++
                return value
            }
        }

    /** A bitfield claiming every piece, with BEP 3's spare bits left at zero. */
    private fun allOf(pieces: Int): ByteArray =
        Bitfield(pieces).also { bits -> (0 until pieces).forEach { bits.set(it) } }.toBytes()

    private fun session(
        metainfo: Metainfo,
        dialer: PeerDialer,
        tracker: TrackerClient,
        storage: Storage,
        hasher: PieceHasher,
        config: SessionConfig = SessionConfig(maxStartedPieces = 4, pipelineDepth = 2, maxPeers = 10),
        random: kotlin.random.Random = kotlin.random.Random(1),
    ) = Session(
        metainfo = metainfo,
        peerId = ourPeerId,
        listenPort = 6881,
        dialer = dialer,
        trackerClient = tracker,
        hasher = hasher,
        storage = storage,
        config = config,
        random = random,
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
