package ru.workinprogress.kachok.engine.io

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import ru.workinprogress.kachok.engine.InfoHash
import ru.workinprogress.kachok.engine.PeerId
import ru.workinprogress.kachok.engine.PieceIndex
import ru.workinprogress.kachok.engine.hash.JvmBlock
import ru.workinprogress.kachok.engine.peer.PeerAddress
import ru.workinprogress.kachok.engine.peer.PeerConnection
import ru.workinprogress.kachok.engine.peer.PeerEvent
import ru.workinprogress.kachok.engine.wire.Handshake
import ru.workinprogress.kachok.engine.wire.Message
import ru.workinprogress.kachok.engine.wire.PeerWire
import ru.workinprogress.kachok.engine.wire.WireException
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A [Block] whose bytes are a pooled direct buffer, flipped and ready to be written. */
public class PooledBlock internal constructor(
    override val piece: PieceIndex,
    override val begin: Int,
    private val pooled: PooledBuffer,
) : JvmBlock {
    /** The block's bytes. Valid until [release]; after that the buffer belongs to somebody else. */
    override val bytes: ByteBuffer get() = pooled.buffer

    override val length: Int get() = pooled.buffer.remaining()

    override fun release() {
        pooled.release()
    }
}

/**
 * One peer, one blocking [SocketChannel], one virtual thread reading it.
 *
 * There is no selector here and there is not going to be one. A blocking read on a virtual thread
 * parks the *virtual* thread and frees its carrier (research §1.1), so ten thousand idle peers cost
 * ten thousand stacks and no platform threads — which is the entire reason this project targets
 * JDK 25.
 *
 * **The reader loop suspends in exactly one place, and that place is the point.** Taking a buffer
 * from the pool for an incoming block suspends when every buffer is out, which stops this
 * connection reading and lets the receive window apply the back-pressure. Everything else in the
 * loop blocks rather than suspends. (The original design note said the reader never suspends at
 * all; it cannot, and the correction is recorded in the research at D1.)
 */
