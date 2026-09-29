package io.github.youndie.kachok.engine.metainfo

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.PeerId
import io.github.youndie.kachok.engine.PieceIndex
import io.github.youndie.kachok.engine.bencode.BDictionary
import io.github.youndie.kachok.engine.bencode.BInteger
import io.github.youndie.kachok.engine.bencode.BString
import io.github.youndie.kachok.engine.bencode.Bencode
import io.github.youndie.kachok.engine.peer.PeerAddress
import io.github.youndie.kachok.engine.peer.PeerConnection
import io.github.youndie.kachok.engine.peer.PeerDialer
import io.github.youndie.kachok.engine.peer.PeerEvent
import io.github.youndie.kachok.engine.tracker.AnnounceRequest
import io.github.youndie.kachok.engine.tracker.AnnounceResponse
import io.github.youndie.kachok.engine.tracker.TrackerClient
import io.github.youndie.kachok.engine.wire.ExtensionHandshake
import io.github.youndie.kachok.engine.wire.Handshake
import io.github.youndie.kachok.engine.wire.Message
import io.github.youndie.kachok.engine.wire.MetadataMessage
import io.github.youndie.kachok.engine.wire.PeerWire
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The acceptance criterion of B-36: a magnet and a peer that has the metadata produce the same
 * `Metainfo` the `.torrent` file would have.
 */
class MetadataFetcherTest {
    private companion object {
        const val PIECES = 1000
    }

    /** Enough pieces that the metadata is more than one 16 KiB block. */
    private val pieces = PIECES

    private val ourId = PeerId("-KA0001-0123456789AB".encodeToByteArray())
    private val peerA = PeerAddress("10.0.0.1", 6881)

    /** The torrent this test is about, as a file would carry it. */
    private val infoDictionary: ByteArray =
        Bencode.encode(
            BDictionary(
                mapOf(
                    // The length has to agree with the number of piece hashes below, or the
                    // parser refuses the dictionary — which it did, on the first run of this test.
                    BString("length") to BInteger(PIECES.toLong() * PeerWire.BLOCK_SIZE),
                    BString("name") to BString("fixture.bin"),
                    BString("piece length") to BInteger(PeerWire.BLOCK_SIZE.toLong()),
                    // Large enough that the metadata is more than one 16 KiB block: a fetcher that
                    // asked for block 0 and stopped would pass a one-block fixture.
                    BString("pieces") to BString(ByteArray(PIECES * Metainfo.HASH_SIZE) { it.toByte() }),
                ),
            ),
        )

    private val fromFile: Metainfo =
        MetainfoParser.parse(
            Bencode.encode(
                BDictionary(
                    mapOf(
                        BString("announce") to BString("http://tracker.example/annc"),
                        BString("info") to Bencode.decode(infoDictionary),
                    ),
                ),
            ),
        )

    private fun magnet(trackers: List<String> = listOf("http://tracker.example/annc")) =
        MagnetLink(fromFile.infoHash, displayName = "fixture.bin", trackers = trackers)

    /**
     * A peer that has the metadata and serves it, or misbehaves on request.
     *
     * Everything below the extension protocol is absent — this conversation never gets as far as a
     * bitfield — which is exactly what makes a separate fetcher the right shape.
     */
    private class MetadataPeer(
        override val address: PeerAddress,
        infoHash: InfoHash,
        private val metadata: ByteArray?,
        private val advertisedSize: Int? = metadata?.size,
        private val rejectEverything: Boolean = false,
        private val corruptFirstBlock: Boolean = false,
    ) : PeerConnection {
        private val incoming = Channel<PeerEvent>(Channel.UNLIMITED)
        val requested: MutableList<Int> = mutableListOf()

        override val handshake: Handshake =
            Handshake(
                infoHash,
                PeerId("-FAKE01-000000000000".encodeToByteArray()),
                Handshake.reservedBits(extensionProtocol = true),
            )

        override val events: ReceiveChannel<PeerEvent> get() = incoming

        override suspend fun send(message: Message) {
            val extended = message as? Message.Extended ?: return
            if (extended.extensionId == ExtensionHandshake.HANDSHAKE_ID) {
                val theirs =
                    ExtensionHandshake(
                        extensions =
                            if (metadata !=
                                null
                            ) {
                                mapOf(ExtensionHandshake.UT_METADATA to OUR_ID)
                            } else {
                                emptyMap()
                            },
                        metadataSize = advertisedSize,
                    )
                incoming.send(
                    PeerEvent.Received(Message.Extended(ExtensionHandshake.HANDSHAKE_ID, theirs.encode())),
                )
                return
            }
            val request = MetadataMessage.decode(extended.payload)
            if (request.type != MetadataMessage.REQUEST) return
            requested += request.piece
            val bytes = metadata ?: return
            if (rejectEverything) {
                incoming.send(reply(MetadataMessage.reject(request.piece)))
                return
            }
            val from = request.piece * MetadataMessage.BLOCK_SIZE
            val to = minOf(from + MetadataMessage.BLOCK_SIZE, bytes.size)
            var block = bytes.copyOfRange(from, to)
            if (corruptFirstBlock && request.piece == 0) block = ByteArray(block.size) { 0x42 }
            incoming.send(reply(MetadataMessage.data(request.piece, bytes.size, block)))
        }

        private fun reply(message: MetadataMessage): PeerEvent =
            PeerEvent.Received(Message.Extended(FETCHER_ID, message.encode()))

        override suspend fun sendBlock(
            piece: PieceIndex,
            begin: Int,
            length: Int,
        ) = Unit

        override val uploaded: Long = 0

        override fun close() {
            incoming.close()
        }

        private companion object {
            /** The id *this peer* publishes, which is not the one the fetcher publishes. */
            const val OUR_ID = 9

            /** The id the fetcher published, and the only one it will read. */
            const val FETCHER_ID = MetadataFetcher.METADATA_ID
        }
    }

