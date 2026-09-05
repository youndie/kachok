package ru.workinprogress.kachok.engine.dht

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import ru.workinprogress.kachok.engine.InfoHash
import ru.workinprogress.kachok.engine.bencode.BString
import ru.workinprogress.kachok.engine.peer.PeerAddress

/**
 * One KRPC exchange with one node.
 *
 * An interface for the same reason the tracker's is: the datagram socket is the platform's, and
 * everything above it — the routing table, the lookup, the announce — is arithmetic that should be
 * testable against a fake network rather than against the internet.
 *
 * A node that does not answer is a `null`, not an exception: on UDP that is the ordinary case and
 * every caller here treats it as information rather than as a failure.
 */
public interface KrpcTransport {
    public suspend fun query(
        node: PeerAddress,
        method: String,
        arguments: ru.workinprogress.kachok.engine.bencode.BDictionary,
    ): KrpcMessage.Response?
}

/** What a `get_peers` lookup found. */
public class LookupResult(
    public val peers: List<PeerAddress>,
    /** The closest nodes that answered, with the token each gave — what `announce_peer` needs. */
    public val tokens: List<Pair<DhtNode, ByteArray>>,
)

/**
 * The DHT as this client uses it (BEP 5): find the peers for an info hash, and say we are one.
 *
 * The lookup is Kademlia's: ask the closest nodes known, learn closer ones from their answers,
 * repeat until an round brings nothing closer. It converges because every answer is *required* to
 * be closer to the target than the asker — a node that answers with nodes further away is not
 * lying so much as useless, and the loop's termination does not depend on it being honest.
 *
 * **Off for private torrents**, which is BEP 27 and is enforced by the session rather than here:
 * this class does what it is asked.
 */
public class Dht(
    public val self: NodeId,
    private val transport: KrpcTransport,
    public val table: RoutingTable = RoutingTable(self),
    private val alpha: Int = ALPHA,
    private val rounds: Int = MAX_ROUNDS,
) {
    /**
     * Ask some known nodes who they know, until the table has some of them.
     *
     * Bootstrap nodes are hosts, not ids: the answer to `find_node` is what gives them ids, so the
     * first query goes to an address this client cannot yet place in a bucket.
     */
    public suspend fun bootstrap(
        scope: CoroutineScope,
        nodes: List<PeerAddress>,
    ): Int {
        nodes
            .map { address ->
                scope.async {
                    val response = transport.query(address, Krpc.FIND_NODE, Krpc.findNode(self, self))
                    if (response != null) {
                        response.id?.let { table.seen(DhtNode(it, address)) }
                        learn(response)
                    }
                }
            }.awaitAll()
        return table.size
    }

    /**
     * Kademlia's iterative `get_peers`.
     *
     * Each round asks the [alpha] closest nodes not yet asked. The loop ends when a round learns
     * no node closer than the best already known, or after [rounds] — the bound is not the
     * algorithm's, it is this client's patience, because a lookup against a hostile or broken
     * network can otherwise walk for ever.
     */
    public suspend fun lookup(
        scope: CoroutineScope,
        infoHash: InfoHash,
    ): LookupResult {
        val target = NodeId(infoHash.bytes)
        val order = closestTo(target)
        val known = table.closest(target).toMutableList()
        val asked = mutableSetOf<PeerAddress>()
        val peers = LinkedHashSet<PeerAddress>()
        val tokens = mutableListOf<Pair<DhtNode, ByteArray>>()

        repeat(rounds) {
            val batch = known.sortedWith(order).filter { it.address !in asked }.take(alpha)
            if (batch.isEmpty()) return@repeat
            batch.forEach { asked += it.address }

            val answers =
                batch
                    .map { node ->
                        scope.async {
                            node to
                                transport.query(node.address, Krpc.GET_PEERS, Krpc.getPeers(self, infoHash))
                        }
                    }.awaitAll()

            var learnedSomethingCloser = false
            val best = known.minWithOrNull(order)
            answers.forEach { (node, response) ->
                if (response == null) {
                    table.failed(node.id)
                    return@forEach
                }
                table.seen(node)
                peers += Krpc.decodeValues(response.values["values"])
                (response.values["token"] as? BString)?.let { tokens += node to it.bytes }
                learn(response).forEach { learned ->
                    if (known.none { it.id == learned.id }) {
                        known += learned
                        if (best == null || order.compare(learned, best) < 0) learnedSomethingCloser = true
                    }
                }
            }
            if (!learnedSomethingCloser && peers.isNotEmpty()) return@repeat
        }

        return LookupResult(
            peers = peers.toList(),
            tokens = tokens.sortedWith(Comparator { left, right -> order.compare(left.first, right.first) }),
        )
    }

    /**
     * Tell the nodes that answered a lookup that this client has the torrent.
     *
     * Only to nodes that gave a token, and only to the closest [RoutingTable.BUCKET_SIZE] of them:
     * the token is how a node proves the announcer asked it first, so an announce without one is
     * refused, and announcing to everything found is a broadcast nobody asked for.
     */
    public suspend fun announce(
        scope: CoroutineScope,
        infoHash: InfoHash,
        port: Int,
        tokens: List<Pair<DhtNode, ByteArray>>,
    ): Int {
        val accepted =
            tokens
                .take(RoutingTable.BUCKET_SIZE)
                .map { (node, token) ->
                    scope.async {
                        val response =
                            transport.query(
                                node.address,
                                Krpc.ANNOUNCE_PEER,
                                Krpc.announcePeer(self, infoHash, port, token),
                            )
                        if (response == null) table.failed(node.id)
                        response != null
                    }
                }.awaitAll()
        return accepted.count { it }
    }

    /** Puts the `nodes` of a response into the table, and hands them back to the lookup. */
    private fun learn(response: KrpcMessage.Response): List<DhtNode> {
        val nodes = Krpc.decodeNodes((response.values["nodes"] as? BString)?.bytes ?: ByteArray(0))
        nodes.forEach { table.seen(it) }
        return nodes
    }

    public companion object {
        /** Kademlia's α: how many nodes are asked at once. Three is the paper's. */
        public const val ALPHA: Int = 3

        /** This client's patience, not the algorithm's: a lookup against a broken network ends. */
        public const val MAX_ROUNDS: Int = 8
    }
}
