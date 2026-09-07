package io.github.youndie.kachok.engine.metainfo

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
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
