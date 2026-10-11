package io.github.youndie.kachok.engine.dht

import io.github.youndie.kachok.engine.bencode.BDictionary
import io.github.youndie.kachok.engine.bencode.BInteger
import io.github.youndie.kachok.engine.bencode.BString
import io.github.youndie.kachok.engine.bencode.BValue
import io.github.youndie.kachok.engine.peer.PeerAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource

/**
 * The four answers a DHT node owes (B-142): the queries a real node sends, and what BEP 5 says
 * comes back.
 */
class ResponderTest {
    private fun id(vararg leading: Int): NodeId =
        NodeId(ByteArray(NodeId.SIZE) { if (it < leading.size) leading[it].toByte() else 0 })

    private val self = id(0x01)
    private val asker = id(0x99)
    private val from = PeerAddress("203.0.113.7", 51413)
    private val hash = id(0xF0, 0x0D)
    private val clock = TestTimeSource()
    private val table = RoutingTable(self)
    private val responder = Responder(self, table, clock)
    private val t = byteArrayOf(7, 9)

    private fun query(
        method: String,
        vararg arguments: Pair<String, BValue>,
    ) = KrpcMessage.Query(
        t,
        method,
        BDictionary((listOf("id" to BString(asker.bytes)) + arguments).associate { (k, v) -> BString(k) to v }),
    )

    private fun ask(
        method: String,
        vararg arguments: Pair<String, BValue>,
        by: PeerAddress = from,
    ): KrpcMessage = responder.answer(query(method, *arguments), by)

    private fun tokenFor(by: PeerAddress = from): ByteArray {
        val reply = assertIs<KrpcMessage.Response>(ask(Krpc.GET_PEERS, "info_hash" to BString(hash.bytes), by = by))
        return assertNotNull((reply.values["token"] as? BString)?.bytes, "get_peers gave no token")
    }

    private fun announce(
        token: ByteArray,
        by: PeerAddress = from,
        port: Long = 6881,
        implied: Long = 0,
    ) = ask(
        Krpc.ANNOUNCE_PEER,
        "info_hash" to BString(hash.bytes),
        "port" to BInteger(port),
        "implied_port" to BInteger(implied),
        "token" to BString(token),
        by = by,
    )

    private fun peers(): List<PeerAddress> {
        val reply = assertIs<KrpcMessage.Response>(ask(Krpc.GET_PEERS, "info_hash" to BString(hash.bytes)))
        return Krpc.decodeValues(reply.values["values"])
    }

    @Test
    fun aPingIsAnsweredWithThisNodesIdAndTheAskersTransaction() {
        val reply = assertIs<KrpcMessage.Response>(ask(Krpc.PING))
        assertEquals(self, reply.id)
        assertContentEquals(t, reply.transactionId)
    }

    @Test
    fun findNodeAnswersWithTheClosestNodesInTheTable() {
        val near = DhtNode(id(0xF0, 0x0C), PeerAddress("198.51.100.1", 1))
        val far = DhtNode(id(0x0F), PeerAddress("198.51.100.2", 2))
        table.seen(near)
        table.seen(far)

        val reply = assertIs<KrpcMessage.Response>(ask(Krpc.FIND_NODE, "target" to BString(hash.bytes)))

        val nodes = Krpc.decodeNodes((reply.values["nodes"] as BString).bytes)
        assertEquals(listOf(near.id, far.id), nodes.map { it.id }, "closest first")
    }

    /** The round trip a peer makes: get_peers for the token, announce_peer with it, and found. */
    @Test
    fun anAnnouncedPeerIsHandedToTheNextAsker() {
        assertTrue(peers().isEmpty())

        assertIs<KrpcMessage.Response>(announce(tokenFor()))

        assertEquals(listOf(PeerAddress(from.host, 6881)), peers())
    }

    /** BEP 5's `implied_port`: the port the datagram came from, which is the one behind the NAT. */
    @Test
    fun anImpliedPortIsTheOneTheQueryCameFrom() {
        assertIs<KrpcMessage.Response>(announce(tokenFor(), port = 1, implied = 1))
        assertEquals(listOf(from), peers())
    }

    @Test
    fun aTokenGivenToAnotherAddressIsRefused() {
        val stranger = PeerAddress("192.0.2.200", 6881)
        val refused = assertIs<KrpcMessage.Error>(announce(tokenFor(), by = stranger))
        assertEquals(Responder.PROTOCOL_ERROR, refused.code)
        assertTrue(peers().isEmpty())
    }

    /** Good for the rotation it was given in and the next; not for the one after. */
    @Test
    fun aTokenOutlivesOneRotationAndNotTwo() {
        val token = tokenFor()
        clock += Responder.TOKEN_ROTATION
        assertIs<KrpcMessage.Response>(announce(token), "a token one rotation old was refused")
        clock += Responder.TOKEN_ROTATION
        assertIs<KrpcMessage.Error>(announce(token), "a token two rotations old was accepted")
    }

    @Test
    fun anAnnouncedPeerIsForgottenAfterHalfAnHour() {
        announce(tokenFor())
        clock += Responder.PEER_LIFETIME
        assertTrue(peers().isEmpty())
    }

    /**
     * A stranger cannot make this node hold more than the cap for one torrent: past it the oldest
     * goes, and an answer carries the newest that fit in one datagram.
     */
    @Test
    fun oneTorrentHoldsNoMoreThanItsCapAndAnswersWithTheNewest() {
        val announcers =
            (1..Responder.MAX_PEERS_PER_TORRENT + 20).map {
                PeerAddress(
                    "10.0.${it / 250}.${it % 250}",
                    6881,
                )
            }
        announcers.forEach { by -> announce(tokenFor(by), by = by) }

        val handed = peers()

        assertEquals(Responder.MAX_VALUES, handed.size)
        assertTrue(announcers.last() in handed, "the newest announcer is not in the answer")
        assertTrue(announcers.first() !in handed)
    }

    @Test
    fun anUnknownMethodAndAMissingIdAreRefusedWithBep5sCodes() {
        assertEquals(Responder.METHOD_UNKNOWN, assertIs<KrpcMessage.Error>(ask("vote")).code)
        val anonymous = KrpcMessage.Query(t, Krpc.PING, BDictionary(emptyMap()))
        assertEquals(Responder.PROTOCOL_ERROR, assertIs<KrpcMessage.Error>(responder.answer(anonymous, from)).code)
        assertNull(table.all().firstOrNull(), "a query is not a reason to put its sender in the table")
    }
}
