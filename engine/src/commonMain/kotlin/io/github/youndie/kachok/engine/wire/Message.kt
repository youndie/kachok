package io.github.youndie.kachok.engine.wire

import io.github.youndie.kachok.engine.PieceIndex

/**
 * A peer wire message (BEP 3), after the length prefix has been stripped.
 *
 * The hierarchy is deliberately lopsided. The rare messages carry their data in objects, because
 * one object per `have` costs nothing and reads well. [Piece] does not: it carries **offsets into
 * the caller's frame**, so that a 16 KiB block is never copied out of the buffer it was read into.
 * That is the whole point of the buffer pool, and a `ByteArray` field here would defeat it before
 * the transport had a chance.
 */
public sealed interface Message {
    /** A zero-length frame. BEP 3: "Messages of length zero are keepalives, and ignored." */
    public data object KeepAlive : Message

    public data object Choke : Message

    public data object Unchoke : Message

    public data object Interested : Message

    public data object NotInterested : Message

    public class Have(
        public val piece: PieceIndex,
    ) : Message {
        override fun toString(): String = "Have(${piece.value})"
    }

    /**
     * BEP 3: only ever the first message, high bit first, spare bits zero. The bytes are the
     * message's own; a bitfield is one per connection, not one per block.
     */
    public class Bitfield(
        public val bits: ByteArray,
    ) : Message {
        override fun toString(): String = "Bitfield(${bits.size} bytes)"
    }

    public class Request(
        public val piece: PieceIndex,
        public val begin: Int,
        public val length: Int,
    ) : Message {
        override fun toString(): String = "Request(${piece.value}, $begin, $length)"
    }

    public class Cancel(
        public val piece: PieceIndex,
        public val begin: Int,
        public val length: Int,
    ) : Message {
        override fun toString(): String = "Cancel(${piece.value}, $begin, $length)"
    }

    /**
     * The block header only. [blockFrom] and [blockLength] point into the array that was decoded;
     * reading them after that array has been reused reads somebody else's block, which is why the
     * transport hands the buffer to the writer before it decodes anything else on that connection.
     */
    public class Piece(
        public val piece: PieceIndex,
        public val begin: Int,
        public val blockFrom: Int,
        public val blockLength: Int,
    ) : Message {
        override fun toString(): String = "Piece(${piece.value}, $begin, $blockLength bytes)"
    }

    /**
     * BEP 6's `have all` and `have none`, which replace the opening `bitfield` between two peers
     * that both set `reserved[7] |= 0x04`.
     *
     * Not decoration: a seed's bitfield for a two-million-piece torrent is 250 KiB, and a peer with
     * nothing sends the same 250 KiB of zeros. BEP 6 makes both of those one byte. The first
     * message on a fast connection is one of these three and never nothing.
     */
    public data object HaveAll : Message

    public data object HaveNone : Message

    /**
     * BEP 6: this request will not be answered.
     *
     * The reason the fast extension is worth having at all. Without it a choke leaves the picker
     * guessing which of its outstanding requests died, and the answer arrives as a timeout half a
     * minute later; with it the block is free again in one round trip.
     */
    public class Reject(
        public val piece: PieceIndex,
        public val begin: Int,
        public val length: Int,
    ) : Message {
        override fun toString(): String = "Reject(${piece.value}, $begin, $length)"
    }

    /** BEP 6: a piece the sender thinks is worth asking it for. It implies the sender has it. */
    public class Suggest(
        public val piece: PieceIndex,
    ) : Message {
        override fun toString(): String = "Suggest(${piece.value})"
    }

    /**
     * BEP 6: a piece this peer will serve **even while it is choking us**.
     *
     * The one message that changes what may be sent rather than what is known, which is why the
     * session's "choked means ask for nothing" has an exception in it.
     */
    public class AllowedFast(
        public val piece: PieceIndex,
    ) : Message {
        override fun toString(): String = "AllowedFast(${piece.value})"
    }

    /** BEP 10. The payload is the extension's business, not the wire's. */
    public class Extended(
        public val extensionId: Int,
        public val payload: ByteArray,
    ) : Message {
        override fun toString(): String = "Extended($extensionId, ${payload.size} bytes)"
    }
}

/** A frame this client refuses. Closing the connection is the caller's decision. */
public class WireException(
    message: String,
) : IllegalArgumentException(message)
