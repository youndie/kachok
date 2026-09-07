package io.github.youndie.kachok.engine.wire

import io.github.youndie.kachok.engine.bencode.BDictionary
import io.github.youndie.kachok.engine.bencode.BInteger
import io.github.youndie.kachok.engine.bencode.BString
import io.github.youndie.kachok.engine.bencode.BValue
import io.github.youndie.kachok.engine.bencode.Bencode
import io.github.youndie.kachok.engine.bencode.BencodeException

/**
 * BEP 9's `ut_metadata` payload: a bencoded dictionary, and for a `data` message the block itself
 * immediately after it.
 *
 * **Nothing says where the dictionary ends.** There is no length field and no separator; the block
 * begins at the byte after the dictionary's closing `e`, and the only thing that knows where that
 * is is the decoder — which is why [io.github.youndie.kachok.engine.bencode.Bencode.decodePrefix]
 * exists. `total_size` describes the whole metadata, not this message.
 */
public class MetadataMessage(
    public val type: Int,
    public val piece: Int,
    /** The whole info dictionary's length, present on `data`. */
    public val totalSize: Int? = null,
    /** The block, for `data`. Empty otherwise. */
    public val data: ByteArray = ByteArray(0),
) {
    override fun toString(): String =
        when (type) {
            REQUEST -> "MetadataRequest($piece)"
            DATA -> "MetadataData($piece, ${data.size} bytes)"
            REJECT -> "MetadataReject($piece)"
            else -> "MetadataMessage($type, $piece)"
        }

    public fun encode(): ByteArray {
        val fields = LinkedHashMap<BString, BValue>()
        fields[BString("msg_type")] = BInteger(type.toLong())
        fields[BString("piece")] = BInteger(piece.toLong())
        totalSize?.let { fields[BString("total_size")] = BInteger(it.toLong()) }
        val header = Bencode.encode(BDictionary(fields))
        if (data.isEmpty()) return header
        val out = ByteArray(header.size + data.size)
        header.copyInto(out)
        data.copyInto(out, header.size)
        return out
    }

    public companion object {
        public const val REQUEST: Int = 0
        public const val DATA: Int = 1
        public const val REJECT: Int = 2

        /** BEP 9: 16 KiB, the same block size the wire uses, and the last one is short. */
        public const val BLOCK_SIZE: Int = 16 * 1024

        /** How many blocks a metadata of this many bytes is. */
        public fun blockCount(totalSize: Int): Int = (totalSize + BLOCK_SIZE - 1) / BLOCK_SIZE

        public fun request(piece: Int): MetadataMessage = MetadataMessage(REQUEST, piece)

        public fun reject(piece: Int): MetadataMessage = MetadataMessage(REJECT, piece)

        public fun data(
            piece: Int,
            totalSize: Int,
            block: ByteArray,
        ): MetadataMessage = MetadataMessage(DATA, piece, totalSize, block)

        public fun decode(payload: ByteArray): MetadataMessage {
            val (value, end) =
                try {
                    Bencode.decodePrefix(payload)
                } catch (malformed: BencodeException) {
                    throw WireException("a ut_metadata message does not start with bencode: ${malformed.message}")
                }
            val root = value as? BDictionary ?: throw WireException("a ut_metadata message is a dictionary")
            val type =
                (root["msg_type"] as? BInteger)?.value?.toInt()
                    ?: throw WireException("a ut_metadata message has no `msg_type`")
            val piece =
                (root["piece"] as? BInteger)?.value?.toInt()
                    ?: throw WireException("a ut_metadata message has no `piece`")
            return MetadataMessage(
                type = type,
                piece = piece,
                totalSize = (root["total_size"] as? BInteger)?.value?.toInt(),
                // Whatever followed the dictionary is the block. A `request` or a `reject` has
                // nothing after it, and reading zero bytes there is the right answer.
                data = if (end < payload.size) payload.copyOfRange(end, payload.size) else ByteArray(0),
            )
        }
    }
}
