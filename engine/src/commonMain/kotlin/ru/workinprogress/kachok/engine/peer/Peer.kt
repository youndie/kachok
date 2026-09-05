package ru.workinprogress.kachok.engine.peer

import kotlinx.coroutines.channels.ReceiveChannel
import ru.workinprogress.kachok.engine.PieceIndex
import ru.workinprogress.kachok.engine.wire.Handshake
import ru.workinprogress.kachok.engine.wire.Message

/** Where a peer is. A host as the tracker gave it: an address most of the time, a name sometimes. */
public class PeerAddress(
    public val host: String,
    public val port: Int,
) {
    override fun equals(other: Any?): Boolean = other is PeerAddress && other.host == host && other.port == port

    override fun hashCode(): Int = host.hashCode() * 31 + port

    /** Brackets an IPv6 host: `2001:db8::1:6881` cannot be read back, `[2001:db8::1]:6881` can. */
    override fun toString(): String = if (':' in host) "[$host]:$port" else "$host:$port"
}

/**
 * One block of a piece, still in whatever buffer the platform read it into.
 *
 * The bytes are deliberately **not** on this interface. A block on the JVM lives in a pooled
 * direct `ByteBuffer` that goes on to be written to disk without ever being copied, and a
 * `ByteArray` accessor here would be an invitation to copy it — the one thing the whole memory
 * design exists to avoid (research D3). Common code routes blocks; the platform's storage reads
 * them.
 *
 * [release] returns the buffer to its pool. A block that is never released is a buffer the pool
 * never hands out again, which is a leak with the same symptom as a stall.
 */
public interface Block {
    public val piece: PieceIndex
    public val begin: Int
    public val length: Int

    public fun release()
}

/**
 * What a connection tells the session about. Blocks are their own case rather than a
 * [Message.Piece]: the message carries offsets into a frame, the event carries an owned buffer.
 */
public sealed interface PeerEvent {
    /** Any message other than a block. */
    public class Received(
        public val message: Message,
    ) : PeerEvent {
        override fun toString(): String = "Received($message)"
    }

    /** A block, in a buffer the receiver now owns and must release. */
    public class BlockReceived(
        public val block: Block,
    ) : PeerEvent {
        override fun toString(): String = "BlockReceived(${block.piece.value}, ${block.begin})"
    }

    /** The connection ended. [cause] is null for an orderly close. */
    public class Closed(
        public val cause: Throwable?,
    ) : PeerEvent {
        override fun toString(): String = "Closed(${cause?.message ?: "orderly"})"
    }
}

/**
 * A live connection to one peer, from the session's point of view.
 *
 * The platform decides what a connection *is* — on the JVM it is one blocking `SocketChannel` and
 * one virtual thread — and this is all the session is allowed to know about it.
 */
public interface PeerConnection {
    public val address: PeerAddress

    /** What the peer said in its handshake, including which extensions it speaks. */
    public val handshake: Handshake

    /** Everything the peer says, in order. The receiver owns — and must release — every block. */
    public val events: ReceiveChannel<PeerEvent>

    /** Queues a message. Suspends only if the peer is far enough behind to fill the queue. */
    public suspend fun send(message: Message)

    /**
     * Queues a block of a piece we hold, to be read from storage and sent.
     *
     * The bytes are deliberately not a parameter. On the JVM the block goes from the page cache to
     * the socket inside the kernel and never enters this process (research D5); a signature taking
     * a `ByteArray` would make the copy compulsory.
     */
    public suspend fun sendBlock(
        piece: PieceIndex,
        begin: Int,
        length: Int,
    )

    /** Bytes served on this connection, for the tracker's `uploaded`. */
    public val uploaded: Long

    public fun close()
}

/**
 * Dials a peer, or fails.
 *
 * The session never constructs a connection itself: on the JVM that means a `SocketChannel` and a
 * handshake, in a test it means a scripted event channel, and the session cannot tell the
 * difference — which is what makes every rule in it testable without a network.
 */
public interface PeerDialer {
    public suspend fun connect(address: PeerAddress): PeerConnection
}
