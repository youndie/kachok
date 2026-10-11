package io.github.youndie.kachok.engine.dht

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.bencode.BString
import io.github.youndie.kachok.engine.peer.PeerAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

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
        arguments: io.github.youndie.kachok.engine.bencode.BDictionary,
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
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    /**
     * When this node last started a walk towards its own id, or null before it has.
     *
     * Set when the walk starts and not when it ends, so that the torrents of one set starting
     * together walk once between them rather than once each. Volatile and unguarded: two that
     * read it in the same instant cost one walk's worth of datagrams, which is less than a lock
     * would cost to reason about.
     */
    @Volatile
    private var refreshed: TimeMark? = null

    /**
     * Ask some known nodes who they know, until the table has some of them.
     *
     * Bootstrap nodes are hosts, not ids: the answer to `find_node` is what gives them ids, so the
     * first query goes to an address this client cannot yet place in a bucket.
     *
     * This is enough for a first lookup and not enough to be a node: [refresh] is what fills the
     * table, and is the caller's to run beside the lookup rather than before it.
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
     * Kademlia's iterative `find_node` towards this node's own id: what fills the buckets near it.
     *
     * **This walk used to be missing, and the table stalled without it**
     * ([B-141](../../../../../../../../docs/backlog/B-141-the-dht-table-fills-and-a-resume-looks-at-once.md)).
     * The bootstrap hosts name a handful of nodes, and lookups for info hashes only ever meet
     * nodes far from this one's id — they land in the first few buckets, eight each, and the rest
     * stay empty. On the public network the table stood at 34 nodes after three lookups; one walk
     * towards its own id took it to 105. BEP 5 has a joining node do exactly this.
     *
     * It takes as long as its slowest round — forty seconds, once, on the public network, against
     * five for the bootstrap — which is why it does not stand between a torrent and its first
     * lookup.
     *
     * Ends the way [lookup] does: when the [RoutingTable.BUCKET_SIZE] closest nodes known have
     * all been asked, or after [rounds].
     */
    public suspend fun refresh(scope: CoroutineScope): Int {
        refreshed = timeSource.markNow()
        val order = closestTo(self)
        val known = table.closest(self).toMutableList()
        val asked = mutableSetOf<PeerAddress>()
        var round = 0
        while (round++ < rounds) {
            val batch = nextBatch(known, order, asked)
            if (batch.isEmpty()) break
            batch.forEach { asked += it.address }
            batch
                .map { node ->
                    scope.async { node to transport.query(node.address, Krpc.FIND_NODE, Krpc.findNode(self, self)) }
                }.awaitAll()
                .forEach { (node, response) ->
                    if (response == null) {
                        table.failed(node.id)
                        known.removeAll { it.id == node.id }
                    } else {
                        table.seen(node)
                        learn(response).forEach { learned -> if (known.none { it.id == learned.id }) known += learned }
                    }
                }
        }
        return table.size
    }

    /**
     * [refresh] again if the last one is older than [REFRESH_INTERVAL]: BEP 5's fifteen minutes,
     * after which a node it met may have left and a bucket gone quiet.
     */
    public suspend fun refreshIfStale(scope: CoroutineScope) {
        val last = refreshed
        if (last == null || last.elapsedNow() >= REFRESH_INTERVAL) refresh(scope)
    }

    /**
     * Kademlia's iterative `get_peers`.
     *
     * Each round asks the [alpha] closest nodes not yet asked. The loop ends when the
     * [RoutingTable.BUCKET_SIZE] closest nodes known have all been asked — Kademlia's own rule,
     * and the one that reaches the nodes an announce has to go to — or after [rounds]. That bound
     * is not the algorithm's, it is this client's patience, because a lookup against a hostile or
     * broken network can otherwise walk for ever.
     *
     * **It used to end "when a round learns nothing closer and there are peers"**, and never did:
     * the exit was a `return@repeat`, which inside `repeat` is a `continue`, so every lookup ran
     * all its rounds — sixteen to twenty seconds on the public network. Making that exit work was
     * tried and measured: it ended the lookup before it reached the closest nodes, with 120 peers
     * and 12 tokens where the full walk had 172 and 19. The closest-eight rule ends as early and
     * does not stop short (B-141).
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

        var round = 0
        while (round++ < rounds) {
            val batch = nextBatch(known, order, asked)
            if (batch.isEmpty()) break
            batch.forEach { asked += it.address }

            val answers =
                batch
                    .map { node ->
                        scope.async {
                            node to
                                transport.query(node.address, Krpc.GET_PEERS, Krpc.getPeers(self, infoHash))
                        }
                    }.awaitAll()

            answers.forEach { (node, response) ->
                if (response == null) {
                    table.failed(node.id)
                    known.removeAll { it.id == node.id }
                    return@forEach
                }
                table.seen(node)
                peers += Krpc.decodeValues(response.values["values"])
                (response.values["token"] as? BString)?.let { tokens += node to it.bytes }
                learn(response).forEach { learned -> if (known.none { it.id == learned.id }) known += learned }
            }
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

    /**
     * The next [alpha] to ask: the closest not yet asked, from among the
     * [RoutingTable.BUCKET_SIZE] closest known. Empty once those have all been asked, which is
     * where a Kademlia walk ends. A node that did not answer is taken out of [known] by the
     * caller, so it does not hold a place among the closest.
     */
    private fun nextBatch(
        known: List<DhtNode>,
        order: Comparator<DhtNode>,
        asked: Set<PeerAddress>,
    ): List<DhtNode> =
        known
            .sortedWith(order)
            .take(RoutingTable.BUCKET_SIZE)
            .filter { it.address !in asked }
            .take(alpha)

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

        /** BEP 5's: a bucket untouched for fifteen minutes is refreshed. */
        public val REFRESH_INTERVAL: Duration = 15.minutes
    }
}
