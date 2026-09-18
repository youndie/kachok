package io.github.youndie.kachok.engine.io

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.PeerId
import io.github.youndie.kachok.engine.PieceIndex
import io.github.youndie.kachok.engine.hash.JvmBlock
import io.github.youndie.kachok.engine.mse.Mse
import io.github.youndie.kachok.engine.mse.MseException
import io.github.youndie.kachok.engine.mse.MseResult
import io.github.youndie.kachok.engine.peer.Encryption
import io.github.youndie.kachok.engine.peer.PeerAddress
import io.github.youndie.kachok.engine.peer.PeerConnection
import io.github.youndie.kachok.engine.peer.PeerEvent
import io.github.youndie.kachok.engine.wire.Handshake
import io.github.youndie.kachok.engine.wire.Message
import io.github.youndie.kachok.engine.wire.PeerWire
import io.github.youndie.kachok.engine.wire.WireException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SocketChannel
import java.nio.channels.WritableByteChannel
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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
    /** Where the blocks this connection serves come from. Required: see [BlockSource] and B-110. */
    private val blocks: BlockSource,
    /**
     * The keystreams the MSE handshake negotiated, or null for a connection in the clear.
     *
     * Null covers two cases that behave identically from here: a peer that never spoke MSE, and
     * one that spoke it and chose `crypto_plaintext` — the obfuscated handshake with a clear
     * payload, which is a legal outcome both sides offer (B-100).
     */
    crypto: MseResult? = null,
    /**
     * Bytes the handshake read past its own end, which belong to the wire.
     *
     * The accepting side of MSE receives the dialler's first message as `IA`; the BitTorrent
     * handshake is taken out of it and anything after that is the first frame.
     */
    carried: ByteArray = ByteArray(0),
) : PeerConnection,
    AutoCloseable {
    /**
     * Everything read and written after the handshake goes through these, and on a plaintext
     * connection they *are* the socket — same object, no wrapper, nothing to pay.
     */
    private val source: ReadableByteChannel =
        crypto
            ?.decrypt
            ?.let { DecryptingChannel(socket, it) }
            ?.let { if (carried.isEmpty()) it else PrefixedChannel(carried, it) }
            ?: if (carried.isEmpty()) socket else PrefixedChannel(carried, socket)

    private val sink: WritableByteChannel = crypto?.encrypt?.let { EncryptingChannel(socket, it) } ?: socket

    /** Whether this connection's payload is encrypted, which the session publishes per peer. */
    override val encrypted: Boolean = crypto?.encrypt != null

    /** Bytes this connection has served. The session sums these for the tracker announce. */
    override var uploaded: Long = 0L
        private set

    private val outgoing = Channel<Outgoing>(OUTGOING_QUEUE)
    private val incoming = Channel<PeerEvent>(INCOMING_QUEUE)
    private lateinit var reader: Job
    private lateinit var writer: Job

    override val events: ReceiveChannel<PeerEvent> get() = incoming

    override suspend fun send(message: Message) {
        enqueue(Outgoing.Frame(message))
    }

    /**
     * Queues without ever waiting, and gives up on a peer that has stopped reading.
     *
     * **A queue that suspends its sender is a peer that can stop the session.** The writer is a
     * blocking `socket.write`, so a peer that keeps the connection open and reads nothing fills
     * the kernel's buffers, then this queue, and then the next `send` to it suspends whoever
     * called — the timer's keep-alives, the `have` broadcast after every piece, the choke pass —
     * and with the timer gone nothing expires a request, nothing dials, nothing unchokes: the
     * download stands still with peers unchoked and requests outstanding for ever. Measured on
     * the public swarm from a machine peers can reach: three runs, each frozen for the rest of
     * its three minutes at 24–29 % after a burst at 20 MiB/s. B-19 met the same failure in the
     * shape of a *closed* queue; this is the shape of a full one. Sixty-four unread messages is
     * not a slow peer, it is a dead one, and it is closed here rather than waited for.
     */
    private fun enqueue(item: Outgoing) {
        val result = outgoing.trySend(item)
        if (result.isSuccess) return
        if (result.isClosed) throw ClosedSendChannelException("connection to $address is closed")
        close()
        throw IOException("$address stopped reading: $OUTGOING_QUEUE messages queued and none taken")
    }

    /**
     * Queues a block to be served from storage.
     *
     * It goes through the same queue as everything else, because a peer's bytes must arrive in the
     * order the protocol put them in — a block written past a `choke` that was queued behind it
     * would be a block the peer has already stopped expecting.
     */
    override suspend fun sendBlock(
        piece: PieceIndex,
        begin: Int,
        length: Int,
    ) {
        enqueue(Outgoing.Block(piece, begin, length))
    }

    /**
     * **`shutdownOutput` before `close`, and the order is not cosmetic.**
     *
     * A block is served with `FileChannel.transferTo(position, count, socket)`. A thread blocked
     * inside that call — which is what a peer that stops reading mid-block produces — is waiting
     * on the *socket* while registered on the *file* channel, and closing the socket does not
     * signal it: measured on macOS and on Linux, 0 of 8 writers ended (research §1.3d). Closing
     * the file channel or interrupting the thread does not end it either; both of those block the
     * caller instead. `shutdownOutput` is the one thing that does, on both platforms, 8 of 8.
     *
     * Without this line a peer that hangs holds a coroutine for as long as TCP takes to give up,
     * and the `FileSet.close()` at the end of the download never returns.
     */
    override fun close() {
        outgoing.close()
        try {
            socket.shutdownOutput()
        } catch (gone: java.io.IOException) {
            // Already closed, or never connected. The `close` below is what matters then.
        }
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
            val read = source.read(scratch)
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
            if (source.read(destination) < 0) throw EOFException("peer closed mid-frame")
        }
    }

    /**
     * One writer per connection, draining a queue. BEP 3 asks that requests queued behind a choke
     * be droppable, and a queue is the only shape that lets anyone drop them.
     */
    private suspend fun writeLoop() {
        try {
            for (item in outgoing) {
                when (item) {
                    is Outgoing.Frame -> {
                        val bytes = ByteBuffer.wrap(PeerWire.encode(item.message))
                        while (bytes.hasRemaining()) sink.write(bytes)
                    }

                    is Outgoing.Block -> {
                        val header = PeerWire.encodePieceHeader(item.piece, item.begin, item.length)
                        val bytes = ByteBuffer.wrap(header)
                        while (bytes.hasRemaining()) sink.write(bytes)
                        // And on a plaintext connection the block itself never enters this process:
                        // `sink` is the socket, and `transferBlock` is `transferTo`. On an
                        // encrypted one it is the keystream, which has to see every byte — the one
                        // real cost of B-100, paid only by the connections that asked for it.
                        blocks.transferBlock(item.piece, item.begin, item.length, sink)
                        uploaded += item.length.toLong()
                    }
                }
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

    /** What a connection's single writer may be asked to put on the wire. */
    private sealed interface Outgoing {
        class Frame(
            val message: Message,
        ) : Outgoing

        class Block(
            val piece: PieceIndex,
            val begin: Int,
            val length: Int,
        ) : Outgoing
    }

    public companion object {
        /** Long enough for a slow route, short enough that a dead peer is not a lost slot. */
        public val DEFAULT_CONNECT_TIMEOUT: Duration = 10.seconds

        /**
         * How long a peer has to send its sixty-eight bytes once the connection is up.
         *
         * **A guess, and it says so** — B-98 is what gives it a number. The shape of the guess:
         * longer than a slow route's round trip, and shorter than the connect timeout, because a
         * peer that answered the SYN and then said nothing has already proved more than a peer
         * that never answered at all and deserves less patience, not more.
         */
        public val DEFAULT_HANDSHAKE_TIMEOUT: Duration = 8.seconds

        /**
         * Reads exactly [Handshake.SIZE] bytes, or throws once [timeout] has passed **in total**.
         *
         * **Why this reads through the socket's stream and not the channel.** A blocking
         * `SocketChannel.read` has no deadline and ignores `SO_TIMEOUT` — the javadoc is explicit
         * that the adapted socket's timeout does not reach channel operations. Two other routes
         * were measured and rejected: `withTimeout` around the read does not end it, because a
         * virtual thread blocked in a socket read is not at a suspension point and cancellation has
         * nowhere to land; a selector would end it and is the one thing this file has committed to
         * not having (see the class comment). The channel's own `socket().getInputStream()` is the
         * remaining door, and it honours `SO_TIMEOUT`.
         *
         * Two properties of that stream were verified on JDK 25.0.2, on Linux and on macOS, before
         * this was written, because the whole approach fails silently if either is false:
         * a read with `SO_TIMEOUT` set throws `SocketTimeoutException` at the deadline (401–405 ms
         * for a 400 ms timeout), and **it does not read ahead** — after taking exactly sixty-eight
         * bytes the following channel read returned byte sixty-eight, so nothing the wire needs is
         * swallowed by the adaptor.
         *
         * **The deadline is recomputed before every read, and that is the point of the loop.**
         * `SO_TIMEOUT` is per read: a peer sending one byte every seven seconds would renew it for
         * ever, which is a slower version of the hang this exists to end.
         */
        private fun readHandshakeBy(
            socket: SocketChannel,
            timeout: Duration,
            decrypt: io.github.youndie.kachok.engine.mse.Rc4? = null,
        ): Handshake = Handshake.decode(readBy(socket, Handshake.SIZE, timeout).also { decrypt?.apply(it) })

        /** [readHandshakeBy]'s machinery, for the handshake and for the bytes that decide its shape. */
        private fun readBy(
            socket: SocketChannel,
            count: Int,
            timeout: Duration,
        ): ByteArray {
            val socketAdaptor = socket.socket()
            val deadline = TimeSource.Monotonic.markNow() + timeout
            val bytes = ByteArray(count)
            var read = 0
            try {
                val stream = socketAdaptor.getInputStream()
                while (read < count) {
                    // Negated because a mark in the future has a negative elapsed time.
                    val left = -deadline.elapsedNow()
                    if (!left.isPositive()) {
                        throw SocketTimeoutException("peer sent $read of $count handshake bytes in $timeout")
                    }
                    // At least one millisecond, or a sub-millisecond remainder would round to zero,
                    // and zero is `SO_TIMEOUT` for "wait for ever" — the bug this whole function is.
                    socketAdaptor.soTimeout = left.inWholeMilliseconds.coerceAtLeast(1).toInt()
                    val got =
                        try {
                            stream.read(bytes, read, count - read)
                        } catch (expired: SocketTimeoutException) {
                            // The stream's own message is "Read timed out", which reaches a person
                            // through `lastPeerError` and tells them nothing. How far the peer got
                            // is the part worth knowing: nothing at all reads differently from
                            // half a handshake.
                            throw SocketTimeoutException(
                                "peer sent $read of $count handshake bytes in $timeout",
                            )
                        }
                    if (got < 0) throw EOFException("peer closed during the handshake")
                    read += got
                }
            } finally {
                // Back to no timeout, because from here the wire is read through the channel, where
                // silence is legitimate: a seed with nothing to say sends a keep-alive every two
                // minutes and nothing in between.
                try {
                    socketAdaptor.soTimeout = 0
                } catch (gone: java.net.SocketException) {
                    // The socket is already closed, which is every failing path through here.
                }
            }
            return bytes
        }

        /** BEP 3's header is nineteen bytes and a length; twenty is the shortest thing that decides. */
        private const val SNIFF_SIZE = 20

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
         *
         * **Encrypted first and in the clear second, when [encryption] says `PREFERRED`.** The
         * peers this exists for refuse a plaintext handshake, and the ones that refuse an
         * encrypted one have to be dialled again — there is no asking a socket to start over, so
         * the fall-back is a second TCP connection. It runs only when the *encrypted handshake*
         * was what failed: a peer that answered for another torrent has answered, and dialling it
         * twice would learn the same thing twice.
         */
        public suspend fun connect(
            scope: CoroutineScope,
            address: PeerAddress,
            infoHash: InfoHash,
            peerId: PeerId,
            pool: BufferPool,
            blocks: BlockSource,
            reserved: ByteArray = Handshake.reservedBits(),
            connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
            handshakeTimeout: Duration = DEFAULT_HANDSHAKE_TIMEOUT,
            /**
             * **Plaintext unless somebody says otherwise, and that is not the client's policy.**
             * This is the transport primitive: it does what the caller asked and nothing by
             * preference. What a *person running kachok* gets is decided where the product is
             * assembled — `SocketPeerDialer`, `RuntimeOptions` and `SetOptions` all default to
             * `PREFERRED` — because an encrypted dial costs a second dial when the peer refuses,
             * and a default that imposes it on every caller of this function would impose it on
             * ten tests that are about something else.
             */
            encryption: Encryption = Encryption.PLAINTEXT,
            random: Random = Random.Default,
        ): SocketPeerConnection {
            val encryptedFirst = encryption != Encryption.PLAINTEXT
            return try {
                dial(
                    scope,
                    address,
                    infoHash,
                    peerId,
                    pool,
                    blocks,
                    reserved,
                    connectTimeout,
                    handshakeTimeout,
                    encryptedFirst,
                    encryption,
                    random,
                )
            } catch (refused: EncryptionRefused) {
                if (encryption == Encryption.REQUIRED) throw refused
                dial(
                    scope,
                    address,
                    infoHash,
                    peerId,
                    pool,
                    blocks,
                    reserved,
                    connectTimeout,
                    handshakeTimeout,
                    encrypted = false,
                    encryption,
                    random,
                )
            }
        }

        private suspend fun dial(
            scope: CoroutineScope,
            address: PeerAddress,
            infoHash: InfoHash,
            peerId: PeerId,
            pool: BufferPool,
            blocks: BlockSource,
            reserved: ByteArray,
            connectTimeout: Duration,
            handshakeTimeout: Duration,
            encrypted: Boolean,
            encryption: Encryption,
            random: Random,
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
                val ours = Handshake(infoHash, peerId, reserved).encode()
                val crypto =
                    if (encrypted) {
                        // Our handshake goes *inside* the MSE exchange as `IA`, which is what saves
                        // the round trip an encrypted connection would otherwise cost.
                        mseDial(socket, address, infoHash, ours, handshakeTimeout, encryption, random)
                    } else {
                        val out = ByteBuffer.wrap(ours)
                        // Bounded from here. This write is not: sixty-eight bytes fit in any send
                        // buffer, so it does not block, and a deadline around it would be a
                        // deadline around nothing.
                        while (out.hasRemaining()) socket.write(out)
                        null
                    }

                val handshake = readHandshakeBy(socket, handshakeTimeout, crypto?.decrypt)
                if (!handshake.infoHash.bytes.contentEquals(infoHash.bytes)) {
                    throw WireException("peer answered for another torrent: ${handshake.infoHash.bytes.toHex()}")
                }
                return SocketPeerConnection(address, handshake, socket, pool, blocks, crypto)
                    .also { it.start(scope) }
            } catch (failure: Throwable) {
                socket.closeQuietly()
                throw failure
            }
        }

        /**
         * The MSE handshake, and the one place its failures are turned into "dial this one in the
         * clear instead".
         */
        private fun mseDial(
            socket: SocketChannel,
            address: PeerAddress,
            infoHash: InfoHash,
            ours: ByteArray,
            handshakeTimeout: Duration,
            encryption: Encryption,
            random: Random,
        ): MseResult {
            val stream = SocketByteStream(socket, handshakeTimeout)
            try {
                return Mse.dial(
                    stream,
                    infoHash,
                    initial = ours,
                    random = random,
                    allowPlaintext = encryption != Encryption.REQUIRED,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw EncryptionRefused(address, failure)
            } finally {
                stream.finish()
            }
        }

        /**
         * Adopts a socket somebody else dialled.
         *
         * The mirror image of [connect] and not a variation on it: the peer that dialled speaks
         * first, so the handshake is read before ours is written, and the info hash is checked
         * before we admit to having the torrent at all.
         */
        public suspend fun accept(
            scope: CoroutineScope,
            socket: SocketChannel,
            infoHash: InfoHash,
            peerId: PeerId,
            pool: BufferPool,
            blocks: BlockSource,
            reserved: ByteArray = Handshake.reservedBits(),
            encryption: Encryption = Encryption.PREFERRED,
            random: Random = Random.Default,
        ): SocketPeerConnection {
            try {
                val accepted = readHandshake(socket, { listOf(infoHash) }, encryption = encryption, random = random)
                if (!accepted.handshake.infoHash.bytes
                        .contentEquals(infoHash.bytes)
                ) {
                    throw WireException("peer asked for another torrent: ${accepted.handshake.infoHash.bytes.toHex()}")
                }
                return answer(scope, socket, accepted, infoHash, peerId, pool, blocks, reserved)
            } catch (failure: Throwable) {
                socket.closeQuietly()
                throw failure
            }
        }

        /**
         * The peer's half of the handshake, read and not answered — in whichever dialect it came.
         *
         * Two steps rather than one because a process holding several torrents cannot know which
         * session a socket belongs to until the peer says: the info hash arrives in *its*
         * handshake, and answering before reading would mean guessing
         * ([B-54](../../../../../../../../../docs/backlog/B-54-many-torrents.md)).
         *
         * **The first twenty bytes decide the dialect, and getting that wrong loses the plaintext
         * peers as well as the encrypted ones.** A plaintext peer opens with BEP 3's fixed
         * nineteen-byte header; an encrypted one opens with the top of a 768-bit public key, which
         * is a number whose first twenty bytes spell `BitTorrent protocol` about as often as
         * never. The bytes are taken off the socket either way, so the MSE handshake is handed
         * them back as its prefix — there is no putting them back.
         *
         * [torrents] answers "do I hold this?", because an encrypted dialler names its torrent
         * only as a hash nobody who does not already know the info hash can recognise.
         *
         * The socket is left open on success and closed on failure, because a caller that has
         * nothing to route this to still has to close it and should not have to remember.
         */
        public fun readHandshake(
            socket: SocketChannel,
            torrents: () -> List<InfoHash> = { emptyList() },
            timeout: Duration = DEFAULT_HANDSHAKE_TIMEOUT,
            encryption: Encryption = Encryption.PREFERRED,
            random: Random = Random.Default,
        ): AcceptedPeer {
            try {
                // **The accepting side needs the deadline more than the dialling one.** A dial
                // that hangs costs one address; an accept that hangs costs a coroutine held by
                // whoever chose to connect to us, which is not a peer this client picked and not a
                // number it controls. Anybody on the network can open handshake-shaped silences.
                val head = readBy(socket, SNIFF_SIZE, timeout)
                if (Mse.looksPlaintext(head)) {
                    if (encryption == Encryption.REQUIRED) {
                        throw WireException("peer opened in the clear and this client requires encryption")
                    }
                    val rest = readBy(socket, Handshake.SIZE - SNIFF_SIZE, timeout)
                    return AcceptedPeer(Handshake.decode(head + rest), null, ByteArray(0))
                }
                if (encryption == Encryption.PLAINTEXT) {
                    throw WireException("peer opened with an encrypted handshake and this client offers none")
                }
                return mseAccept(socket, head, torrents, timeout, encryption, random)
            } catch (failure: Throwable) {
                socket.closeQuietly()
                throw failure
            }
        }

        private fun mseAccept(
            socket: SocketChannel,
            head: ByteArray,
            torrents: () -> List<InfoHash>,
            timeout: Duration,
            encryption: Encryption,
            random: Random,
        ): AcceptedPeer {
            val stream = SocketByteStream(socket, timeout, prefix = head)
            val result =
                try {
                    Mse.accept(stream, torrents, random, allowPlaintext = encryption != Encryption.REQUIRED).second
                } finally {
                    stream.finish()
                }
            // What the dialler sent as `IA` is normally its handshake and nothing else. Normally is
            // not always: a peer may send less (and the handshake follows on the wire) or more (and
            // the rest is the first frame, which the connection is handed rather than losing).
            val carried = result.carried
            val bytes =
                if (carried.size >= Handshake.SIZE) {
                    carried.copyOfRange(0, Handshake.SIZE)
                } else {
                    carried + readBy(socket, Handshake.SIZE - carried.size, timeout).also { result.decrypt?.apply(it) }
                }
            val leftover =
                if (carried.size > Handshake.SIZE) carried.copyOfRange(Handshake.SIZE, carried.size) else ByteArray(0)
            return AcceptedPeer(Handshake.decode(bytes), result, leftover)
        }

        /** The other half: our handshake, and a connection reading from then on. */
        public fun answer(
            scope: CoroutineScope,
            socket: SocketChannel,
            accepted: AcceptedPeer,
            infoHash: InfoHash,
            peerId: PeerId,
            pool: BufferPool,
            blocks: BlockSource,
            reserved: ByteArray = Handshake.reservedBits(),
        ): SocketPeerConnection {
            try {
                val ours = Handshake(infoHash, peerId, reserved).encode()
                // The keystream is the connection's, and it starts here: these sixty-eight bytes
                // are the first thing it encrypts, and the writer picks it up mid-stream.
                accepted.crypto?.encrypt?.apply(ours)
                val out = ByteBuffer.wrap(ours)
                while (out.hasRemaining()) socket.write(out)

                val remote = socket.remoteAddress as InetSocketAddress
                val address = PeerAddress(remote.address.hostAddress, remote.port)
                return SocketPeerConnection(
                    address,
                    accepted.handshake,
                    socket,
                    pool,
                    blocks,
                    accepted.crypto,
                    accepted.carried,
                ).also { it.start(scope) }
            } catch (failure: Throwable) {
                socket.closeQuietly()
                throw failure
            }
        }

        private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}

/**
 * What a socket said before anything was answered: whose torrent, and in which dialect.
 *
 * It exists because the two questions arrive together on an encrypted connection and separately on
 * a plaintext one, and the caller — a process holding several torrents — has to route on the first
 * before it can answer with the second.
 */
public class AcceptedPeer internal constructor(
    public val handshake: Handshake,
    internal val crypto: MseResult?,
    /** Bytes of the peer's first frame that arrived inside its handshake; usually none. */
    internal val carried: ByteArray,
) {
    /** Whether what follows is encrypted, rather than merely obfuscated up to the handshake. */
    public val encrypted: Boolean get() = crypto?.encrypt != null
}

/**
 * The peer would not speak MSE — so this dial is over, and `PREFERRED` tries again in the clear.
 *
 * A distinct type because the fall-back must not fire for every failure: a peer that answered for
 * another torrent has answered, and dialling it a second time would learn the same thing twice.
 */
private class EncryptionRefused(
    address: PeerAddress,
    cause: Throwable,
) : IOException("$address did not answer an encrypted handshake: ${cause.message}", cause)

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