    private class OneTracker(
        private val peers: List<PeerAddress>,
    ) : TrackerClient {
        var left: Long = -1
            private set

        override suspend fun announce(
            tracker: String,
            request: AnnounceRequest,
        ): AnnounceResponse {
            left = request.left
            return AnnounceResponse(interval = 1800, peers = peers)
        }
    }

    private class OneDialer(
        private val connection: PeerConnection,
    ) : PeerDialer {
        override suspend fun connect(address: PeerAddress): PeerConnection = connection
    }

    @Test
    fun aMagnetAndAPeerWithTheMetadataProduceTheTorrentTheFileWouldHave() =
        runTest {
            val peer = MetadataPeer(peerA, fromFile.infoHash, infoDictionary)
            val tracker = OneTracker(listOf(peerA))
            val fetcher = MetadataFetcher(magnet(), ourId, 6881, OneDialer(peer), tracker)

            val fetched = fetcher.fetch(this)

            assertTrue(fetched.infoHash.bytes.contentEquals(fromFile.infoHash.bytes), "the info hash")
            assertEquals(fromFile.name, fetched.name)
            assertEquals(fromFile.pieceLength, fetched.pieceLength)
            assertEquals(fromFile.totalLength, fetched.totalLength)
            assertEquals(fromFile.pieceCount, fetched.pieceCount)
            assertTrue(fetched.pieceHashes.contentEquals(fromFile.pieceHashes), "the piece hashes")
            assertEquals(fromFile.trackers, fetched.trackers, "the magnet's trackers, not the file's")
            assertTrue(
                peer.requested.size > 1,
                "the metadata is ${infoDictionary.size} bytes and was fetched in one request",
            )
        }

    @Test
    fun theTrackerIsToldANonZeroLeftBecauseThisClientIsNotASeed() =
        runTest {
            // `left` is the torrent's length, which is in the metadata being fetched. Zero would
            // announce this client as a seed and get leechers back.
            val tracker = OneTracker(listOf(peerA))
            MetadataFetcher(
                magnet(),
                ourId,
                6881,
                OneDialer(MetadataPeer(peerA, fromFile.infoHash, infoDictionary)),
                tracker,
            ).fetch(this)

            assertTrue(tracker.left > 0, "the tracker was told `left=${tracker.left}`")
        }

    @Test
    fun metadataThatDoesNotHashToTheMagnetsInfoHashIsThrownAwayWhole() =
        runTest {
            // Everything here came from a stranger who was asked by identifier. The hash is the
            // only thing that makes parsing it safe, so it is checked before anything looks at it.
            val peer = MetadataPeer(peerA, fromFile.infoHash, infoDictionary, corruptFirstBlock = true)
            val fetcher = MetadataFetcher(magnet(), ourId, 6881, OneDialer(peer), OneTracker(listOf(peerA)))

            val thrown = assertFailsWith<MetainfoException> { fetcher.fetch(this) }

            assertContains(thrown.message ?: "", "SHA-1")
        }