public class SocketPeerConnection private constructor(
    override val address: PeerAddress,
    override val handshake: Handshake,
    private val socket: SocketChannel,
    private val pool: BufferPool,
) : PeerConnection,
    AutoCloseable {
    private val outgoing = Channel<Message>(OUTGOING_QUEUE)
    private val incoming = Channel<PeerEvent>(INCOMING_QUEUE)
    private lateinit var reader: Job
    private lateinit var writer: Job

    override val events: ReceiveChannel<PeerEvent> get() = incoming

    override suspend fun send(message: Message) {
        outgoing.send(message)
    }

    override fun close() {
        outgoing.close()
        socket.closeQuietly()
        if (::reader.isInitialized) reader.cancel()
        if (::writer.isInitialized) writer.cancel()
        incoming.close()
    }

    private fun start(scope: CoroutineScope) {
        reader = scope.launch { readLoop() }
        writer = scope.launch { writeLoop() }
    }

    /**
     * Blocking reads, one frame at a time. A frame is a four-byte length prefix and that many
     * bytes; a block goes straight into a pooled buffer, everything else into a small heap array.
     */
    private suspend fun readLoop() {
        val scratch = ByteBuffer.allocateDirect(SCRATCH_SIZE)
        try {
            while (true) {
                val length = readLength(scratch) ?: break
                if (length == 0) {
                    incoming.send(PeerEvent.Received(Message.KeepAlive))
                    continue
                }
                if (length > PeerWire.MAX_FRAME_SIZE) {
                    throw WireException("peer announced a $length-byte frame, cap is ${PeerWire.MAX_FRAME_SIZE}")
                }
                readFrame(length, scratch)
            }
            incoming.send(PeerEvent.Closed(null))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // Reported, not rethrown. A connection ending — because the peer hung up, because the
            // frame was malformed, or because *we* closed the socket — is news for the session,
            // which is what the event is for. Rethrowing sends it to whatever the platform does
            // with an uncaught coroutine exception instead, and the commonest cause is our own
            // `close()`: the blocking read then fails with `AsynchronousCloseException`, which is
            // the shutdown working rather than anything going wrong.
            //
            // trySend because the session may already have stopped listening, and a second failure
            // while reporting the first one helps nobody.
            incoming.trySend(PeerEvent.Closed(failure))
        } finally {
            incoming.close()
            socket.closeQuietly()
        }
    }

    /** Null at an orderly end of stream, which is a peer hanging up rather than a failure. */
    private fun readLength(scratch: ByteBuffer): Int? {
        scratch.clear().limit(PeerWire.LENGTH_PREFIX_SIZE)
        while (scratch.hasRemaining()) {
            val read = socket.read(scratch)
            if (read < 0) {
                return if (scratch.position() == 0) null else throw EOFException("frame length cut short")
            }
        }
        return scratch.flip().int
    }

    private suspend fun readFrame(
        length: Int,
        scratch: ByteBuffer,
    ) {
        scratch.clear().limit(1)
        readFully(scratch)
        val id = scratch.flip().get().toInt() and 0xFF

        if (id == PeerWire.PIECE) {
            if (length < PIECE_HEADER) throw WireException("a piece frame of $length bytes has no header")
            scratch.clear().limit(PIECE_HEADER - 1)
            readFully(scratch)
            scratch.flip()
            val piece = PieceIndex(scratch.int)
            val begin = scratch.int
            val blockLength = length - PIECE_HEADER
            // The one suspension in the loop: no buffer, no reading, which is the back-pressure.
            val pooled = pool.acquire()
            try {
                pooled.buffer.clear().limit(blockLength)
                readFully(pooled.buffer)
                pooled.buffer.flip()
            } catch (failure: Throwable) {
                pooled.release()
                throw failure
            }
            incoming.send(PeerEvent.BlockReceived(PooledBlock(piece, begin, pooled)))
            return
        }

        // Cold path: the frame is small, so a heap array costs nothing and reads plainly.
        val frame = ByteArray(length)
        frame[0] = id.toByte()
        if (length > 1) readFully(ByteBuffer.wrap(frame, 1, length - 1))
        incoming.send(PeerEvent.Received(PeerWire.decode(frame)))
    }

    private fun readFully(destination: ByteBuffer) {
        while (destination.hasRemaining()) {
            if (socket.read(destination) < 0) throw EOFException("peer closed mid-frame")
        }
    }

    /**
     * One writer per connection, draining a queue. BEP 3 asks that requests queued behind a choke
     * be droppable, and a queue is the only shape that lets anyone drop them.
     */
    private suspend fun writeLoop() {
        try {
            for (message in outgoing) {
                val bytes = ByteBuffer.wrap(PeerWire.encode(message))
                while (bytes.hasRemaining()) socket.write(bytes)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (closed: IOException) {
            // The socket went away under us; the reader reports the connection's end, and two
            // reports of one event are one too many.
        } finally {
            try {
                socket.shutdownOutput()
            } catch (ignored: IOException) {
                // The peer is gone, which is the only reason a half-close fails and is exactly
                // what this shutdown was announcing.
            }
        }
    }

    public companion object {
        /** Long enough for a slow route, short enough that a dead peer is not a lost slot. */
        public val DEFAULT_CONNECT_TIMEOUT: Duration = 10.seconds

        private const val OUTGOING_QUEUE = 64
        private const val INCOMING_QUEUE = 64
        private const val SCRATCH_SIZE = 16
        private const val PIECE_HEADER = 9

        /**
         * Dials a peer and completes the handshake, or throws and leaves no socket behind.
         *
         * A peer answering for a different torrent is dropped here rather than confusing the
         * session later: BEP 3 says the two sides sever the connection when the info hashes
         * differ, and this is where that happens.
         */
        public suspend fun connect(
            scope: CoroutineScope,
            address: PeerAddress,
            infoHash: InfoHash,
            peerId: PeerId,
            pool: BufferPool,
            reserved: ByteArray = Handshake.reservedBits(),
            connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
        ): SocketPeerConnection {
            val socket = SocketChannel.open()
            try {
                // Through the socket rather than the channel, because `SocketChannel.connect` has
                // no timeout and a peer that silently drops packets then holds this coroutine —
                // and one of the session's connection slots — until the operating system gives up,
                // which is minutes. Half the addresses a tracker hands out are like that; it is the
                // normal case, not an edge one. Measured against a real swarm in B-19: 22 of 50
                // dials were stuck in `connect` while five connections did the work.
                socket.socket().connect(
                    InetSocketAddress(address.host, address.port),
                    connectTimeout.inWholeMilliseconds.toInt(),
                )
                val ours = Handshake(infoHash, peerId, reserved)
                val out = ByteBuffer.wrap(ours.encode())
                while (out.hasRemaining()) socket.write(out)

                val theirs = ByteBuffer.allocate(Handshake.SIZE)
                while (theirs.hasRemaining()) {
                    if (socket.read(theirs) < 0) {
                        throw EOFException("peer closed during the handshake")
                    }
                }
                val handshake = Handshake.decode(theirs.array())
                if (!handshake.infoHash.bytes.contentEquals(infoHash.bytes)) {
                    throw WireException("peer answered for another torrent: ${handshake.infoHash.bytes.toHex()}")
                }
                return SocketPeerConnection(address, handshake, socket, pool).also { it.start(scope) }
            } catch (failure: Throwable) {
                socket.closeQuietly()
                throw failure
            }
        }

        private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}

/**
 * Closes and does not report. A socket that fails to close is already unusable, and the failure
 * worth reporting is the one that caused the close — which is on its way to the caller.
 */
private fun AutoCloseable.closeQuietly() {
    try {
        close()
    } catch (ignored: IOException) {
        // See above: there is nothing a caller could do with this.
    }
}
