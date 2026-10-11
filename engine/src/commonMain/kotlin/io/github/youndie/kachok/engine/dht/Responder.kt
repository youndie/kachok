package io.github.youndie.kachok.engine.dht

import io.github.youndie.kachok.engine.bencode.BDictionary
import io.github.youndie.kachok.engine.bencode.BInteger
import io.github.youndie.kachok.engine.bencode.BString
import io.github.youndie.kachok.engine.bencode.BValue
import io.github.youndie.kachok.engine.peer.CompactPeers
import io.github.youndie.kachok.engine.peer.PeerAddress
import io.github.youndie.kachok.engine.platform.sha1
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The other half of being a DHT node: answering the four queries (BEP 5).
 *
 * **It used to be missing**
 * ([B-142](../../../../../../../../docs/backlog/B-142-the-dht-answers.md)). The transport counted
 * the queries other nodes sent and answered none, and BEP 5 drops a node that does not answer:
 * every node that met this one in a lookup pinged it, heard nothing, and evicted it. A client that
 * only asks is a client, not a node anyone keeps.
 *
 * **Peers announced here are held, briefly and in bounded number.** That is the duty a node takes
 * on by giving out tokens, and the bound is what keeps a stranger's `announce_peer` storm from
 * becoming this process's memory: [MAX_TORRENTS] info hashes, [MAX_PEERS_PER_TORRENT] peers each,
 * [PEER_LIFETIME] each, oldest out first.
 *
 * **Tokens are a hash of the asker's address and a secret that rotates**, which is BEP 5's own
 * suggestion: nothing is stored per asker, and a token stays good for one rotation after the one it
 * was given in, so an `announce_peer` that follows its `get_peers` across a rotation is not refused.
 *
 * Not thread safe: the transport's one reader is its only caller.
 */
public class Responder(
    private val self: NodeId,
    private val table: RoutingTable,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val random: Random = Random.Default,
) {
    private var secret: ByteArray = random.nextBytes(SECRET_SIZE)
    private var previousSecret: ByteArray = secret
    private var rotated: TimeMark = timeSource.markNow()

    /** Info hash, as a [NodeId] because that compares by value, to peer and when it announced. */
    private val stored = LinkedHashMap<NodeId, LinkedHashMap<PeerAddress, TimeMark>>()

    /** The answer to [query] from [from], always one: a node that refuses says why. */
    public fun answer(
        query: KrpcMessage.Query,
        from: PeerAddress,
    ): KrpcMessage {
        val t = query.transactionId
        if (query.id == null) return KrpcMessage.Error(t, PROTOCOL_ERROR, "no 20-byte id")
        rotate()
        return when (query.method) {
            Krpc.PING -> {
                reply(t, emptyMap())
            }

            Krpc.FIND_NODE -> {
                val target =
                    query.arguments.nodeId("target") ?: return KrpcMessage.Error(t, PROTOCOL_ERROR, "no target")
                reply(t, mapOf("nodes" to BString(Krpc.encodeNodes(table.closest(target)))))
            }

            Krpc.GET_PEERS -> {
                val hash =
                    query.arguments.nodeId("info_hash") ?: return KrpcMessage.Error(t, PROTOCOL_ERROR, "no info_hash")
                val fields = LinkedHashMap<String, BValue>()
                fields["token"] = BString(token(from.host, secret))
                val peers = peersFor(hash)
                if (peers.isNotEmpty()) fields["values"] = Krpc.encodeValues(peers)
                // Nodes as well as values, as libtorrent sends them: a lookup that reached this node
                // goes on to the closer ones either way, and the cost is two hundred bytes.
                fields["nodes"] = BString(Krpc.encodeNodes(table.closest(hash)))
                reply(t, fields)
            }

            Krpc.ANNOUNCE_PEER -> {
                announce(query, from) ?: reply(t, emptyMap())
            }

            else -> {
                KrpcMessage.Error(t, METHOD_UNKNOWN, "method unknown")
            }
        }
    }

    /** Null when stored; the refusal otherwise. */
    private fun announce(
        query: KrpcMessage.Query,
        from: PeerAddress,
    ): KrpcMessage.Error? {
        val t = query.transactionId
        val hash = query.arguments.nodeId("info_hash") ?: return KrpcMessage.Error(t, PROTOCOL_ERROR, "no info_hash")
        val given =
            (query.arguments["token"] as? BString)?.bytes ?: return KrpcMessage.Error(t, PROTOCOL_ERROR, "no token")
        if (!given.contentEquals(token(from.host, secret)) && !given.contentEquals(token(from.host, previousSecret))) {
            return KrpcMessage.Error(t, PROTOCOL_ERROR, "bad token")
        }
        val implied = (query.arguments["implied_port"] as? BInteger)?.value ?: 0L
        val port = if (implied != 0L) from.port else (query.arguments["port"] as? BInteger)?.value?.toInt()
        if (port == null || port !in 1..MAX_PORT) return KrpcMessage.Error(t, PROTOCOL_ERROR, "no port")
        val peer = PeerAddress(from.host, port)
        // An address the compact form cannot carry could never be handed back out.
        if (!CompactPeers.isPackable(peer.host)) return null
        val peers = stored.getOrPut(hash) { LinkedHashMap() }
        peers.remove(peer)
        peers[peer] = timeSource.markNow()
        if (peers.size > MAX_PEERS_PER_TORRENT) peers.remove(peers.keys.first())
        if (stored.size > MAX_TORRENTS) stored.remove(stored.keys.first())
        return null
    }

    private fun peersFor(hash: NodeId): List<PeerAddress> {
        val peers = stored[hash] ?: return emptyList()
        peers.entries.removeAll { it.value.elapsedNow() >= PEER_LIFETIME }
        if (peers.isEmpty()) stored.remove(hash)
        // The newest: an address that announced a minute ago is likelier to be there than one
        // that announced twenty-nine minutes ago.
        return peers.keys.toList().takeLast(MAX_VALUES)
    }

    private fun rotate() {
        if (rotated.elapsedNow() < TOKEN_ROTATION) return
        previousSecret = secret
        secret = random.nextBytes(SECRET_SIZE)
        rotated = timeSource.markNow()
    }

    private fun token(
        host: String,
        with: ByteArray,
    ): ByteArray {
        val input = host.encodeToByteArray() + with
        return sha1(input, 0, input.size).copyOf(TOKEN_SIZE)
    }

    private fun reply(
        t: ByteArray,
        fields: Map<String, BValue>,
    ): KrpcMessage.Response =
        KrpcMessage.Response(
            t,
            BDictionary(
                buildMap {
                    put(BString("id"), BString(self.bytes))
                    fields.forEach { (name, value) -> put(BString(name), value) }
                },
            ),
        )

    public companion object {
        /** BEP 5's: tokens up to ten minutes old are accepted, which is two rotations of five. */
        public val TOKEN_ROTATION: Duration = 5.minutes

        /** BEP 5 leaves it to the node; thirty minutes is what libtorrent and Transmission keep. */
        public val PEER_LIFETIME: Duration = 30.minutes

        public const val MAX_TORRENTS: Int = 1000
        public const val MAX_PEERS_PER_TORRENT: Int = 100

        /** Fifty compact peers is four hundred bytes: one answer inside one datagram. */
        public const val MAX_VALUES: Int = 50

        /** BEP 5's error codes. */
        public const val PROTOCOL_ERROR: Int = 203
        public const val METHOD_UNKNOWN: Int = 204

        private const val SECRET_SIZE = 16
        private const val TOKEN_SIZE = 8
        private const val MAX_PORT = 65535
    }
}
