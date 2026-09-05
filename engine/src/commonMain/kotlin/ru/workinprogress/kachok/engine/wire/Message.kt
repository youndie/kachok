package ru.workinprogress.kachok.engine.wire

import ru.workinprogress.kachok.engine.PieceIndex

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
