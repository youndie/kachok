package io.github.youndie.kachok.engine.session

import io.github.youndie.kachok.engine.PeerId
import io.github.youndie.kachok.engine.PieceIndex
import io.github.youndie.kachok.engine.bencode.BDictionary
import io.github.youndie.kachok.engine.bencode.BInteger
import io.github.youndie.kachok.engine.bencode.BString
import io.github.youndie.kachok.engine.bencode.Bencode
import io.github.youndie.kachok.engine.metainfo.Metainfo
import io.github.youndie.kachok.engine.metainfo.MetainfoParser
import io.github.youndie.kachok.engine.peer.Block
import io.github.youndie.kachok.engine.peer.PeerAddress
import io.github.youndie.kachok.engine.peer.PeerConnection
import io.github.youndie.kachok.engine.peer.PeerDialer
import io.github.youndie.kachok.engine.peer.PeerEvent
import io.github.youndie.kachok.engine.picker.Bitfield
import io.github.youndie.kachok.engine.storage.PieceHasher
import io.github.youndie.kachok.engine.storage.Storage
import io.github.youndie.kachok.engine.tracker.AnnounceEvent
import io.github.youndie.kachok.engine.tracker.AnnounceRequest
import io.github.youndie.kachok.engine.tracker.AnnounceResponse
import io.github.youndie.kachok.engine.tracker.TrackerClient
import io.github.youndie.kachok.engine.tracker.TrackerException
import io.github.youndie.kachok.engine.wire.ExtensionHandshake
import io.github.youndie.kachok.engine.wire.Handshake
import io.github.youndie.kachok.engine.wire.Message
import io.github.youndie.kachok.engine.wire.MetadataMessage
import io.github.youndie.kachok.engine.wire.PeerWire
import io.github.youndie.kachok.engine.wire.PexMessage
import io.github.youndie.kachok.engine.wire.WireException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    private val peerC = PeerAddress("10.0.0.3", 6881)
    private val ourPeerId = PeerId("-KA0001-0123456789AB".encodeToByteArray())

    /** The messages this client sent under one peer's `ut_pex` id, decoded. */
    private fun pexOf(
        connection: FakeConnection,
        id: Int,
    ): List<PexMessage> =
        connection.sent
            .filterIsInstance<Message.Extended>()
            .filter { it.extensionId == id }
            .map { PexMessage.decode(it.payload) }

    private fun privateTorrent(pieces: Int): Metainfo = torrent(pieces, private = true)

    /** Two files of `pieces / 2` blocks each, so one of them can be skipped. */
    private fun twoFileTorrent(pieces: Int): Metainfo {
        val half = pieces / 2 * PeerWire.BLOCK_SIZE.toLong()
        val info =
            BDictionary(
                mapOf(
                    BString("files") to
                        io.github.youndie.kachok.engine.bencode.BList(
                            listOf(
                                BDictionary(
                                    mapOf(
                                        BString("length") to BInteger(half),
                                        BString("path") to
                                            io.github.youndie.kachok.engine.bencode.BList(
                                                listOf(BString("wanted.bin")),
                                            ),
                                    ),
                                ),
                                BDictionary(
                                    mapOf(
                                        BString("length") to BInteger(half),
                                        BString("path") to
                                            io.github.youndie.kachok.engine.bencode.BList(
                                                listOf(BString("skipped.bin")),
                                            ),
                                    ),
                                ),
                            ),
                        ),
                    BString("name") to BString("pair"),
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
        return MetainfoParser.parse(
            io.github.youndie.kachok.engine.bencode.Bencode
                .encode(root),
        )
    }

    /** [pieces] pieces of exactly one block each. */
    private fun torrent(
        pieces: Int,
        private: Boolean = false,
    ): Metainfo {
        val fields =
            mutableMapOf<BString, io.github.youndie.kachok.engine.bencode.BValue>(
                BString("length") to BInteger(pieces.toLong() * PeerWire.BLOCK_SIZE),
                BString("name") to BString("fixture"),
                BString("piece length") to BInteger(PeerWire.BLOCK_SIZE.toLong()),
                BString("pieces") to BString(ByteArray(pieces * Metainfo.HASH_SIZE) { it.toByte() }),
            )
        if (private) fields[BString("private")] = BInteger(1)
        val info = BDictionary(fields)
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
        infoHash: io.github.youndie.kachok.engine.InfoHash,
        reserved: ByteArray = Handshake.reservedBits(),
        /**
         * Distinct per address by default, because since B-111 a session drops a second connection
         * carrying a peer id it already holds — and every fake used to carry the same one.
         */
        peerId: PeerId = fakeIdFor(address),
    ) : PeerConnection {
        val incoming = Channel<PeerEvent>(Channel.UNLIMITED)
        val sent = mutableListOf<Message>()
        var closes = 0

        override val handshake: Handshake = Handshake(infoHash, peerId, reserved)

        override val events: ReceiveChannel<PeerEvent> get() = incoming

        /** Set to make every send fail the way a connection to a peer that stopped reading does. */
        var sendFailsWith: Exception? = null

        override suspend fun send(message: Message) {
            sendFailsWith?.let {
                close()
                throw it
            }
            sent += message
        }

        /** Blocks this peer was served, and how many bytes of them. */
        val servedBlocks = mutableListOf<Triple<Int, Int, Int>>()

        override var uploaded: Long = 0L
            private set

        override suspend fun sendBlock(
            piece: io.github.youndie.kachok.engine.PieceIndex,
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
        private val infoHash: io.github.youndie.kachok.engine.InfoHash,
        private val refuse: Set<PeerAddress> = emptySet(),
        /** What the *peer* advertises. BEP 10's bit is the peer's, not ours. */
        private val reserved: ByteArray = Handshake.reservedBits(),
        /** Which id the peer at an address offers; the default is distinct per address. */
        private val peerIdFor: (PeerAddress) -> PeerId = ::fakeIdFor,
    ) : PeerDialer {
        val connections = LinkedHashMap<PeerAddress, FakeConnection>()
        val dialled = mutableListOf<PeerAddress>()

        override suspend fun connect(address: PeerAddress): PeerConnection {
            dialled += address
            if (address in refuse) throw TrackerException("refused")
            return connections.getOrPut(address) { FakeConnection(address, infoHash, reserved, peerIdFor(address)) }
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

    /**
     * A disk whose flush can be made to fail and then to work again.
     *
     * The periodic jobs are the ones whose failure is transient by construction — the loop that
     * runs them survives and runs them again — and `flush` is the one a test can turn on and off
     * from outside without inventing a network.
     */
    private class FailingFlushStorage : Storage {
        var failFlush = false

        override suspend fun write(
            piece: PieceIndex,
            blocks: List<Block>,
        ) = Unit

        override suspend fun readPiece(piece: PieceIndex): List<Block>? = null

        override suspend fun flush() {
            if (failFlush) throw IllegalStateException("the disk went away")
        }
    }

    /** Returns the torrent's own hash for the piece, so every piece verifies. */
    private class AgreeableHasher(
        private val metainfo: Metainfo,
    ) : PieceHasher {
        /** Counted, so a test can assert that a resume did *not* re-verify. */
        var hashed: Int = 0
            private set

        override suspend fun hash(blocks: List<Block>): ByteArray {
            hashed++
            return metainfo.pieceHash(blocks.first().piece)
        }
    }

    /** Agrees until [turn], and lies afterwards: a byte flipped on the disk, from inside. */
    private class TurncoatHasher(
        private val metainfo: Metainfo,
    ) : PieceHasher {
        private var honest = true

        fun turn() {
            honest = false
        }

        override suspend fun hash(blocks: List<Block>): ByteArray =
            if (honest) {
                metainfo.pieceHash(blocks.first().piece)
            } else {
                ByteArray(Metainfo.HASH_SIZE) { 0xFF.toByte() }
            }
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

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun twoPeersLearnOfEachOtherWithinAMinute() =
        runTest {
            // The acceptance criterion of B-34. Both speak BEP 10 and both offer ut_pex, under
            // different ids on purpose: the ids are the receiver's and are not symmetric.
            val metainfo = torrent(pieces = 4)
            val dialer = FakeDialer(metainfo.infoHash, reserved = Handshake.reservedBits(extensionProtocol = true))
            val session =
                session(metainfo, dialer, FakeTracker(listOf(peerA, peerB)), FakeStorage(), AgreeableHasher(metainfo))
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            val first = dialer.connections.getValue(peerA)
            val second = dialer.connections.getValue(peerB)
            first.incoming.send(
                PeerEvent.Received(
                    Message.Extended(0, ExtensionHandshake(mapOf(ExtensionHandshake.UT_PEX to 3)).encode()),
                ),
            )
            second.incoming.send(
                PeerEvent.Received(
                    Message.Extended(0, ExtensionHandshake(mapOf(ExtensionHandshake.UT_PEX to 7)).encode()),
                ),
            )
            testScheduler.runCurrent()

            assertTrue(pexOf(first, 3).isEmpty(), "nothing is exchanged before the interval is up")

            testScheduler.advanceTimeBy(61_000)
            testScheduler.runCurrent()

            val toFirst = pexOf(first, 3).single()
            val toSecond = pexOf(second, 7).single()
            assertEquals(
                listOf(peerB.host to peerB.port),
                toFirst.added.map { it.host to it.port },
                "the first peer was not told about the second",
            )
            assertEquals(listOf(peerA.host to peerA.port), toSecond.added.map { it.host to it.port })
            assertTrue(toFirst.dropped.isEmpty() && toSecond.dropped.isEmpty())
            job.cancelAndJoin()
        }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun theSecondMessageIsADeltaAndNotTheSwarmAgain() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val dialer = FakeDialer(metainfo.infoHash, reserved = Handshake.reservedBits(extensionProtocol = true))
            val session =
                session(metainfo, dialer, FakeTracker(listOf(peerA, peerB)), FakeStorage(), AgreeableHasher(metainfo))
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            val first = dialer.connections.getValue(peerA)
            listOf(first to 3, dialer.connections.getValue(peerB) to 7).forEach { (connection, id) ->
                connection.incoming.send(
                    PeerEvent.Received(
                        Message.Extended(0, ExtensionHandshake(mapOf(ExtensionHandshake.UT_PEX to id)).encode()),
                    ),
                )
            }
            testScheduler.advanceTimeBy(61_000)
            testScheduler.runCurrent()
            assertEquals(1, pexOf(first, 3).size)

            // Nothing changed in the swarm, so there is nothing to say. A message repeating the
            // same peer every minute would still be well formed and still parse.
            testScheduler.advanceTimeBy(61_000)
            testScheduler.runCurrent()

            assertEquals(1, pexOf(first, 3).size, "an unchanged swarm produced a second message")
            job.cancelAndJoin()
        }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun aPrivateTorrentNeverOffersPeerExchangeAtAll() =
        runTest {
            // BEP 27: not "offered and never sent". A peer that sees ut_pex in the handshake asks.
            val metainfo = privateTorrent(pieces = 4)
            val dialer = FakeDialer(metainfo.infoHash, reserved = Handshake.reservedBits(extensionProtocol = true))
            val session =
                session(metainfo, dialer, FakeTracker(listOf(peerA, peerB)), FakeStorage(), AgreeableHasher(metainfo))
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            val first = dialer.connections.getValue(peerA)
            val offered =
                ExtensionHandshake.decode((first.sent.first { it is Message.Extended } as Message.Extended).payload)
            assertTrue(
                !offered.supports(ExtensionHandshake.UT_PEX),
                "a private torrent offered ut_pex: ${offered.extensions}",
            )

            first.incoming.send(
                PeerEvent.Received(
                    Message.Extended(0, ExtensionHandshake(mapOf(ExtensionHandshake.UT_PEX to 3)).encode()),
                ),
            )
            testScheduler.advanceTimeBy(61_000)
            testScheduler.runCurrent()

            assertTrue(pexOf(first, 3).isEmpty(), "a private torrent sent ut_pex anyway")
            job.cancelAndJoin()
        }

    @Test
    fun peersFromAPexMessageAreDialledLikeATrackersAre() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val dialer = FakeDialer(metainfo.infoHash, reserved = Handshake.reservedBits(extensionProtocol = true))
            val session =
                session(metainfo, dialer, FakeTracker(listOf(peerA)), FakeStorage(), AgreeableHasher(metainfo))
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            assertEquals(listOf(peerA), dialer.dialled)

            val stranger = PeerAddress("10.9.9.9", 6881)
            val first = dialer.connections.getValue(peerA)
            first.incoming.send(
                PeerEvent.Received(
                    Message.Extended(0, ExtensionHandshake(mapOf(ExtensionHandshake.UT_PEX to 3)).encode()),
                ),
            )
            // Under the id *this* client published, which is the only one a peer may use with it.
            first.incoming.send(
                PeerEvent.Received(Message.Extended(1, PexMessage(added = listOf(stranger)).encode())),
            )
            testScheduler.runCurrent()

            assertContains(dialer.dialled, stranger, "a peer named over ut_pex was never dialled")
            assertEquals(2, session.state.value.knownPeers)
            job.cancelAndJoin()
        }

    @Test
    fun aPeerAsksForTheMetadataAndGetsBytesThatHashToTheInfoHash() =
        runTest {
            // The acceptance criterion of B-45. The fixture's metadata is more than one block, so a
            // client that answered block 0 and stopped would fail here rather than pass.
            val metainfo = torrent(pieces = 1000)
            val dialer = FakeDialer(metainfo.infoHash, reserved = Handshake.reservedBits(extensionProtocol = true))
            val session =
                session(metainfo, dialer, FakeTracker(listOf(peerA)), FakeStorage(), AgreeableHasher(metainfo))
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            val connection = dialer.connections.getValue(peerA)
            val offered =
                ExtensionHandshake.decode(
                    (connection.sent.first { it is Message.Extended } as Message.Extended).payload,
                )
            val id = offered.id(ExtensionHandshake.UT_METADATA)
            assertTrue(id != null, "the client offered no ut_metadata: ${offered.extensions}")
            val size = offered.metadataSize
            assertTrue(size != null && size > MetadataMessage.BLOCK_SIZE, "metadata_size was $size")

            val blocks = MetadataMessage.blockCount(size)
            (0 until blocks).forEach { piece ->
                connection.incoming.send(
                    PeerEvent.Received(Message.Extended(id, MetadataMessage.request(piece).encode())),
                )
            }
            testScheduler.runCurrent()

            val answered =
                connection.sent
                    .filterIsInstance<Message.Extended>()
                    .filter { it.extensionId == id }
                    .map { MetadataMessage.decode(it.payload) }
                    .filter { it.type == MetadataMessage.DATA }
            assertEquals(blocks, answered.size, "one data message per block")
            val assembled = ByteArray(size)
            answered.forEach { it.data.copyInto(assembled, it.piece * MetadataMessage.BLOCK_SIZE) }
            assertTrue(
                io.github.youndie.kachok.engine.platform
                    .sha1(assembled, 0, assembled.size)
                    .contentEquals(metainfo.infoHash.bytes),
                "the bytes served do not hash to this client's own info hash",
            )
            job.cancelAndJoin()
        }

    @Test
    fun aRequestPastTheEndOfTheMetadataIsRejected() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val dialer = FakeDialer(metainfo.infoHash, reserved = Handshake.reservedBits(extensionProtocol = true))
            val session =
                session(metainfo, dialer, FakeTracker(listOf(peerA)), FakeStorage(), AgreeableHasher(metainfo))
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            val connection = dialer.connections.getValue(peerA)
            val id = ExtensionHandshake.ID_UT_METADATA
            listOf(99, -1).forEach { piece ->
                connection.incoming.send(
                    PeerEvent.Received(Message.Extended(id, MetadataMessage.request(piece).encode())),
                )
            }
            testScheduler.runCurrent()

            val replies =
                connection.sent
                    .filterIsInstance<Message.Extended>()
                    .filter { it.extensionId == id }
                    .map { MetadataMessage.decode(it.payload) }
            assertEquals(2, replies.size, "a request that cannot be answered is answered anyway")
            assertTrue(replies.all { it.type == MetadataMessage.REJECT }, "both should be rejects: $replies")
            job.cancelAndJoin()
        }

    @Test
    fun theMetadataGoesThroughTheUploadBudgetLikeAnythingElse() =
        runTest {
            // Otherwise a peer asks for the same block a thousand times and walks around the limit.
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
                            uploadLimitBytesPerSecond = 1,
                        ),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            val connection = dialer.connections.getValue(peerA)
            val id = ExtensionHandshake.ID_UT_METADATA
            connection.incoming.send(
                PeerEvent.Received(Message.Extended(id, MetadataMessage.request(0).encode())),
            )
            testScheduler.runCurrent()

            val reply =
                MetadataMessage.decode(
                    (connection.sent.last { it is Message.Extended && it.extensionId == id } as Message.Extended)
                        .payload,
                )
            assertEquals(MetadataMessage.REJECT, reply.type, "a byte a second cannot pay for a block")
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
        unwantedFiles: Set<Int> = emptySet(),
        highFiles: Set<Int> = emptySet(),
        sequential: Boolean = false,
        timeSource: kotlin.time.TimeSource = kotlin.time.TimeSource.Monotonic,
        dht: io.github.youndie.kachok.engine.dht.Dht? = null,
    ) = Session(
        metainfo = metainfo,
        peerId = ourPeerId,
        timeSource = timeSource,
        dht = dht,
        listenPort = 6881,
        dialer = dialer,
        trackerClient = tracker,
        hasher = hasher,
        storage = storage,
        config = config,
        random = random,
        unwantedFiles = unwantedFiles,
        highFiles = highFiles,
        sequential = sequential,
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

    private class FakeResumeStore : io.github.youndie.kachok.engine.resume.ResumeStore {
        val saved = mutableListOf<io.github.youndie.kachok.engine.resume.ResumeRecord>()

        override suspend fun load(): io.github.youndie.kachok.engine.resume.ResumeRecord? = null

        override suspend fun save(record: io.github.youndie.kachok.engine.resume.ResumeRecord) {
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

    /**
     * A resume store that says how many times the disk had been forced when it was written.
     *
     * The record's whole claim is "these pieces are on the disk", and the only way to assert a
     * claim about *order* is to read the other side's counter at the moment the call arrives.
     */
    private class OrderedResumeStore(
        private val flushes: () -> Int,
    ) : io.github.youndie.kachok.engine.resume.ResumeStore {
        val flushesWhenSaved = mutableListOf<Int>()

        override suspend fun load(): io.github.youndie.kachok.engine.resume.ResumeRecord? = null

        override suspend fun save(record: io.github.youndie.kachok.engine.resume.ResumeRecord) {
            flushesWhenSaved += flushes()
        }
    }

    /**
     * The periodic record is written after a flush, like the other three.
     *
     * `shutDown`, `pause` and `recheck` flush and then save, and say in as many words why: the
     * record vouches for pieces that are on the disk. The save on the timer did not, so a record
     * could vouch for a piece whose bytes were still in the page cache — and after a host that went
     * down, the Files tab would call a file complete that was not
     * ([B-119](../../../../../../../../docs/backlog/B-119-a-file-the-files-tab-calls-complete-is-not.md)).
     *
     * The flush interval is a minute against the record's five seconds, so the flush this counts
     * cannot be the flush timer's: it is the one the save did itself.
     */
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun theRecordOnTheTimerIsWrittenAfterTheDiskIsForced() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val dialer = FakeDialer(metainfo.infoHash)
            val storage = FakeStorage()
            val store = OrderedResumeStore { storage.flushes }
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
                            flushInterval = 60.seconds,
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

            assertEquals(1, store.flushesWhenSaved.size, "one record, on the interval")
            assertTrue(
                store.flushesWhenSaved.first() >= 1,
                "the record was written before the disk was forced: it vouches for bytes that may not be there",
            )
            job.cancelAndJoin()
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

    /**
     * A pause is the stop without the leaving.
     *
     * The three things it has in common with a stop are asserted here — `stopped` on the tracker,
     * every peer closed, the data flushed — and so is the one thing that separates them: the job
     * is still running afterwards, which is what makes the resume cost an announce instead of a
     * re-verify.
     */
    @Test
    fun pausingGivesUpThePeersAndKeepsTheSession() =
        runTest {
            val metainfo = torrent(pieces = 2)
            val dialer = FakeDialer(metainfo.infoHash)
            val tracker = FakeTracker(listOf(peerA, peerB))
            val storage = FakeStorage()
            val session = session(metainfo, dialer, tracker, storage, AgreeableHasher(metainfo))

            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            assertEquals(2, session.state.value.connectedPeers)

            session.send(Command.Pause)
            testScheduler.runCurrent()

            assertTrue(session.state.value.paused, "the state does not say so")
            assertEquals(0, session.state.value.connectedPeers, "a paused session claims no peers")
            assertEquals(
                listOf<AnnounceEvent?>(AnnounceEvent.STARTED, AnnounceEvent.STOPPED),
                tracker.events,
                "the tracker was not told",
            )
            dialer.connections.values.forEach { peer ->
                assertTrue(peer.closes >= 1, "${peer.address} was never closed")
            }
            assertTrue(storage.flushes >= 1, "a pause makes the data durable, like a stop")
            assertTrue(job.isActive, "a paused session that ended its job is a stopped one")

            job.cancelAndJoin()
        }

    /**
     * And nothing is re-verified on the way back.
     *
     * The hasher is the oracle: a resume that rebuilt the session would run the start-up pass
     * again, and the count would go up. It does not.
     */
    @Test
    fun resumingAnnouncesAgainAndVerifiesNothing() =
        runTest {
            val metainfo = torrent(pieces = 2)
            val dialer = FakeDialer(metainfo.infoHash)
            val tracker = FakeTracker(listOf(peerA, peerB))
            val hasher = AgreeableHasher(metainfo)
            val session = session(metainfo, dialer, tracker, FakeStorage(), hasher)

            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            session.send(Command.Pause)
            testScheduler.runCurrent()
            val hashedWhilePaused = hasher.hashed
            val dialledWhilePaused = dialer.dialled.size

            session.send(Command.Resume)
            testScheduler.runCurrent()

            assertEquals(false, session.state.value.paused)
            assertEquals(
                listOf<AnnounceEvent?>(AnnounceEvent.STARTED, AnnounceEvent.STOPPED, AnnounceEvent.STARTED),
                tracker.events,
                "the swarm was not told this client is back",
            )
            // The dial, not `connectedPeers`: `FakeDialer` hands back the same `FakeConnection` it
            // made the first time, and the pause closed it — so the redial reaches a connection
            // whose event channel is already shut and drops straight back out. What is being
            // asserted is that the session went for the swarm again, which is the half of this the
            // fake can answer for. A set, because those instant drops make `serve`'s `finally` call
            // `connectMore` in turn and a peer can legitimately be dialled twice in one drain.
            assertEquals(
                setOf(peerA, peerB),
                dialer.dialled.drop(dialledWhilePaused).toSet(),
                "resuming dialled nobody",
            )
            assertEquals(hashedWhilePaused, hasher.hashed, "resuming re-verified pieces")

            job.cancelAndJoin()
        }

    /**
     * A re-check reads the whole disk again and says nothing to the tracker.
     *
     * The three assertions are the three halves of the decision: the hasher ran over every piece
     * (the record was not trusted), the tracker heard nothing (a disk check is not the swarm's
     * business), and the session came back to what it was doing.
     */
    @Test
    fun aRecheckHashesEveryPieceAndTellsTheTrackerNothing() =
        runTest {
            val metainfo = torrent(pieces = 2)
            val dialer = FakeDialer(metainfo.infoHash)
            val tracker = FakeTracker(listOf(peerA, peerB))
            val hasher = AgreeableHasher(metainfo)
            val storage = FakeStorage()
            val session = session(metainfo, dialer, tracker, storage, hasher)

            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            val announcesBefore = tracker.events.size

            session.send(Command.Recheck)
            testScheduler.runCurrent()

            assertEquals(
                announcesBefore,
                tracker.events.size,
                "a disk check announced itself to the swarm",
            )
            assertEquals(
                metainfo.pieceCount,
                session.state.value.verifyingOf,
                "the pass did not cover the whole torrent",
            )
            assertEquals(false, session.state.value.paused, "a running torrent stayed paused after its re-check")
            // Written after the first version of this test passed while the running application
            // showed "the picker is already in use" on its banner: the pass publishes its progress
            // *before* it seeds the picker, so every assertion above held on a session that had
            // already failed.
            assertEquals(null, session.state.value.sessionError, "the re-check degraded the session")
            assertTrue(job.isActive)

            job.cancelAndJoin()
        }

    /**
     * A re-check counts what this client claimed and the disk does not back.
     *
     * **The number exists because its absence cost a diagnosis.** A file the Files tab called
     * complete was not there, and the only pass that can tell "the picker was wrong" from "the
     * bytes are wrong" threw away what it disproved, so by the time anybody asked, the download had
     * finished and the evidence with it
     * ([B-119](../../../../../../../../docs/backlog/B-119-a-file-the-files-tab-calls-complete-is-not.md)).
     * Here the picker believes both pieces — the start-up pass read them — and the disk loses them
     * under it, which is what a record vouching for bytes that were never forced looks like from
     * inside the session.
     */
    @Test
    fun aRecheckSaysHowManyPiecesThisClientClaimedAndCouldNotShow() =
        runTest {
            val metainfo = torrent(pieces = 2)
            val hasher = AgreeableHasher(metainfo)
            val storage =
                FakeStorage().apply {
                    present += 0
                    present += 1
                }
            val session =
                session(metainfo, FakeDialer(metainfo.infoHash), FakeTracker(listOf(peerA)), storage, hasher)
            session.restore(hasher)
            assertEquals(metainfo.pieceCount, session.state.value.completedPieces, "the disk started sound")
            assertEquals(0, session.state.value.claimedNotOnDisk, "nothing has been disproved yet")

            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            // The bytes go away while the client goes on believing in them.
            storage.present.clear()
            session.send(Command.Recheck)
            testScheduler.runCurrent()

            assertEquals(0, session.state.value.completedPieces, "the re-check still believed the pieces")
            assertEquals(
                2,
                session.state.value.claimedNotOnDisk,
                "the client claimed two pieces it could not show and the re-check did not say so",
            )

            job.cancelAndJoin()
        }

    /**
     * A piece that went bad on the disk is found, and the torrent stops claiming it.
     *
     * The hasher is the corruption: it agrees while the torrent is being served and disagrees when
     * the re-check reads the file, which is what a byte flipped under the client looks like from
     * inside the session.
     */
    @Test
    fun aRecheckFindsAPieceThatWentBadOnTheDisk() =
        runTest {
            val metainfo = torrent(pieces = 2)
            val hasher = TurncoatHasher(metainfo)
            // Both pieces are on the "disk", so the start-up pass reads them and believes them.
            val storage =
                FakeStorage().apply {
                    present += 0
                    present += 1
                }
            val session =
                session(metainfo, FakeDialer(metainfo.infoHash), FakeTracker(listOf(peerA)), storage, hasher)
            session.restore(hasher)
            assertEquals(metainfo.pieceCount, session.state.value.completedPieces, "the disk started sound")

            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            hasher.turn()
            session.send(Command.Recheck)
            testScheduler.runCurrent()

            assertEquals(0, session.state.value.completedPieces, "the torrent still claims pieces the disk lost")
            assertEquals(metainfo.totalLength, session.state.value.left)
            assertEquals(false, session.state.value.isComplete)
            assertEquals(null, session.state.value.sessionError)

            job.cancelAndJoin()
        }

    /** And a paused torrent is still paused when the pass ends. */
    @Test
    fun aRecheckLeavesAPausedTorrentPaused() =
        runTest {
            val metainfo = torrent(pieces = 2)
            val session =
                session(
                    metainfo,
                    FakeDialer(metainfo.infoHash),
                    FakeTracker(listOf(peerA)),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            session.send(Command.Pause)
            testScheduler.runCurrent()

            session.send(Command.Recheck)
            testScheduler.runCurrent()

            assertTrue(session.state.value.paused, "the re-check started a torrent somebody had paused")
            job.cancelAndJoin()
        }

    /**
     * The peer list is a list of rows, not a list of connections.
     *
     * It carries what the *Peers* tab draws — who, how, and what they are doing — and nothing a
     * reader could hold on to. It is rebuilt on the timer, so this waits for one tick rather than
     * asserting on the state a connect published.
     */
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun theSessionNamesItsPeersAndNotJustCountsThem() =
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
            val connection = dialer.connections.getValue(peerA)
            connection.incoming.send(PeerEvent.Received(Message.Bitfield(allOf(metainfo.pieceCount))))
            connection.incoming.send(PeerEvent.Received(Message.Unchoke))
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(SessionConfig().tick.inWholeMilliseconds + 1)
            testScheduler.runCurrent()

            val peers = session.state.value.peers
            assertEquals(
                session.state.value.connectedPeers,
                peers.size,
                "the count and the list disagree about how many peers there are",
            )
            val a = peers.single { it.address == peerA.toString() }
            assertEquals(true, a.dialled, "this client dialled it")
            assertEquals(false, a.choking, "it unchoked us")
            assertEquals(metainfo.pieceCount, a.pieces, "it said it has everything")
            assertTrue(a.client.isNotBlank(), "a row with no client is a row that looks broken")
            val b = peers.single { it.address == peerB.toString() }
            assertEquals(true, b.choking, "it has said nothing, so it is still choking us")
            assertEquals(0, b.pieces)

            job.cancelAndJoin()
        }

    /** And a peer that goes leaves the list, rather than lingering as a row of stale numbers. */
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun aPeerThatGoesLeavesTheList() =
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
            dialer.connections
                .getValue(peerA)
                .incoming
                .close()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(SessionConfig().tick.inWholeMilliseconds + 1)
            testScheduler.runCurrent()

            assertTrue(
                session.state.value.peers
                    .none { it.address == peerA.toString() },
                "a closed peer was still in the list",
            )

            job.cancelAndJoin()
        }

    /**
     * A file nobody wants is never asked for, all the way from the options to the wire.
     *
     * The engine-level pieces of this are tested where they live; this is the seam — a session
     * built with one file unwanted asks the swarm for the other one's pieces and no others, and
     * announces `left` as the wanted half rather than the whole torrent.
     */
    @Test
    fun anUnwantedFileIsNeverRequested() =
        runTest {
            val metainfo = twoFileTorrent(pieces = 8)
            val dialer = FakeDialer(metainfo.infoHash)
            val storage = FakeStorage()
            val session =
                session(
                    metainfo,
                    dialer,
                    FakeTracker(listOf(peerA)),
                    storage,
                    AgreeableHasher(metainfo),
                    config = SessionConfig(maxStartedPieces = 8, pipelineDepth = 8, maxPeers = 10),
                    unwantedFiles = setOf(1),
                )
            session.restore(AgreeableHasher(metainfo))
            assertEquals(
                metainfo.totalLength / 2,
                session.state.value.left,
                "`left` counts the whole torrent, not the half this client wants",
            )

            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            val connection = dialer.connections.getValue(peerA)
            connection.incoming.send(PeerEvent.Received(Message.Bitfield(allOf(metainfo.pieceCount))))
            connection.incoming.send(PeerEvent.Received(Message.Unchoke))
            testScheduler.runCurrent()

            val asked =
                connection.sent
                    .filterIsInstance<Message.Request>()
                    .map { it.piece.value }
                    .toSet()
            assertTrue(asked.isNotEmpty(), "nothing was asked for at all")
            assertEquals(
                emptySet(),
                asked.filter { it >= metainfo.pieceCount / 2 }.toSet(),
                "a piece belonging only to the skipped file was requested",
            )

            job.cancelAndJoin()
        }

    /**
     * Each tracker's own answer, and the ones nobody reached said so.
     *
     * BEP 12 has the session stop at the first tracker that works, so a list of three normally has
     * one report and two silences — and the silences are a status rather than an absence.
     */
    @Test
    fun everyTrackerGetsItsOwnStatus() =
        runTest {
            val metainfo = torrent(pieces = 2)
            val session =
                session(
                    metainfo,
                    FakeDialer(metainfo.infoHash),
                    FakeTracker(listOf(peerA)),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            val trackers = session.state.value.trackers
            assertEquals(metainfo.trackers, trackers.map { it.url }, "the list is the torrent's own")
            assertEquals(TrackerView.Status.Working, trackers.first().status)
            assertEquals(1, trackers.first().peers, "the tracker returned one peer")
            assertEquals(null, trackers.first().message)

            job.cancelAndJoin()
        }

    /** A tracker that refuses keeps its own words, and the session keeps going. */
    @Test
    fun aTrackerThatRefusesIsRecordedInItsOwnWords() =
        runTest {
            val metainfo = torrent(pieces = 2)
            val session =
                session(
                    metainfo,
                    FakeDialer(metainfo.infoHash),
                    FakeTracker(emptyList(), failWith = "502 Bad Gateway"),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            val tracker =
                session.state.value.trackers
                    .first()
            assertEquals(TrackerView.Status.Failed, tracker.status)
            assertEquals("502 Bad Gateway", tracker.message)
            assertTrue(job.isActive, "one tracker refusing ended the session")

            job.cancelAndJoin()
        }

    /** And a person can ask again, out of turn. */
    @Test
    fun announcingByHandAsksTheTrackerAgain() =
        runTest {
            val metainfo = torrent(pieces = 2)
            val tracker = FakeTracker(listOf(peerA))
            val session =
                session(
                    metainfo,
                    FakeDialer(metainfo.infoHash),
                    tracker,
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            val before = tracker.events.size

            session.send(Command.Announce)
            testScheduler.runCurrent()

            assertEquals(before + 1, tracker.events.size, "the tracker was not asked again")
            assertEquals(null, tracker.events.last(), "a re-announce carries no event, like a periodic one")

            job.cancelAndJoin()
        }

    /**
     * A raised peer count reaches a session that is already running, and dials.
     *
     * Raising it and waiting is not enough: the loop that would use the room is the one that runs
     * when a peer drops, which may be an hour away, so the command dials on the spot.
     */
    @Test
    fun raisingThePeerCountDialsMorePeersWithoutARestart() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val dialer = FakeDialer(metainfo.infoHash)
            val session =
                session(
                    metainfo,
                    dialer,
                    FakeTracker(listOf(peerA, peerB, peerC)),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                    config = SessionConfig(maxStartedPieces = 4, pipelineDepth = 2, maxPeers = 1),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            assertEquals(1, session.state.value.connectedPeers, "the limit was one")

            session.send(Command.Reconfigure(maxPeers = 3))
            testScheduler.runCurrent()

            assertEquals(3, session.state.value.connectedPeers, "the new limit did not reach the session")

            job.cancelAndJoin()
        }

    /**
     * The order can be changed under a running session, and the state says so.
     *
     * The seam this covers is the one that survived being deleted: the picker's own test knows the
     * picker can be switched and the panel's test knows the control asks, and between them sat a
     * `Command.Reconfigure` branch that nothing exercised
     * ([B-89](../../../../../../../../docs/backlog/B-89-sequential-on-a-running-torrent.md)).
     *
     * The *state* and not only the picker, because that is what the control draws: a panel showing
     * what it last asked for rather than what the session is doing would disagree with the client
     * the first time a command was lost.
     */
    @Test
    fun theOrderCanBeChangedOnARunningSession() =
        runTest {
            val metainfo = torrent(pieces = 8)
            val session =
                session(
                    metainfo,
                    FakeDialer(metainfo.infoHash),
                    FakeTracker(listOf(peerA)),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            assertFalse(session.state.value.sequential, "a session starts rarest-first")

            session.send(Command.Reconfigure(sequential = true))
            testScheduler.runCurrent()
            assertTrue(session.state.value.sequential, "the order did not reach the running session")

            session.send(Command.Reconfigure(sequential = false))
            testScheduler.runCurrent()
            assertFalse(session.state.value.sequential, "the order could not be turned back off")

            job.cancelAndJoin()
        }

    /** A reconfigure that says nothing about the order leaves it where it was. */
    @Test
    fun aReconfigureThatDoesNotMentionTheOrderDoesNotChangeIt() =
        runTest {
            val metainfo = torrent(pieces = 8)
            val session =
                session(
                    metainfo,
                    FakeDialer(metainfo.infoHash),
                    FakeTracker(listOf(peerA)),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                    sequential = true,
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            assertTrue(session.state.value.sequential, "a session built in order did not say so")

            // The settings screen sends this whenever a rate limit is typed into, and it must not
            // take one torrent's order away with it.
            session.send(Command.Reconfigure(maxPeers = 3))
            testScheduler.runCurrent()
            assertTrue(session.state.value.sequential, "editing a setting reset this torrent's order")

            job.cancelAndJoin()
        }

    /**
     * A periodic job that fails and then works again does not leave the torrent marked for ever.
     *
     * Reported from a running client: after `dht lookup: ConcurrentModificationException` there was
     * nothing to be done with the torrent but remove it. The loop had recovered within the second —
     * `tick` catches, records and carries on — and the *mark* was what never came off, so the row
     * said `Error` and the banner stayed up on a session that was working
     * ([B-94](../../../../../../../../docs/backlog/B-94-a-degraded-session-cannot-recover.md)).
     */
    @Test
    fun aPassThatFailsAndThenWorksClearsItsOwnMark() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val storage = FailingFlushStorage()
            val session =
                session(
                    metainfo,
                    FakeDialer(metainfo.infoHash),
                    FakeTracker(listOf(peerA)),
                    storage,
                    AgreeableHasher(metainfo),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))

            storage.failFlush = true
            testScheduler.advanceTimeBy(SessionConfig().flushInterval * 2)
            testScheduler.runCurrent()
            assertContains(
                session.state.value.sessionError
                    .orEmpty(),
                "flush",
            )

            storage.failFlush = false
            testScheduler.advanceTimeBy(SessionConfig().flushInterval * 2)
            testScheduler.runCurrent()
            assertEquals(null, session.state.value.sessionError, "the mark outlived the failure")

            job.cancelAndJoin()
        }

    /**
     * And one job's recovery does not rub out another job's failure.
     *
     * Clearing whatever happens to be there would be worse than never clearing: a `dht lookup` that
     * starts answering would hide a `flush` that is still losing data.
     */
    @Test
    fun onePassClearsOnlyItsOwnMark() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val storage = FailingFlushStorage()
            val session =
                session(
                    metainfo,
                    FakeDialer(metainfo.infoHash),
                    FakeTracker(listOf(peerA)),
                    storage,
                    AgreeableHasher(metainfo),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))

            storage.failFlush = true
            testScheduler.advanceTimeBy(SessionConfig().flushInterval * 2)
            testScheduler.runCurrent()
            assertContains(
                session.state.value.sessionError
                    .orEmpty(),
                "flush",
            )

            // Every other periodic job is succeeding all the while — keep-alives, rates, expiry —
            // and not one of them may take this mark off.
            testScheduler.advanceTimeBy(SessionConfig().flushInterval * 4)
            testScheduler.runCurrent()
            assertTrue(
                "flush" in
                    session.state.value.sessionError
                        .orEmpty(),
                "another job's success cleared a failure that is still happening",
            )

            // Left healthy on the way out, because `nothingEscaped` refuses a suite in which any
            // session ended degraded — and it is right to: an error nobody cleared is the defect
            // this test is about. The assertion above has already been made.
            storage.failFlush = false
            testScheduler.advanceTimeBy(SessionConfig().flushInterval * 2)
            testScheduler.runCurrent()

            job.cancelAndJoin()
        }

    /** And a limit set to nothing is no limit, on a session that was throttled a moment ago. */
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun aRateLimitCanBeChangedAndLifted() =
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
                    config =
                        SessionConfig(
                            maxStartedPieces = 4,
                            pipelineDepth = 2,
                            maxPeers = 10,
                            downloadLimitBytesPerSecond = 1024,
                        ),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            val connection = dialer.connections.getValue(peerA)
            connection.incoming.send(PeerEvent.Received(Message.Bitfield(allOf(metainfo.pieceCount))))
            connection.incoming.send(PeerEvent.Received(Message.Unchoke))
            testScheduler.runCurrent()
            val throttled = connection.sent.filterIsInstance<Message.Request>().size

            session.send(Command.Reconfigure(downloadLimitBytesPerSecond = 0))
            testScheduler.advanceTimeBy(SessionConfig().tick.inWholeMilliseconds + 1)
            testScheduler.runCurrent()

            assertTrue(
                connection.sent.filterIsInstance<Message.Request>().size > throttled,
                "lifting the limit asked for nothing more",
            )

            job.cancelAndJoin()
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

    /**
     * B-95: the connections are topped up by the clock, not by whatever event happens along.
     *
     * Twenty-four of the thirty addresses refuse. The session holds ten at a time, so the first
     * pass dials ten and every one of them fails — and nothing in the old code asked for a
     * replacement: a failed dial recorded itself in `failed` and returned, and the next caller of
     * `connectMore` was the announce, thirty minutes of virtual time away. The assertion is that
     * within a few ticks the client is at its cap, with the announce interval nowhere near.
     */
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun theConnectionsAreToppedUpOnTheTimerAndNotOnlyWhenSomethingHappens(): Unit =
        runTest {
            val metainfo = torrent(pieces = 4)
            val all = (1..30).map { PeerAddress("10.0.0.$it", 6881) }
            val dialer = FakeDialer(metainfo.infoHash, refuse = all.take(24).toSet())
            val tracker = FakeTracker(all)
            val session =
                session(
                    metainfo,
                    dialer,
                    tracker,
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                    config = SessionConfig(maxStartedPieces = 4, pipelineDepth = 2, maxPeers = 6),
                )

            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            assertEquals(
                0,
                session.state.value.connectedPeers,
                "the first pass dials six addresses and all six refuse",
            )

            // Well short of the 1 800 s announce interval, so nothing but the timer can be dialling.
            repeat(8) {
                testScheduler.advanceTimeBy(1_000)
                testScheduler.runCurrent()
            }

            assertEquals(6, session.state.value.connectedPeers, "the timer never reached the peers that answer")
            // Without this the test would also pass on the old code if the announce interval ever
            // dropped below the window above: it is the timer that has to be doing the dialling.
            assertEquals(
                listOf<AnnounceEvent?>(AnnounceEvent.STARTED),
                tracker.events,
                "a second announce ran, so this proves nothing about the timer",
            )
            assertEquals(30, dialer.dialled.size, "every address was tried exactly once")
            job.cancelAndJoin()
        }

    /**
     * B-95: an address whose dial is still outstanding is not dialled a second time.
     *
     * The dialer here never answers, which is what most of a public swarm's addresses do for the
     * length of the connect timeout. With the timer dialling every tick and no record of what is
     * in flight, each of those addresses would be dialled once a second for ever.
     */
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun anAddressWhoseDialIsStillOutstandingIsNotDialledAgain(): Unit =
        runTest {
            val metainfo = torrent(pieces = 4)
            val silent = SilentDialer()
            val session =
                session(
                    metainfo,
                    silent,
                    FakeTracker(listOf(peerA, peerB, peerC)),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                    config = SessionConfig(maxStartedPieces = 4, pipelineDepth = 2, maxPeers = 10),
                )

            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            repeat(10) {
                testScheduler.advanceTimeBy(1_000)
                testScheduler.runCurrent()
            }

            assertEquals(
                listOf(peerA, peerB, peerC),
                silent.dialled,
                "each address is dialled once while its dial is outstanding, not once per tick",
            )
            job.cancelAndJoin()
        }

    /** A dialer whose `connect` never returns — the address a swarm hands out that does not answer. */
    private class SilentDialer : PeerDialer {
        val dialled = mutableListOf<PeerAddress>()

        override suspend fun connect(address: PeerAddress): PeerConnection {
            dialled += address
            kotlinx.coroutines.awaitCancellation()
        }
    }

    /**
     * B-97: the announce asks for the connections this client is short of.
     *
     * `numWant` existed on the request, both transports wrote it, and the session never filled it
     * in — so every announce asked for whatever default the tracker had picked.
     */
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun theAnnounceAsksForTheConnectionsThisClientIsShortOf(): Unit =
        runTest {
            val metainfo = torrent(pieces = 4)
            val dialer = FakeDialer(metainfo.infoHash)
            val tracker = RecordingTracker(listOf(peerA, peerB))
            val session =
                session(
                    metainfo,
                    dialer,
                    tracker,
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                    config = SessionConfig(maxStartedPieces = 4, pipelineDepth = 2, maxPeers = 6),
                )

            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            assertEquals(6, tracker.numWants.first(), "the first announce is made with nothing connected")
            assertEquals(2, session.state.value.connectedPeers)

            session.send(Command.Announce)
            testScheduler.runCurrent()
            assertEquals(4, tracker.numWants.last(), "six wanted, two connected, so four short")
            job.cancelAndJoin()
        }

    /** B-97: a client that is leaving asks for nobody, whatever its deficit says. */
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun aStoppedAnnounceAsksForNoPeers(): Unit =
        runTest {
            val metainfo = torrent(pieces = 4)
            val tracker = RecordingTracker(listOf(peerA))
            val session =
                session(
                    metainfo,
                    FakeDialer(metainfo.infoHash),
                    tracker,
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                    config = SessionConfig(maxStartedPieces = 4, pipelineDepth = 2, maxPeers = 6),
                )

            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            session.send(Command.Pause)
            testScheduler.runCurrent()

            val stopped = tracker.events.indexOf(AnnounceEvent.STOPPED)
            assertTrue(stopped >= 0, "the pause did not announce stopped")
            assertEquals(0, tracker.numWants[stopped], "a client that is leaving still asked for peers")
            job.cancelAndJoin()
        }

    /**
     * B-97: off, the first tracker that answers ends the announce; on, every one is asked and the
     * peers are the union.
     */
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun askingEveryTrackerIsASettingAndNotTheDefault(): Unit =
        runTest {
            val metainfo = threeTrackerTorrent(pieces = 4)
            val perTracker =
                mapOf(
                    "http://a.example/annc" to listOf(peerA),
                    "http://b.example/annc" to listOf(peerB),
                    "http://c.example/annc" to listOf(peerA, peerC),
                )

            val defaultTracker = PerUrlTracker(perTracker)
            val byDefault =
                session(
                    metainfo,
                    FakeDialer(metainfo.infoHash),
                    defaultTracker,
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                    config = SessionConfig(maxStartedPieces = 4, pipelineDepth = 2, maxPeers = 10),
                )
            val first = byDefault.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            assertEquals(listOf("http://a.example/annc"), defaultTracker.asked, "BEP 12: the first that answers")
            assertEquals(1, byDefault.state.value.knownPeers)
            first.cancelAndJoin()

            val allTracker = PerUrlTracker(perTracker)
            val toAll =
                session(
                    metainfo,
                    FakeDialer(metainfo.infoHash),
                    allTracker,
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                    config =
                        SessionConfig(
                            maxStartedPieces = 4,
                            pipelineDepth = 2,
                            maxPeers = 10,
                            announceToAllTrackers = true,
                        ),
                )
            val second = toAll.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            assertEquals(perTracker.keys.toList(), allTracker.asked, "every tracker the torrent names")
            // Three addresses and not four: peer A is named by two trackers and is one peer.
            assertEquals(3, toAll.state.value.knownPeers, "the peers are the union, deduplicated")
            second.cancelAndJoin()
        }

    /**
     * B-98: the dials are counted, and the failures are counted by kind.
     *
     * `connectedPeers` alone cannot tell a client holding two peers after three dials from one
     * holding two after thirty — which is the entire question the measurement asks.
     */
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun theDialsAndTheirFailuresAreCounted(): Unit =
        runTest {
            val metainfo = torrent(pieces = 4)
            val all = listOf(peerA, peerB, peerC)
            // Peer B refuses with a wire error, peer C with a timeout: two labels, not two messages.
            val dialer =
                LabelledDialer(
                    metainfo.infoHash,
                    peerB to WireException("peer answered for another torrent: ab12"),
                    peerC to IllegalStateException("Connection refused"),
                )
            val session =
                session(
                    metainfo,
                    dialer,
                    FakeTracker(all),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                    config = SessionConfig(maxStartedPieces = 4, pipelineDepth = 2, maxPeers = 10),
                )

            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            val state = session.state.value
            assertEquals(3, state.dialsAttempted, "one per address the session dialled")
            assertEquals(1, state.dialsHandshaked, "only peer A answered")
            assertEquals(1, state.connectedPeers)
            assertEquals(
                mapOf("another torrent" to 1, "refused" to 1),
                state.dialFailures,
                "failures are bucketed by kind, not by the message that names an address",
            )
            job.cancelAndJoin()
        }

    /** A dialer that fails named addresses with a chosen exception, so the labels can be asserted. */
    private class LabelledDialer(
        private val infoHash: io.github.youndie.kachok.engine.InfoHash,
        vararg failures: Pair<PeerAddress, Exception>,
    ) : PeerDialer {
        private val failWith = failures.toMap()
        val connections = LinkedHashMap<PeerAddress, FakeConnection>()

        override suspend fun connect(address: PeerAddress): PeerConnection {
            failWith[address]?.let { throw it }
            return connections.getOrPut(address) { FakeConnection(address, infoHash) }
        }
    }

    /** A tracker that remembers what it was asked for, not only that it was asked. */
    private class RecordingTracker(
        private val peers: List<PeerAddress>,
    ) : TrackerClient {
        val events = mutableListOf<AnnounceEvent?>()
        val numWants = mutableListOf<Int?>()

        override suspend fun announce(
            tracker: String,
            request: AnnounceRequest,
        ): AnnounceResponse {
            events += request.event
            numWants += request.numWant
            return AnnounceResponse(interval = 1800, peers = peers)
        }
    }

    /** A tracker whose answer depends on which URL was called, so a union can be told from a first. */
    private class PerUrlTracker(
        private val byUrl: Map<String, List<PeerAddress>>,
    ) : TrackerClient {
        val asked = mutableListOf<String>()

        override suspend fun announce(
            tracker: String,
            request: AnnounceRequest,
        ): AnnounceResponse {
            asked += tracker
            val peers = byUrl[tracker] ?: throw TrackerException("no such tracker")
            return AnnounceResponse(interval = 1800, peers = peers)
        }
    }

    /** A torrent whose `announce-list` names three trackers, each with its own slice of the swarm. */
    private fun threeTrackerTorrent(pieces: Int): Metainfo {
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
                    BString("announce") to BString("http://a.example/annc"),
                    BString("announce-list") to
                        io.github.youndie.kachok.engine.bencode.BList(
                            listOf(
                                io.github.youndie.kachok.engine.bencode
                                    .BList(listOf(BString("http://a.example/annc"))),
                                io.github.youndie.kachok.engine.bencode
                                    .BList(listOf(BString("http://b.example/annc"))),
                                io.github.youndie.kachok.engine.bencode
                                    .BList(listOf(BString("http://c.example/annc"))),
                            ),
                        ),
                    BString("info") to info,
                ),
            )
        return MetainfoParser.parse(Bencode.encode(root))
    }

    /**
     * A file raised on a running session is asked for first, and the state says which tier it is in.
     *
     * The seam, as with the order (B-89): the picker's own tests know a pool can be raised and the
     * panel's know the control asks; between them is a `Command.PrioritiseFile` branch that only
     * this exercises ([B-106](../../../../../../../../docs/backlog/B-106-per-file-priority.md)).
     * The request order is what a person sees as "that file moves first", so it is what is asserted,
     * and the *state* is asserted because that is what the panel draws.
     */
    @Test
    fun aFileRaisedOnARunningSessionIsAskedForFirst() =
        runTest {
            val metainfo = twoFileTorrent(pieces = 8)
            val dialer = FakeDialer(metainfo.infoHash)
            val session =
                session(
                    metainfo,
                    dialer,
                    FakeTracker(listOf(peerA)),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                    config = SessionConfig(maxStartedPieces = 1, pipelineDepth = 1, maxPeers = 10),
                )
            session.restore(AgreeableHasher(metainfo))
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            session.send(Command.PrioritiseFile(1, FilePriority.HIGH))
            testScheduler.runCurrent()
            assertEquals(
                listOf(FilePriority.NORMAL, FilePriority.HIGH),
                session.state.value.files
                    .map { it.priority },
                "the tier did not reach the state, or took the other file with it",
            )

            val connection = dialer.connections.getValue(peerA)
            connection.incoming.send(PeerEvent.Received(Message.Bitfield(allOf(metainfo.pieceCount))))
            connection.incoming.send(PeerEvent.Received(Message.Unchoke))
            testScheduler.runCurrent()

            val first =
                connection.sent
                    .filterIsInstance<Message.Request>()
                    .first()
                    .piece
                    .value
            assertTrue(first >= metainfo.pieceCount / 2, "the first request ($first) was not for the raised file")

            job.cancelAndJoin()
        }

    /** A file skipped on a running session leaves `left` and the file list, and does so at once. */
    @Test
    fun aFileSkippedOnARunningSessionStopsBeingOwed() =
        runTest {
            val metainfo = twoFileTorrent(pieces = 8)
            val session =
                session(
                    metainfo,
                    FakeDialer(metainfo.infoHash),
                    FakeTracker(listOf(peerA)),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                )
            session.restore(AgreeableHasher(metainfo))
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            assertEquals(metainfo.totalLength, session.state.value.left)

            session.send(Command.PrioritiseFile(0, FilePriority.SKIP))
            testScheduler.runCurrent()

            val state = session.state.value
            assertEquals(metainfo.totalLength / 2, state.left, "`left` still counts the skipped file")
            assertFalse(state.files[0].wanted, "the skipped file still reads as wanted")
            assertEquals(FilePriority.SKIP, state.files[0].priority)

            job.cancelAndJoin()
        }

    /** `-FAKE01-` and then the address, so two fakes are two peers unless a test says otherwise. */
    private companion object {
        fun fakeIdFor(address: PeerAddress): PeerId =
            PeerId(
                (
                    "-FAKE01-" +
                        address.host.replace(
                            ".",
                            "",
                        ) + address.port
                ).padEnd(20, '0').take(20).encodeToByteArray(),
            )
    }

    /**
     * B-111: a second connection from a peer this session already holds is closed, by peer id.
     *
     * The address differs — that is the whole case: the same client dialled off the tracker and
     * dialling back off local discovery, and a picker that took the second as a new peer asked it
     * for everything in endgame. Two of the same kind — here a peer arriving twice from two ports —
     * keep the one already held. Counted under its own reason, so B-98's counters show it.
     */
    @Test
    fun aSecondConnectionFromAPeerAlreadyHeldIsClosedAndCounted() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val session =
                session(
                    metainfo,
                    FakeDialer(metainfo.infoHash),
                    FakeTracker(emptyList()),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            val first = FakeConnection(peerA, metainfo.infoHash)
            session.send(Command.AcceptPeer(first))
            testScheduler.runCurrent()
            assertEquals(1, session.state.value.connectedPeers)

            val again = FakeConnection(peerB, metainfo.infoHash, peerId = first.handshake.peerId)
            session.send(Command.AcceptPeer(again))
            testScheduler.runCurrent()

            assertEquals(1, again.closes, "the duplicate was not closed")
            assertEquals(0, first.closes, "the connection already held was closed instead of the newcomer")
            assertEquals(1, session.state.value.connectedPeers)
            assertEquals(
                1,
                session.state.value.disconnectReasons["duplicate peer"],
                session.state.value.disconnectReasons
                    .toString(),
            )
            job.cancelAndJoin()
        }

    /**
     * When the two connections differ in who dialled, "keep the first seen" is the wrong rule: two
     * clients that hear each other at once each see a different one first, close the other, and
     * hold nothing. The rule both sides can agree on is that the connection dialled by the *lower*
     * peer id stays — so here, where the peer's id sorts below ours, the one it dialled (accepted
     * by us) wins over the one we dialled, even though ours was there first.
     */
    @Test
    fun aTieBetweenDialledAndAcceptedGoesToTheLowerPeerIdsDial() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val dialer = FakeDialer(metainfo.infoHash)
            val session =
                session(metainfo, dialer, FakeTracker(listOf(peerA)), FakeStorage(), AgreeableHasher(metainfo))
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            val dialled = dialer.connections.getValue(peerA)
            assertTrue(
                dialled.handshake.peerId.bytes
                    .first { it != '-'.code.toByte() } < 'K'.code.toByte(),
            )

            val accepted = FakeConnection(peerB, metainfo.infoHash, peerId = dialled.handshake.peerId)
            session.send(Command.AcceptPeer(accepted))
            testScheduler.runCurrent()

            assertEquals(0, accepted.closes, "the accepted connection lost a tie the lower id's dial should win")
            // Closed by the tie and again by its own teardown, like every link the session hangs up on.
            assertTrue(dialled.closes >= 1, "the connection we dialled was kept over the lower id's own dial")
            assertEquals(1, session.state.value.connectedPeers)
            assertEquals(1, session.state.value.disconnectReasons["duplicate peer"])
            job.cancelAndJoin()
        }

    /** The mirror image: the peer's id sorts above ours, so the connection *we* dialled is the one kept. */
    @Test
    fun aTieWithAHigherPeerIdKeepsOurOwnDial() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val higher = PeerId("-ZZ0001-000000000000".encodeToByteArray())
            val dialer = FakeDialer(metainfo.infoHash) { higher }
            val session =
                session(metainfo, dialer, FakeTracker(listOf(peerA)), FakeStorage(), AgreeableHasher(metainfo))
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            val dialled = dialer.connections.getValue(peerA)

            val accepted = FakeConnection(peerB, metainfo.infoHash, peerId = higher)
            session.send(Command.AcceptPeer(accepted))
            testScheduler.runCurrent()

            assertEquals(1, accepted.closes, "the higher id's own dial was kept over ours")
            assertEquals(0, dialled.closes)
            assertEquals(1, session.state.value.connectedPeers)
            assertEquals(1, session.state.value.disconnectReasons["duplicate peer"])
            job.cancelAndJoin()
        }

    /**
     * B-112's measurement: what came of each peer that wanted our pieces, counted as it leaves.
     *
     * One slot and two interested peers, so the pass at ten seconds unchokes exactly one — which
     * one is the seeded random's choice, and the test reads it off the wire rather than guessing.
     * The unchoked one asks and is `served`; the other is seen by the pass and passed over, and
     * leaves twelve seconds after its interest: `left choked after a pass`. A third peer that
     * leaves three seconds in was never reached by any pass: `left choked inside one pass`. A peer
     * this client hangs up on itself is not counted at all — that is stop, not the choker.
     */
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun whatCameOfEachInterestedPeerIsCountedWhenItLeaves() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val peerC = PeerAddress("10.0.0.3", 6881)
            val dialer = FakeDialer(metainfo.infoHash)
            val session =
                session(
                    metainfo,
                    dialer,
                    FakeTracker(listOf(peerA, peerB, peerC)),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                    config = SessionConfig(maxStartedPieces = 4, pipelineDepth = 2, maxPeers = 10, maxUnchoked = 1),
                    // The stay is measured on the session's clock, which here has to be the test's.
                    timeSource = testScheduler.timeSource,
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            val a = dialer.connections.getValue(peerA)
            val b = dialer.connections.getValue(peerB)
            val c = dialer.connections.getValue(peerC)
            // Something to serve, and a peer worth something, before anyone asks.
            a.incoming.send(PeerEvent.BlockReceived(FakeBlock(PieceIndex(1), 0, PeerWire.BLOCK_SIZE)))
            listOf(a, b, c).forEach { it.incoming.send(PeerEvent.Received(Message.Interested)) }
            testScheduler.runCurrent()

            testScheduler.advanceTimeBy(3_000)
            c.incoming.close()
            testScheduler.advanceTimeBy(8_000)
            testScheduler.runCurrent()

            val (given, passedOver) =
                if (a.sent.any { it is Message.Unchoke }) a to b else b to a
            assertTrue(passedOver.sent.none { it is Message.Unchoke }, "one slot, two unchokes")
            given.incoming.send(PeerEvent.Received(Message.Request(PieceIndex(1), 0, PeerWire.BLOCK_SIZE)))
            testScheduler.runCurrent()
            assertEquals(1, given.servedBlocks.size)

            testScheduler.advanceTimeBy(1_000)
            given.incoming.close()
            passedOver.incoming.close()
            testScheduler.runCurrent()

            assertEquals(
                mapOf("served" to 1, "left choked after a pass" to 1, "left choked inside one pass" to 1),
                session.state.value.interestOutcomes,
            )
            job.cancelAndJoin()
        }

    /**
     * One DHT node that answers everything and holds nothing: every lookup costs one `get_peers`,
     * so counting those is counting lookups.
     */
    private class LonelyDht(
        private val holds: List<PeerAddress> = emptyList(),
    ) : io.github.youndie.kachok.engine.dht.KrpcTransport {
        val node =
            io.github.youndie.kachok.engine.dht.DhtNode(
                io.github.youndie.kachok.engine.dht
                    .NodeId(ByteArray(20) { 0x0F }),
                PeerAddress("10.0.0.9", 6881),
            )
        var lookups = 0
        var announces = 0

        override suspend fun query(
            node: PeerAddress,
            method: String,
            arguments: io.github.youndie.kachok.engine.bencode.BDictionary,
        ): io.github.youndie.kachok.engine.dht.KrpcMessage.Response {
            val fields = LinkedHashMap<BString, io.github.youndie.kachok.engine.bencode.BValue>()
            fields[BString("id")] = BString(this.node.id.bytes)
            when (method) {
                io.github.youndie.kachok.engine.dht.Krpc.FIND_NODE -> {
                    fields[BString("nodes")] =
                        BString(
                            io.github.youndie.kachok.engine.dht.Krpc
                                .encodeNodes(listOf(this.node)),
                        )
                }

                io.github.youndie.kachok.engine.dht.Krpc.GET_PEERS -> {
                    lookups++
                    fields[BString("token")] = BString("t".encodeToByteArray())
                    if (holds.isNotEmpty()) {
                        fields[BString("values")] =
                            io.github.youndie.kachok.engine.dht.Krpc
                                .encodeValues(holds)
                    } else {
                        fields[BString("nodes")] =
                            BString(
                                io.github.youndie.kachok.engine.dht.Krpc
                                    .encodeNodes(listOf(this.node)),
                            )
                    }
                }

                io.github.youndie.kachok.engine.dht.Krpc.ANNOUNCE_PEER -> {
                    announces++
                }
            }
            return io.github.youndie.kachok.engine.dht.KrpcMessage
                .Response(byteArrayOf(1), BDictionary(fields))
        }
    }

    private fun dhtConfig() =
        SessionConfig(
            maxStartedPieces = 4,
            pipelineDepth = 2,
            maxPeers = 10,
            dhtBootstrap = listOf(PeerAddress("10.0.0.9", 6881)),
        )

    /**
     * A lookup that leaves the client short of addresses is retaken in seconds, doubling, and not
     * kept for fifteen minutes: on the public swarm the first one, taken while the bootstrap
     * nodes were not answering, found nothing, and the tracker there hands out one peer.
     */
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun aLookupThatFoundTooFewPeersIsRetakenSoonAndBacksOff() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val transport = LonelyDht()
            val dht =
                io.github.youndie.kachok.engine.dht.Dht(
                    io.github.youndie.kachok.engine.dht
                        .NodeId(ByteArray(20) { 0x01 }),
                    transport,
                )
            val session =
                session(
                    metainfo,
                    FakeDialer(metainfo.infoHash),
                    FakeTracker(emptyList()),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                    config = dhtConfig(),
                    timeSource = testScheduler.timeSource,
                    dht = dht,
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            assertEquals(1, transport.lookups, "the first lookup happens at start")

            testScheduler.advanceTimeBy(31_000)
            testScheduler.runCurrent()
            assertEquals(2, transport.lookups, "a starving client looks again after thirty seconds")
            testScheduler.advanceTimeBy(31_000)
            testScheduler.runCurrent()
            assertEquals(2, transport.lookups, "the second wait is a minute, not another thirty seconds")
            testScheduler.advanceTimeBy(30_000)
            testScheduler.runCurrent()
            assertEquals(3, transport.lookups)
            assertEquals(1, transport.announces, "looking again is not announcing again")
            job.cancelAndJoin()
        }

    /** A client with more addresses than it can hold keeps the snapshot for the full interval. */
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun aLookupThatFoundEnoughPeersIsKeptForTheFullInterval() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val plenty = (1..12).map { PeerAddress("10.1.0.$it", 6881) }
            val transport = LonelyDht(holds = plenty)
            val dht =
                io.github.youndie.kachok.engine.dht.Dht(
                    io.github.youndie.kachok.engine.dht
                        .NodeId(ByteArray(20) { 0x01 }),
                    transport,
                )
            val session =
                session(
                    metainfo,
                    FakeDialer(metainfo.infoHash, refuse = plenty.toSet()),
                    FakeTracker(emptyList()),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                    config = dhtConfig(),
                    timeSource = testScheduler.timeSource,
                    dht = dht,
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            assertEquals(1, transport.lookups)
            assertEquals(12, session.state.value.knownPeers)

            testScheduler.advanceTimeBy(14 * 60_000L)
            testScheduler.runCurrent()
            assertEquals(1, transport.lookups, "twelve known against ten wanted is not starving")
            testScheduler.advanceTimeBy(2 * 60_000L)
            testScheduler.runCurrent()
            assertEquals(2, transport.lookups)
            job.cancelAndJoin()
        }

    /**
     * The session's side of the same guarantee: a send the connection refuses because the peer
     * stopped reading ends that peer, counted under its own reason, and the timer that tried —
     * here the keep-alive — goes on to the next tick rather than waiting on it.
     */
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun aPeerThatStoppedReadingIsDroppedAndCountedAndTheTimerGoesOn() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val dialer = FakeDialer(metainfo.infoHash)
            val session =
                session(metainfo, dialer, FakeTracker(listOf(peerA, peerB)), FakeStorage(), AgreeableHasher(metainfo))
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            val stuck = dialer.connections.getValue(peerA)
            val fine = dialer.connections.getValue(peerB)
            stuck.sendFailsWith = java.io.IOException("$peerA stopped reading: 64 messages queued and none taken")

            testScheduler.advanceTimeBy(125_000)
            testScheduler.runCurrent()

            assertEquals(
                1,
                session.state.value.disconnectReasons["not reading"],
                session.state.value.disconnectReasons
                    .toString(),
            )
            assertTrue(
                fine.sent.any { it === Message.KeepAlive },
                "the keep-alive never reached the peer after the stuck one",
            )
            assertEquals(1, session.state.value.connectedPeers)
            job.cancelAndJoin()
        }

    /**
     * A dial that lands after the session has stopped is closed, and not registered into the map
     * the shutdown has just emptied.
     *
     * Found by B-100: an encrypted dial is two round trips and, against a peer that will not have
     * it, a timeout and a second dial — so the window between "the stop closed everything" and
     * "this dial finished" grew from milliseconds to seconds. What landed in it was a connection
     * with a reader parked on a socket nobody would ever close, and a client that printed
     * `stopping` and then sat there: on the JVM that is a virtual thread inside a blocking read,
     * which no cancellation can reach.
     */
    @Test
    fun aDialThatLandsAfterTheStopIsClosedRatherThanKept() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val landing = kotlinx.coroutines.CompletableDeferred<Unit>()
            val late = FakeConnection(peerA, metainfo.infoHash)
            val slow =
                object : PeerDialer {
                    override suspend fun connect(address: PeerAddress): PeerConnection {
                        // Uncancellable on purpose: a real dial is a socket and a handshake, and
                        // the cancellation lands when it is *finished* — which is the case that
                        // loses the connection.
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { landing.await() }
                        return late
                    }
                }
            val session =
                session(metainfo, slow, FakeTracker(listOf(peerA)), FakeStorage(), AgreeableHasher(metainfo))
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            session.send(Command.Stop)
            testScheduler.runCurrent()
            // And only now does the dial come back, with nothing left to give it to.
            landing.complete(Unit)
            testScheduler.runCurrent()

            assertEquals(1, late.closes, "the connection that arrived after the stop was kept open")
            assertEquals(0, session.state.value.connectedPeers)
            job.join()
        }

    /** And the cheapest case of the same rule: a peer offering this session's own id is this session. */
    @Test
    fun aConnectionOfferingOurOwnIdIsClosedAsOurselves() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val session =
                session(
                    metainfo,
                    FakeDialer(metainfo.infoHash),
                    FakeTracker(emptyList()),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            val mirror = FakeConnection(peerB, metainfo.infoHash, peerId = ourPeerId)
            session.send(Command.AcceptPeer(mirror))
            testScheduler.runCurrent()

            assertEquals(1, mirror.closes)
            assertEquals(0, session.state.value.connectedPeers)
            assertEquals(1, session.state.value.disconnectReasons["ourselves"])
            job.cancelAndJoin()
        }

    /**
     * B-118: an address that never answers is asked less and less often.
     *
     * The timer dials every tick, so with one flat `reconnectDelay` the schedule over an hour is
     * one dial every thirty seconds — 120 of them against an address that has never completed a
     * handshake. Doubling puts them at 0, 30, 90, 210, 450, 930 and 1890 seconds, and the eighth
     * falls at 3690, past the hour. Seven is the assertion; what it is really pinning is that the
     * count does not grow with the length of the run.
     */
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun anAddressThatNeverAnswersIsDialledLessAndLessOften(): Unit =
        runTest {
            val metainfo = torrent(pieces = 4)
            val dialer = FakeDialer(metainfo.infoHash, refuse = setOf(peerA))
            val session =
                session(
                    metainfo,
                    dialer,
                    FakeTracker(listOf(peerA)),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                    // The backoff is measured on the session's clock, which here has to be the test's.
                    timeSource = testScheduler.timeSource,
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()
            assertEquals(listOf(peerA), dialer.dialled, "the first dial goes out at once")

            testScheduler.advanceTimeBy(60 * 60 * 1_000L)
            testScheduler.runCurrent()

            assertEquals(7, dialer.dialled.size, "dialled at 0, 30, 90, 210, 450, 930 and 1890 seconds")
            assertTrue(dialer.dialled.all { it == peerA }, "one address, and the tracker offers no other")
            job.cancelAndJoin()
        }

    /**
     * B-118, the other half: the backoff belongs to an address, and a handshake ends it.
     *
     * A peer behind a NAT that opens a port has been dark for as long as it takes; the moment it
     * answers it is a live peer, and if it then hangs up it must be redialled on the flat delay
     * rather than on the hours its silence had earned. The dialer here refuses until the address
     * has served its third wait, so the peer arrives with a streak of three behind it — under
     * which a redial would be due at 120 seconds rather than 30.
     */
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun aPeerThatAnswersLosesTheBackoffItHadEarned(): Unit =
        runTest {
            val metainfo = torrent(pieces = 4)
            val refuse = mutableSetOf(peerA)
            val dialer = FakeDialer(metainfo.infoHash, refuse = refuse)
            val session =
                session(
                    metainfo,
                    dialer,
                    FakeTracker(listOf(peerA)),
                    FakeStorage(),
                    AgreeableHasher(metainfo),
                    timeSource = testScheduler.timeSource,
                )
            val job = session.start(kotlinx.coroutines.CoroutineScope(coroutineContext + handler))
            testScheduler.runCurrent()

            // Dials at 0, 30 and 90 seconds, all refused: the address now waits 240.
            testScheduler.advanceTimeBy(100 * 1_000L)
            testScheduler.runCurrent()
            assertEquals(3, dialer.dialled.size, "three refusals before the peer opens its port")

            refuse.clear()
            testScheduler.advanceTimeBy(120 * 1_000L)
            testScheduler.runCurrent()
            assertEquals(1, session.state.value.connectedPeers, "the fourth dial, at 210 seconds, is answered")

            val connection = dialer.connections.getValue(peerA)
            connection.incoming.close()
            testScheduler.runCurrent()
            assertEquals(0, session.state.value.connectedPeers)
            val afterHandshake = dialer.dialled.size

            // Thirty seconds, not the two hundred and forty the streak would have asked for.
            testScheduler.advanceTimeBy(31 * 1_000L)
            testScheduler.runCurrent()
            assertEquals(afterHandshake + 1, dialer.dialled.size, "the flat delay, because it answered once")
            job.cancelAndJoin()
        }
}