    @Test
    fun aPeerThatRefusesEveryBlockEndsAsATimeoutAndNotAsAHang() =
        runTest {
            val peer = MetadataPeer(peerA, fromFile.infoHash, infoDictionary, rejectEverything = true)
            val fetcher =
                MetadataFetcher(
                    magnet(),
                    ourId,
                    6881,
                    OneDialer(peer),
                    OneTracker(listOf(peerA)),
                    timeout = kotlin.time.Duration.parse("5s"),
                )

            val thrown = assertFailsWith<MetainfoException> { fetcher.fetch(this) }

            assertContains(thrown.message ?: "", "answered")
        }

    @Test
    fun aPeerClaimingAnAbsurdMetadataSizeIsNotBelieved() =
        runTest {
            // The size arrives in a handshake from a stranger and is what gets allocated.
            val peer =
                MetadataPeer(
                    peerA,
                    fromFile.infoHash,
                    infoDictionary,
                    advertisedSize = Int.MAX_VALUE,
                )
            val fetcher =
                MetadataFetcher(
                    magnet(),
                    ourId,
                    6881,
                    OneDialer(peer),
                    OneTracker(listOf(peerA)),
                    timeout = kotlin.time.Duration.parse("5s"),
                )

            assertFailsWith<MetainfoException> { fetcher.fetch(this) }
            assertTrue(peer.requested.isEmpty(), "a block was asked for from a peer that was not believed")
        }

    @Test
    fun noPeersIsItsOwnFailureAndSaysSo() =
        runTest {
            val fetcher =
                MetadataFetcher(
                    magnet(),
                    ourId,
                    6881,
                    OneDialer(MetadataPeer(peerA, fromFile.infoHash, null)),
                    OneTracker(emptyList()),
                )

            val thrown = assertFailsWith<MetainfoException> { fetcher.fetch(this) }

            assertContains(thrown.message ?: "", "no peers")
        }

    /** Answers per tracker URL; a tracker it does not know fails the way a dead one does. */
    private class Trackers(
        private val byUrl: Map<String, List<PeerAddress>>,
    ) : TrackerClient {
        val asked: MutableList<String> = mutableListOf()

        override suspend fun announce(
            tracker: String,
            request: AnnounceRequest,
        ): AnnounceResponse {
            asked += tracker
            val peers =
                byUrl[tracker] ?: throw io.github.youndie.kachok.engine.tracker
                    .TrackerException("no answer")
            return AnnounceResponse(interval = 1800, peers = peers)
        }
    }

    /** Only [live] answers; every other address is a dial that times out, as a peer behind NAT does. */
    private class MostlyDeadDialer(
        private val live: PeerAddress,
        private val connection: PeerConnection,
    ) : PeerDialer {
        val dialled: MutableList<PeerAddress> = mutableListOf()

        override suspend fun connect(address: PeerAddress): PeerConnection {
            dialled += address
            if (address == live) return connection
            throw IllegalStateException("$address: Connect timed out")
        }
    }

    private fun dead(count: Int) = (1..count).map { PeerAddress("10.1.0.$it", 6881) }

    /**
     * The case that was reported: the tracker's first peers are all unreachable and the one that
     * has the metadata is further down. The first version dialled twenty once and gave up
     * ([B-135](../../../../../../../../docs/backlog/B-135-the-magnet-fetch-gives-up-too-early.md)).
     */
    @Test
    fun aLivePeerBehindTwentyFiveDeadOnesIsStillReached(): Unit =
        runTest {
            val live = PeerAddress("10.0.0.9", 6881)
            val dialer = MostlyDeadDialer(live, MetadataPeer(live, fromFile.infoHash, infoDictionary))
            val fetcher =
                MetadataFetcher(magnet(), ourId, 6881, dialer, OneTracker(dead(25) + live), maxPeers = 20)

            val fetched = fetcher.fetch(this)

            assertEquals(fromFile.name, fetched.name)
            assertTrue(live in dialer.dialled, "the live peer was never dialled")
        }

    /** Every tracker is asked, not only the first that answers. */
    @Test
    fun aPeerOnlyTheSecondTrackerKnowsIsReached(): Unit =
        runTest {
            val live = PeerAddress("10.0.0.9", 6881)
            val trackers =
                Trackers(mapOf("http://first.example/annc" to dead(3), "http://second.example/annc" to listOf(live)))
            val fetcher =
                MetadataFetcher(
                    magnet(listOf("http://first.example/annc", "http://second.example/annc")),
                    ourId,
                    6881,
                    MostlyDeadDialer(live, MetadataPeer(live, fromFile.infoHash, infoDictionary)),
                    trackers,
                )

            assertEquals(fromFile.name, fetcher.fetch(this).name)
            assertTrue("http://second.example/annc" in trackers.asked, "the second tracker was never asked")
        }

