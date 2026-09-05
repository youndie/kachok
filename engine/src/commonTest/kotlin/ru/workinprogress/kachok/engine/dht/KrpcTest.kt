package ru.workinprogress.kachok.engine.dht

import ru.workinprogress.kachok.engine.InfoHash
import ru.workinprogress.kachok.engine.bencode.BDictionary
import ru.workinprogress.kachok.engine.bencode.BInteger
import ru.workinprogress.kachok.engine.bencode.BString
import ru.workinprogress.kachok.engine.bencode.Bencode
import ru.workinprogress.kachok.engine.peer.PeerAddress
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** KRPC's three shapes and BEP 5's four queries, on the bencode this project already had. */
class KrpcTest {
    private val self = NodeId(ByteArray(NodeId.SIZE) { it.toByte() })
    private val target = NodeId(ByteArray(NodeId.SIZE) { (255 - it).toByte() })
    private val infoHash = InfoHash(ByteArray(InfoHash.SIZE) { (it * 3).toByte() })

    @Test
    fun aQueryRoundTripsWithItsTransactionId() {
        // The transaction id is the whole of the multiplexing: one socket, many outstanding
        // questions, and `t` is what says which answer belongs to which.
        val query = KrpcMessage.Query(byteArrayOf(0x61, 0x62), Krpc.PING, Krpc.ping(self))

        val read = Krpc.decode(Krpc.encode(query)) as KrpcMessage.Query

        assertEquals(Krpc.PING, read.method)
        assertTrue(read.transactionId.contentEquals(byteArrayOf(0x61, 0x62)))
        assertEquals(self, read.id)
    }

    @Test
    fun aResponseAndAnErrorAreToldApartByTheirKind() {
        val response = KrpcMessage.Response(byteArrayOf(1), BDictionary(mapOf(BString("id") to BString(self.bytes))))
        val error = KrpcMessage.Error(byteArrayOf(1), 201, "Generic Error")

        assertTrue(Krpc.decode(Krpc.encode(response)) is KrpcMessage.Response)
        val read = Krpc.decode(Krpc.encode(error)) as KrpcMessage.Error
        assertEquals(201, read.code)
        assertEquals("Generic Error", read.message)
    }

    @Test
    fun theQueriesCarryTheArgumentsBep5Names() {
        assertEquals(self.bytes.toList(), (Krpc.ping(self)["id"] as BString).bytes.toList())
        assertEquals(target.bytes.toList(), (Krpc.findNode(self, target)["target"] as BString).bytes.toList())
        assertEquals(infoHash.bytes.toList(), (Krpc.getPeers(self, infoHash)["info_hash"] as BString).bytes.toList())

        val announce = Krpc.announcePeer(self, infoHash, port = 6881, token = byteArrayOf(9, 9))
        assertEquals(6881L, (announce["port"] as BInteger).value)
        assertEquals(listOf<Byte>(9, 9), (announce["token"] as BString).bytes.toList())
        assertEquals(
            1L,
            (announce["implied_port"] as BInteger).value,
            "behind NAT the port a client thinks it listens on is not the one the world reaches it at",
        )
    }

    @Test
    fun compactNodesAreTwentySixBytesEach() {
        val nodes =
            listOf(
                DhtNode(self, PeerAddress("10.0.0.1", 6881)),
                DhtNode(target, PeerAddress("192.168.0.9", 51413)),
            )

        val encoded = Krpc.encodeNodes(nodes)

        assertEquals(2 * Krpc.COMPACT_NODE_SIZE, encoded.size)
        val read = Krpc.decodeNodes(encoded)
        assertEquals(listOf(self, target), read.map { it.id })
        assertEquals(
            listOf("10.0.0.1" to 6881, "192.168.0.9" to 51413),
            read.map { it.address.host to it.address.port },
        )
    }

    @Test
    fun aTrailingFragmentOfANodeIsDroppedRatherThanReadPast() {
        val encoded = Krpc.encodeNodes(listOf(DhtNode(self, PeerAddress("10.0.0.1", 6881))))

        assertTrue(Krpc.decodeNodes(encoded.copyOfRange(0, encoded.size - 1)).isEmpty(), "half a node is not a node")
    }

    @Test
    fun valuesIsAListOfStringsAndNotOneStringOfPeers() {
        // The trap for anyone who has just written the tracker's `peers`, which is the other shape.
        val peers = listOf(PeerAddress("10.0.0.1", 6881), PeerAddress("10.0.0.2", 6882))

        val encoded = Krpc.encodeValues(peers)

        assertEquals(2, encoded.items.size, "one string per peer")
        assertEquals(
            peers.map { it.host to it.port },
            Krpc.decodeValues(encoded).map { it.host to it.port },
        )
        assertTrue(
            Krpc.decodeValues(BString("not a list")).isEmpty(),
            "the tracker's shape is not this one and is read as nothing rather than as garbage",
        )
    }

    @Test
    fun somethingThatIsNotKrpcIsRefused() {
        listOf(
            "nonsense".encodeToByteArray() to "bencode",
            Bencode.encode(BInteger(3)) to "dictionary",
            Bencode.encode(BDictionary(mapOf(BString("y") to BString("q")))) to "`t`",
            Bencode.encode(BDictionary(mapOf(BString("t") to BString("aa")))) to "not one of q, r, e",
        ).forEach { (packet, reason) ->
            val thrown = assertFailsWith<KrpcException>("$reason should be refused") { Krpc.decode(packet) }
            assertContains(thrown.message ?: "", reason)
        }
    }

    @Test
    fun anIdOfTheWrongLengthIsAbsentRatherThanWrong() {
        val query =
            KrpcMessage.Query(
                byteArrayOf(1),
                Krpc.PING,
                BDictionary(mapOf(BString("id") to BString("short"))),
            )

        assertNull((Krpc.decode(Krpc.encode(query)) as KrpcMessage.Query).id)
    }
}
