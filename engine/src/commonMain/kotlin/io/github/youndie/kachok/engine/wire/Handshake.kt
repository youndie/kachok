package io.github.youndie.kachok.engine.wire

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.PeerId

/**
 * The 68 bytes that open every peer connection (BEP 3): one byte `19`, the string
 * `BitTorrent protocol`, eight reserved bytes, the info hash, the peer id.
 *
 * The reserved bytes are not decoration — they are how a peer says which extensions it speaks, and
 * the two this project cares about are read here rather than by whoever remembers the bit numbers.
 */
public class Handshake(
    public val infoHash: InfoHash,
    public val peerId: PeerId,
    public val reserved: ByteArray = ByteArray(RESERVED_SIZE),
) {
    /** BEP 10: `reserved[5] & 0x10`. */
    public val supportsExtensionProtocol: Boolean
        get() = hasExtensionProtocol(reserved)

    /** BEP 6: `reserved[7] |= 0x04`. */
    public val supportsFastExtension: Boolean
        get() = hasFastExtension(reserved)

    public fun encode(): ByteArray {
        val out = ByteArray(SIZE)
        out[0] = PROTOCOL.length.toByte()
        PROTOCOL.encodeToByteArray().copyInto(out, 1)
        reserved.copyInto(out, 1 + PROTOCOL.length)
        infoHash.bytes.copyInto(out, 1 + PROTOCOL.length + RESERVED_SIZE)
        peerId.bytes.copyInto(out, 1 + PROTOCOL.length + RESERVED_SIZE + InfoHash.SIZE)
        return out
    }

    public companion object {
        public const val PROTOCOL: String = "BitTorrent protocol"
        public const val RESERVED_SIZE: Int = 8
        public const val SIZE: Int = 1 + PROTOCOL.length + RESERVED_SIZE + InfoHash.SIZE + PeerId.SIZE

        /**
         * The same two questions asked of bare reserved bytes.
         *
         * BEP 6's and BEP 10's messages are legal only when **both** sides advertised, so the
         * session has to ask them of what it sends as well as of what it received — and asking
         * them of one array rather than storing a second boolean is what keeps the two answers
         * from drifting apart.
         */
        public fun hasExtensionProtocol(reserved: ByteArray): Boolean =
            (reserved[EXTENSION_BYTE].toInt() and EXTENSION_BIT) != 0

        public fun hasFastExtension(reserved: ByteArray): Boolean = (reserved[FAST_BYTE].toInt() and FAST_BIT) != 0

        private const val EXTENSION_BYTE = 5
        private const val EXTENSION_BIT = 0x10
        private const val FAST_BYTE = 7
        private const val FAST_BIT = 0x04

        /** A handshake advertising the extensions this client is built to grow into. */
        public fun reservedBits(
            extensionProtocol: Boolean = false,
            fastExtension: Boolean = false,
        ): ByteArray {
            val reserved = ByteArray(RESERVED_SIZE)
            if (extensionProtocol) {
                reserved[EXTENSION_BYTE] = (reserved[EXTENSION_BYTE].toInt() or EXTENSION_BIT).toByte()
            }
            if (fastExtension) {
                reserved[FAST_BYTE] = (reserved[FAST_BYTE].toInt() or FAST_BIT).toByte()
            }
            return reserved
        }

        public fun decode(
            bytes: ByteArray,
            from: Int = 0,
        ): Handshake {
            if (bytes.size - from < SIZE) {
                throw WireException("a handshake is $SIZE bytes, got ${bytes.size - from}")
            }
            val nameLength = bytes[from].toInt() and 0xFF
            if (nameLength != PROTOCOL.length) {
                throw WireException("handshake announces a $nameLength-byte protocol name, expected ${PROTOCOL.length}")
            }
            val name = bytes.decodeToString(from + 1, from + 1 + nameLength)
            if (name != PROTOCOL) {
                throw WireException("handshake protocol name is '$name', expected '$PROTOCOL'")
            }
            var at = from + 1 + nameLength
            val reserved = bytes.copyOfRange(at, at + RESERVED_SIZE)
            at += RESERVED_SIZE
            val infoHash = InfoHash(bytes.copyOfRange(at, at + InfoHash.SIZE))
            at += InfoHash.SIZE
            val peerId = PeerId(bytes.copyOfRange(at, at + PeerId.SIZE))
            return Handshake(infoHash, peerId, reserved)
        }
    }
}