    /** A magnet whose trackers know nobody is found through the DHT. */
    @Test
    fun theDhtIsAskedAsWell(): Unit =
        runTest {
            val live = PeerAddress("10.0.0.9", 6881)
            val fetcher =
                MetadataFetcher(
                    magnet(),
                    ourId,
                    6881,
                    MostlyDeadDialer(live, MetadataPeer(live, fromFile.infoHash, infoDictionary)),
                    OneTracker(emptyList()),
                    dhtPeers = { listOf(live) },
                )

            assertEquals(fromFile.name, fetcher.fetch(this).name)
        }

    /** When nothing works the message says what was tried, not a count of what a tracker returned. */
    @Test
    fun theFailureSaysWhatWasTried(): Unit =
        runTest {
            val fetcher =
                MetadataFetcher(
                    magnet(listOf("http://first.example/annc", "http://gone.example/annc")),
                    ourId,
                    6881,
                    MostlyDeadDialer(PeerAddress("10.0.0.9", 6881), MetadataPeer(peerA, fromFile.infoHash, null)),
                    Trackers(mapOf("http://first.example/annc" to dead(30))),
                    maxPeers = 20,
                    timeout = kotlin.time.Duration.parse("5s"),
                )

            val message = assertFailsWith<MetainfoException> { fetcher.fetch(this) }.message.orEmpty()

            assertContains(message, "1 of 2 tracker(s) answered")
            assertContains(message, "30 peer(s) dialled, 30 unreachable")
            assertContains(message, "Connect timed out")
        }

    /**
     * An added tracker stays on a public torrent and is dropped from a private one — which only
     * the metadata can say (BEP 27).
     */
    @Test
    fun anExtraTrackerIsKeptOnlyWhenTheTorrentIsNotPrivate(): Unit =
        runTest {
            val extra = "udp://extra.example:1337/announce"
            val public =
                MetadataFetcher(
                    magnet(),
                    ourId,
                    6881,
                    OneDialer(MetadataPeer(peerA, fromFile.infoHash, infoDictionary)),
                    OneTracker(listOf(peerA)),
                    extraTrackers = listOf(extra),
                ).fetch(this)
            assertTrue(extra in public.trackers, "the extra tracker was not kept on a public torrent")

            val privateInfo =
                Bencode.encode(
                    BDictionary(
                        (Bencode.decode(infoDictionary) as BDictionary).entries + (BString("private") to BInteger(1)),
                    ),
                )
            val privateHash =
                MetainfoParser
                    .parse(
                        Bencode.encode(
                            BDictionary(
                                mapOf(
                                    BString("announce") to BString("http://tracker.example/annc"),
                                    BString("info") to Bencode.decode(privateInfo),
                                ),
                            ),
                        ),
                    ).infoHash
            val private =
                MetadataFetcher(
                    MagnetLink(
                        privateHash,
                        displayName = "fixture.bin",
                        trackers = listOf("http://tracker.example/annc"),
                    ),
                    ourId,
                    6881,
                    OneDialer(MetadataPeer(peerA, privateHash, privateInfo)),
                    OneTracker(listOf(peerA)),
                    extraTrackers = listOf(extra),
                ).fetch(this)
            assertTrue(private.isPrivate)
            assertEquals(
                listOf("http://tracker.example/annc"),
                private.trackers,
                "a private torrent kept the extra tracker",
            )
        }

    @Test
    fun theMetadataIsBigEnoughForTheTestToMeanSomething() {
        assertTrue(
            infoDictionary.size > MetadataMessage.BLOCK_SIZE,
            "a one-block fixture would let a fetcher that asked for block 0 and stopped pass",
        )
        assertEquals(pieces, fromFile.pieceCount)
    }

    @Test
    fun theAssemblyRefusesABlockOfTheWrongLength() {
        val assembly = MetadataAssembly(fromFile.infoHash, totalSize = MetadataMessage.BLOCK_SIZE * 2 + 10)

        assertTrue(!assembly.accept(0, ByteArray(10)), "block 0 is a full block or it is not block 0")
        assertTrue(assembly.accept(0, ByteArray(MetadataMessage.BLOCK_SIZE)))
        assertTrue(!assembly.accept(0, ByteArray(MetadataMessage.BLOCK_SIZE)), "a block arriving twice")
        assertTrue(!assembly.accept(2, ByteArray(MetadataMessage.BLOCK_SIZE)), "the last block is short")
        assertTrue(assembly.accept(2, ByteArray(10)))
        assertEquals(listOf(1), assembly.missing())
    }
}
