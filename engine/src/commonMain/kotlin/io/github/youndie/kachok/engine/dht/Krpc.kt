package io.github.youndie.kachok.engine.dht

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.bencode.BDictionary
import io.github.youndie.kachok.engine.bencode.BInteger
import io.github.youndie.kachok.engine.bencode.BList
import io.github.youndie.kachok.engine.bencode.BString
import io.github.youndie.kachok.engine.bencode.BValue
import io.github.youndie.kachok.engine.bencode.Bencode
import io.github.youndie.kachok.engine.bencode.BencodeException
import io.github.youndie.kachok.engine.peer.CompactPeers
import io.github.youndie.kachok.engine.peer.PeerAddress

/** A datagram this node refuses to read. Nothing is thrown at a caller over one; it is dropped. */
public class KrpcException(
    message: String,
) : IllegalArgumentException(message)

/**
 * KRPC (BEP 5): bencode over UDP, in three shapes.
 *
 * The transaction id is the whole of the multiplexing. One socket carries every query this node
 * has outstanding to every other node, and `t` is what says which answer belongs to which
 * question — so it is bytes, chosen by the asker, and echoed verbatim.
 */
public sealed interface KrpcMessage {
    public val transactionId: ByteArray

    public class Query(
        override val transactionId: ByteArray,
        public val method: String,
        public val arguments: BDictionary,
    ) : KrpcMessage {
        public val id: NodeId? get() = arguments.nodeId("id")

        override fun toString(): String = "Query($method)"
    }

    public class Response(
        override val transactionId: ByteArray,
        public val values: BDictionary,
    ) : KrpcMessage {
        public val id: NodeId? get() = values.nodeId("id")

        override fun toString(): String = "Response(${values.entries.keys.map { it.asString() }})"
    }

    public class Error(
        override val transactionId: ByteArray,
        public val code: Int,
        public val message: String,
    ) : KrpcMessage {
        override fun toString(): String = "Error($code, $message)"
    }
}

/** BEP 5's four queries and the shapes of their answers. */
public object Krpc {
    public const val PING: String = "ping"
    public const val FIND_NODE: String = "find_node"
    public const val GET_PEERS: String = "get_peers"
    public const val ANNOUNCE_PEER: String = "announce_peer"

    /** Id plus a compact address: what `nodes` is a repetition of. */
    public const val COMPACT_NODE_SIZE: Int = NodeId.SIZE + CompactPeers.SIZE

    public fun encode(message: KrpcMessage): ByteArray {
        val fields = LinkedHashMap<BString, BValue>()
        fields[BString("t")] = BString(message.transactionId)
        when (message) {
            is KrpcMessage.Query -> {
                fields[BString("y")] = BString("q")
                fields[BString("q")] = BString(message.method)
                fields[BString("a")] = message.arguments
            }

            is KrpcMessage.Response -> {
                fields[BString("y")] = BString("r")
                fields[BString("r")] = message.values
            }

            is KrpcMessage.Error -> {
                fields[BString("y")] = BString("e")
                fields[BString("e")] =
                    BList(listOf(BInteger(message.code.toLong()), BString(message.message)))
            }
        }
        return Bencode.encode(BDictionary(fields))
    }

    public fun decode(
        packet: ByteArray,
        length: Int = packet.size,
    ): KrpcMessage {
        val root =
            try {
                Bencode.decode(packet.copyOfRange(0, length)) as? BDictionary
                    ?: throw KrpcException("a KRPC message is a dictionary")
            } catch (malformed: BencodeException) {
                throw KrpcException("a KRPC message is not bencode: ${malformed.message}")
            }
        val transaction = (root["t"] as? BString)?.bytes ?: throw KrpcException("a KRPC message has no `t`")
        return when (val kind = (root["y"] as? BString)?.asString()) {
            "q" -> {
                KrpcMessage.Query(
                    transactionId = transaction,
                    method = (root["q"] as? BString)?.asString() ?: throw KrpcException("a query has no `q`"),
                    arguments = root["a"] as? BDictionary ?: BDictionary(emptyMap()),
                )
            }

            "r" -> {
                KrpcMessage.Response(
                    transactionId = transaction,
                    values = root["r"] as? BDictionary ?: BDictionary(emptyMap()),
                )
            }

            "e" -> {
                val error = root["e"] as? BList ?: throw KrpcException("an error has no `e`")
                KrpcMessage.Error(
                    transactionId = transaction,
                    code = (error.items.getOrNull(0) as? BInteger)?.value?.toInt() ?: GENERIC_ERROR,
                    message = (error.items.getOrNull(1) as? BString)?.asString() ?: "no reason given",
                )
            }

            else -> {
                throw KrpcException("`y` is `$kind`, which is not one of q, r, e")
            }
        }
    }

