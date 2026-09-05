package ru.workinprogress.kachok.engine.dht

import ru.workinprogress.kachok.engine.peer.PeerAddress
import kotlin.random.Random

/**
 * A node's 160-bit identifier in the DHT's key space (BEP 5).
 *
 * **Not a value class**, unlike the torrent's other twenty-byte identifiers. The routing table
 * keys maps by node id and asks whether two ids are the same constantly; a `value class` around a
 * `ByteArray` inherits the array's equality, which is identity, so every lookup would miss and the
 * table would fill with duplicates of the same node.
 */
public class NodeId(
    public val bytes: ByteArray,
) : Comparable<NodeId> {
    init {
        require(bytes.size == SIZE) { "a node id is $SIZE bytes, got ${bytes.size}" }
    }

    /**
     * XOR, which is the whole of Kademlia: the distance between two ids is a third id, and
     * "closer" means smaller as an unsigned 160-bit number.
     */
    public infix fun distanceTo(other: NodeId): NodeId =
        NodeId(
            ByteArray(SIZE) {
                (
                    bytes[it].toInt() xor
                        other.bytes[it].toInt()
                ).toByte()
            },
        )

    /** How many leading bits two ids share: which bucket a node belongs in. */
    public infix fun commonPrefixWith(other: NodeId): Int {
        for (index in 0 until SIZE) {
            val difference = bytes[index].toInt() xor other.bytes[index].toInt()
            if (difference != 0) {
                var bit = 0
                while (bit < 8 && (difference and (0x80 ushr bit)) == 0) bit++
                return index * 8 + bit
            }
        }
        return SIZE * 8
    }

    /** Unsigned, big-endian: the ordering Kademlia's "closest" is defined in terms of. */
    override fun compareTo(other: NodeId): Int {
        for (index in 0 until SIZE) {
            val difference = (bytes[index].toInt() and 0xFF) - (other.bytes[index].toInt() and 0xFF)
            if (difference != 0) return difference
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is NodeId && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String =
        bytes.take(4).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') } + "…"

    public companion object {
        public const val SIZE: Int = 20

        public fun random(random: Random = Random.Default): NodeId =
            NodeId(ByteArray(SIZE) { random.nextInt(256).toByte() })
    }
}

/** A node in the DHT: an id and where to reach it. */
public class DhtNode(
    public val id: NodeId,
    public val address: PeerAddress,
) {
    override fun equals(other: Any?): Boolean = other is DhtNode && other.id == id && other.address == address

    override fun hashCode(): Int = id.hashCode() * 31 + address.hashCode()

    override fun toString(): String = "$id@$address"
}

/** Orders nodes by how close they are to one target, which is every lookup's only question. */
public fun closestTo(target: NodeId): Comparator<DhtNode> =
    Comparator { left, right -> (left.id distanceTo target).compareTo(right.id distanceTo target) }
