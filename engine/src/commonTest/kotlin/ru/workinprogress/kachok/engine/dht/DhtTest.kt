package ru.workinprogress.kachok.engine.dht

import kotlinx.coroutines.test.runTest
import ru.workinprogress.kachok.engine.InfoHash
import ru.workinprogress.kachok.engine.bencode.BDictionary
import ru.workinprogress.kachok.engine.bencode.BString
import ru.workinprogress.kachok.engine.peer.PeerAddress
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The acceptance criterion of B-35: a lookup across a fake network finds the announced peer.
 *
 * The network below is a ring of nodes that each know only their neighbour's neighbour, so a
 * lookup that did not actually walk — one that asked its starting nodes and stopped — would come
 * back empty. That is the property being tested; a network where every node knows the answer would
 * pass with no algorithm at all.
 */
class DhtTest {
    private fun id(vararg leading: Int): NodeId =
        NodeId(ByteArray(NodeId.SIZE) { if (it < leading.size) leading[it].toByte() else 0 })

    private val infoHash = InfoHash(ByteArray(InfoHash.SIZE) { if (it == 0) 0xF0.toByte() else 0 })
    private val holder = PeerAddress("10.9.9.9", 51413)

    /**
     * A network of nodes that answer `get_peers` truthfully.
     *
     * A node returns `values` only if it is the one holding the torrent; otherwise it returns the
     * nodes it knows that are closer to the target than itself. Anything asked of an address that
     * is not in the network returns null, the way an unanswered datagram does.
     */
    private class FakeNetwork(
        private val nodes: Map<PeerAddress, FakeNode>,
    ) : KrpcTransport {
        val asked: MutableList<Pair<PeerAddress, String>> = mutableListOf()

        override suspend fun query(
            node: PeerAddress,
            method: String,
            arguments: BDictionary,
        ): KrpcMessage.Response? {
            asked += node to method
            return nodes[node]?.answer(method, arguments)
        }
    }

    private class FakeNode(
        val id: NodeId,
        val knows: List<DhtNode>,
        val holds: List<PeerAddress> = emptyList(),
    ) {
        var announced: MutableList<Pair<InfoHash, ByteArray>> = mutableListOf()

        fun answer(
            method: String,
            arguments: BDictionary,
        ): KrpcMessage.Response {
            val fields = LinkedHashMap<BString, ru.workinprogress.kachok.engine.bencode.BValue>()
            fields[BString("id")] = BString(id.bytes)
            when (method) {
                Krpc.GET_PEERS -> {
                    fields[BString("token")] = BString("token-for-$id".encodeToByteArray())
                    if (holds.isNotEmpty()) {
                        fields[BString("values")] = Krpc.encodeValues(holds)
                    } else {
                        fields[BString("nodes")] = BString(Krpc.encodeNodes(knows))
                    }
                }

                Krpc.FIND_NODE -> {
                    fields[BString("nodes")] = BString(Krpc.encodeNodes(knows))
                }

                Krpc.ANNOUNCE_PEER -> {
                    val hash = InfoHash((arguments["info_hash"] as BString).bytes)
                    announced += hash to (arguments["token"] as BString).bytes
                }
            }
            return KrpcMessage.Response(byteArrayOf(1), BDictionary(fields))
        }
    }

    /** Three hops from the bootstrap node to the one holding the torrent. */
    private fun chain(): Pair<FakeNetwork, FakeNode> {
        val far = DhtNode(id(0x0F), PeerAddress("10.0.0.4", 4))
        val near = DhtNode(id(0x70), PeerAddress("10.0.0.3", 3))
        val middle = DhtNode(id(0xC0), PeerAddress("10.0.0.2", 2))
        val entry = DhtNode(id(0xFF), PeerAddress("10.0.0.1", 1))

        // Each knows only the next one along, so the lookup has to walk to reach the holder.
        val holderNode = FakeNode(far.id, knows = emptyList(), holds = listOf(holder))
        val network =
            FakeNetwork(
                mapOf(
                    entry.address to FakeNode(entry.id, knows = listOf(middle)),
                    middle.address to FakeNode(middle.id, knows = listOf(near)),
                    near.address to FakeNode(near.id, knows = listOf(far)),
                    far.address to holderNode,
                ),
            )
        return network to holderNode
    }

    @Test
    fun aLookupWalksTheNetworkAndComesBackWithTheAnnouncedPeer() =
        runTest {
            val (network, _) = chain()
            val dht = Dht(self = id(0x01), transport = network)
            dht.table.seen(DhtNode(id(0xFF), PeerAddress("10.0.0.1", 1)))

            val found = dht.lookup(this, infoHash)

            assertEquals(listOf(holder.host to holder.port), found.peers.map { it.host to it.port })
            assertTrue(
                network.asked.map { it.first.port }.containsAll(listOf(1, 2, 3, 4)),
                "the lookup did not walk; it asked ${network.asked.map { it.first.port }}",
            )
            assertTrue(found.tokens.isNotEmpty(), "a lookup with no token cannot announce afterwards")
        }

    @Test
    fun everyNodeThatAnsweredIsInTheTableAfterwards() =
        runTest {
            val (network, _) = chain()
            val dht = Dht(self = id(0x01), transport = network)
            dht.table.seen(DhtNode(id(0xFF), PeerAddress("10.0.0.1", 1)))

            dht.lookup(this, infoHash)

            assertEquals(4, dht.table.size, "the walk is also how the table fills: ${dht.table.all()}")
        }

    @Test
    fun announcingUsesTheTokenTheNodeGave() =
        runTest {
            // BEP 5: the token is how a node proves the announcer asked it first. An announce
            // carrying somebody else's token, or none, is refused by any real node.
            val (network, holderNode) = chain()
            val dht = Dht(self = id(0x01), transport = network)
            dht.table.seen(DhtNode(id(0xFF), PeerAddress("10.0.0.1", 1)))
            val found = dht.lookup(this, infoHash)

            val accepted = dht.announce(this, infoHash, port = 6881, tokens = found.tokens)

            assertTrue(accepted > 0, "nothing accepted the announce")
            val (hash, token) = holderNode.announced.single()
            assertTrue(hash.bytes.contentEquals(infoHash.bytes))
            assertEquals("token-for-${id(0x0F)}", token.decodeToString(), "the token came from that node")
        }

    @Test
    fun aNodeThatNeverAnswersIsMarkedFailedRatherThanRetriedForever() =
        runTest {
            val dead = DhtNode(id(0x80), PeerAddress("10.0.0.250", 250))
            val network = FakeNetwork(emptyMap())
            val dht = Dht(self = id(0x01), transport = network)
            dht.table.seen(dead)

            val found = dht.lookup(this, infoHash)

            assertTrue(found.peers.isEmpty())
            assertEquals(1, network.asked.size, "a node that did not answer is not asked again in the same lookup")
            assertTrue(dht.table.isGood(dead.id), "one failure is not enough to call a node bad")
        }

    @Test
    fun bootstrappingFillsTheTableFromAddressesWithNoIds() =
        runTest {
            // The first query goes to an address this client cannot yet place in a bucket: the
            // answer is what gives the bootstrap node its id.
            val (network, _) = chain()
            val dht = Dht(self = id(0x01), transport = network)

            val size = dht.bootstrap(this, listOf(PeerAddress("10.0.0.1", 1)))

            assertEquals(2, size, "the bootstrap node itself, and the one it named")
            assertContains(dht.table.all().map { it.id }, id(0xFF))
        }
}