    public fun ping(self: NodeId): BDictionary = BDictionary(mapOf(BString("id") to BString(self.bytes)))

    public fun findNode(
        self: NodeId,
        target: NodeId,
    ): BDictionary =
        BDictionary(
            mapOf(BString("id") to BString(self.bytes), BString("target") to BString(target.bytes)),
        )

    public fun getPeers(
        self: NodeId,
        infoHash: InfoHash,
    ): BDictionary =
        BDictionary(
            mapOf(BString("id") to BString(self.bytes), BString("info_hash") to BString(infoHash.bytes)),
        )

    /**
     * `announce_peer`, with `implied_port` set.
     *
     * BEP 5: when `implied_port` is non-zero the node ignores the `port` argument and uses the
     * source port of the datagram. That is the right answer behind NAT, where the port a client
     * *thinks* it listens on and the port the world can reach it at are different — and it is the
     * common case for a home connection.
     */
    public fun announcePeer(
        self: NodeId,
        infoHash: InfoHash,
        port: Int,
        token: ByteArray,
    ): BDictionary =
        BDictionary(
            mapOf(
                BString("id") to BString(self.bytes),
                BString("info_hash") to BString(infoHash.bytes),
                BString("port") to BInteger(port.toLong()),
                BString("implied_port") to BInteger(1),
                BString("token") to BString(token),
            ),
        )

    /**
     * `nodes`: 26 bytes per node, id then compact address.
     *
     * A trailing fragment is dropped rather than read past, for the same reason a compact peer
     * list's is: half a node is not a node.
     */
    public fun decodeNodes(bytes: ByteArray): List<DhtNode> {
        val nodes = ArrayList<DhtNode>(bytes.size / COMPACT_NODE_SIZE)
        var at = 0
        while (at + COMPACT_NODE_SIZE <= bytes.size) {
            val id = NodeId(bytes.copyOfRange(at, at + NodeId.SIZE))
            val address = CompactPeers.decode(bytes, at + NodeId.SIZE, at + COMPACT_NODE_SIZE).firstOrNull()
            if (address != null) nodes += DhtNode(id, address)
            at += COMPACT_NODE_SIZE
        }
        return nodes
    }

    public fun encodeNodes(nodes: List<DhtNode>): ByteArray {
        val packable = nodes.filter { CompactPeers.isPackable(it.address.host) }
        val bytes = ByteArray(packable.size * COMPACT_NODE_SIZE)
        packable.forEachIndexed { index, node ->
            val at = index * COMPACT_NODE_SIZE
            node.id.bytes.copyInto(bytes, at)
            CompactPeers.encode(listOf(node.address)).copyInto(bytes, at + NodeId.SIZE)
        }
        return bytes
    }

    /**
     * `values`: a **list of strings**, one compact peer each — not one string of many peers, which
     * is what the tracker's `peers` is and what a reader who has just written that one expects.
     */
    public fun decodeValues(value: BValue?): List<PeerAddress> =
        (value as? BList)
            ?.items
            ?.filterIsInstance<BString>()
            ?.flatMap { CompactPeers.decode(it.bytes) }
            ?: emptyList()

    public fun encodeValues(peers: List<PeerAddress>): BList =
        BList(peers.map { BString(CompactPeers.encode(listOf(it))) })

    private const val GENERIC_ERROR = 201
}

internal fun BDictionary.nodeId(key: String): NodeId? =
    (this[key] as? BString)?.bytes?.takeIf { it.size == NodeId.SIZE }?.let { NodeId(it) }
