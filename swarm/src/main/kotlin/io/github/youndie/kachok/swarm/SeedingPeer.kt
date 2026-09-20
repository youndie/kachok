package io.github.youndie.kachok.swarm

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.PeerId
import io.github.youndie.kachok.engine.PieceIndex
import io.github.youndie.kachok.engine.wire.ExtensionHandshake
import io.github.youndie.kachok.engine.wire.Handshake
import io.github.youndie.kachok.engine.wire.Message
import io.github.youndie.kachok.engine.wire.MetadataMessage
import io.github.youndie.kachok.engine.wire.PeerWire
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A peer that actually has the torrent and will serve it.
 *
 * This is the other half of an end-to-end test: a client that downloads from a mock proves that
 * the mock agrees with the client, while a client that downloads from something speaking BEP 3 on
 * a real socket proves rather more. It is deliberately simple — no choking policy, unchoke on
 * sight — because what is under test is the downloader.
 *
 * It does have two knobs that are not simplicity but the opposite, and both exist so that a stand
 * can pose a question this one cannot otherwise be asked: [holds], because on a swarm of seeds that
 * all have everything no piece is rarer than any other and every picker makes the same requests in
 * a different order, and [bytesPerSecond], because at loopback speed a download measures the kernel
 * and the disk ([B-123](../../../../../../../docs/backlog/B-123-a-seed-that-holds-part-of-the-torrent.md)).
 */
public class SeedingPeer(
    private val infoHash: InfoHash,
    private val content: ByteArray,
    private val pieceLength: Int,
    /** Slows the seed down, so a test can interrupt a download that is genuinely in progress. */
    private val delayPerBlockMillis: Long = 0,
    /**
     * The pieces this seed has, or null for the seed that has the torrent.
     *
     * **Announced *and* enforced.** A seed that advertised a subset and served anything asked of it
     * would let the client under test cheat the very rule the subset exists to create, and the
     * cheating would look like a pass. A `request` for a piece outside this set is the connection
     * closed, which BEP 3 allows a peer to do and a real one does.
     */
    private val holds: Set<Int>? = null,
    /**
     * Bytes a second **across every connection this seed has**, or zero for as fast as the socket
     * will go.
     *
     * **Shared and not per connection, and the difference is a whole scenario.** It was per
     * connection first, and a stand of one seed at "one client's worth of bandwidth" and four
     * clients then gave each of them that bandwidth in full: nobody had to trade with anybody, the
     * four downloads finished in the time one of them would have taken, and the comparison reported
     * no difference because there was no swarm in it
     * ([B-126](../../../../../../../docs/backlog/B-126-a-stand-with-more-than-one-leecher.md)). A
     * real seed has an uplink, not an uplink per peer.
     *
     * Applied by sleeping until this seed's shared budget allows the block that was just sent, which
     * makes the rate a floor on the download time and never a ceiling: nothing here can make a slow
     * machine faster, so a test may assert "took at least this long" and never "took at most".
     */
    private val bytesPerSecond: Long = 0,
    /**
     * Serve this many blocks and then go quiet, keeping the connection open.
     *
     * For a test that has to act on a download *while it is running*: a delay per block only makes
     * the race slower to lose, and a client that got faster — as it did when the encrypted dial
     * stopped waiting out a deadline — loses it again
     * ([B-113](../../../../../../../docs/backlog/B-113-shutdowntest-interrupts-a-download-that-has-already-finished.md)).
     * A seed that stops serving is a state a real swarm has, and it is a fact rather than a wager.
     */
    private val freezeAfterBlocks: Int? = null,
    /** BEP 10's reserved bit, so a test can see what this client sends a peer that asks for it. */
    private val extensionProtocol: Boolean = false,
    /** BEP 6's, for the same reason. */
    private val fastExtension: Boolean = false,
    /**
     * Loopback for the tests. A run-time image being checked from inside a container has to reach
     * this seed from outside this machine's loopback, and nothing else does.
     */
    private val bindAddress: String = "127.0.0.1",
    /**
     * The `info` dictionary this seed will serve over BEP 9, or null for a seed that will not.
     *
     * A magnet carries none of the torrent, so a client that only has one has to ask a peer for it
     * — which means a test of that path needs a peer on a real socket that answers, not an
     * in-process fake. This is that peer.
     */
    private val metadata: ByteArray? = null,
) : AutoCloseable {
    private val server: ServerSocketChannel =
        ServerSocketChannel.open().bind(InetSocketAddress(bindAddress, 0), BACKLOG)

    public val port: Int = (server.localAddress as InetSocketAddress).port

    /**
     * This seed's own id, and it must be its own: **twenty bytes that name the peer, not the fake.**
     *
     * Every seed used to hand out the same `-SEED01-000000000000`, which nobody noticed while a
     * stand had one of them. The first stand with five was a download that stalled at seven pieces
     * of eight with one peer connected: the client saw five connections claiming one id and hung up
     * on four of them as duplicates, which is
     * [B-111](../../../../../../../docs/backlog/B-111-two-connections-to-the-same-peer.md) working exactly as
     * written. The port is what makes an id unique here, because it is what makes the peer unique.
     */
    public val peerId: PeerId = PeerId("-SEED01-%012d".format(port).encodeToByteArray())

    /** Requests served, so a test can tell "it downloaded" from "it had it already". */
    public val served: ConcurrentLinkedQueue<Message.Request> = ConcurrentLinkedQueue()

    /**
     * Requests for pieces this seed does not hold, which it hung up on.
     *
     * Empty is the interesting value: a client that reads a `bitfield` asks for nothing outside it,
     * so anything in here is either a client that ignored the announcement or a stand whose subsets
     * do not mean what the test thinks they mean.
     */
    public val refused: ConcurrentLinkedQueue<Message.Request> = ConcurrentLinkedQueue()

    /** Extended messages received, in order: the first one is BEP 10's handshake or nothing is. */
    public val extended: ConcurrentLinkedQueue<Message.Extended> = ConcurrentLinkedQueue()

    /** Everything the client said, so a test can ask what it opened with. */
    public val received: ConcurrentLinkedQueue<Message> = ConcurrentLinkedQueue()

    private val sockets = ConcurrentLinkedQueue<SocketChannel>()

    private val pieces: Int = (content.size + pieceLength - 1) / pieceLength

    init {
        Thread.ofVirtual().name("seed-acceptor").start {
            while (server.isOpen) {
                val socket =
                    try {
                        server.accept()
                    } catch (closed: IOException) {
                        break
                    }
                sockets += socket
                Thread.ofVirtual().start {
                    try {
                        serve(socket)
                    } catch (ended: IOException) {
                        // The client hung up or the test finished; both end a seed's day.
                    }
                }
            }
        }
    }

    private fun serve(socket: SocketChannel) {
        val theirs = ByteBuffer.allocate(Handshake.SIZE)
        while (theirs.hasRemaining()) if (socket.read(theirs) < 0) return
        // **A client with encryption switched off hangs up on what it cannot read**, and this fake
        // is one: since B-100 kachok opens with an MSE public key by default and falls back to the
        // clear when the peer will not answer it. A fake that read the key as a handshake and
        // replied anyway made every dial wait out the handshake deadline before the fall-back —
        // slow here, and not what a real plaintext client does there.
        if (!theirs.array().copyOfRange(0, PROTOCOL_HEADER.size).contentEquals(PROTOCOL_HEADER)) {
            socket.close()
            return
        }
        Handshake.decode(theirs.array())

        write(
            socket,
            Handshake(
                infoHash,
                peerId,
                Handshake.reservedBits(
                    extensionProtocol = extensionProtocol,
                    fastExtension = fastExtension,
                ),
            ).encode(),
        )
        // What this seed has, said before anything else (BEP 3; BEP 6 lets a complete peer send
        // one byte instead of a bitfield, and this fake keeps sending the bitfield on purpose —
        // what is under test is what the *client* opens with).
        val bitfield = ByteArray((pieces + 7) / 8)
        (0 until pieces).forEach { index ->
            if (has(index)) {
                bitfield[index / 8] = (bitfield[index / 8].toInt() or (0x80 ushr (index % 8))).toByte()
            }
        }
        write(socket, PeerWire.encode(Message.Bitfield(bitfield)))
        write(socket, PeerWire.encode(Message.Unchoke))

        val length = ByteBuffer.allocate(PeerWire.LENGTH_PREFIX_SIZE)
        while (true) {
            length.clear()
            while (length.hasRemaining()) if (socket.read(length) < 0) return
            val size = length.flip().int
            if (size == 0) continue
            val frame = ByteBuffer.allocate(size)
            while (frame.hasRemaining()) if (socket.read(frame) < 0) return
            val message = PeerWire.decode(frame.array())
            received += message
            if (message is Message.Extended) {
                extended += message
                if (message.extensionId == ExtensionHandshake.HANDSHAKE_ID) {
                    clientMetadataId =
                        ExtensionHandshake
                            .decode(message.payload)
                            .extensions[ExtensionHandshake.UT_METADATA] ?: clientMetadataId
                }
            }
            if (message is Message.Extended) {
                serveExtended(socket, message)
                continue
            }
            if (message is Message.Request) {
                if (!has(message.piece.value)) {
                    // Hanging up rather than ignoring it: a peer that stays silent about a piece it
                    // announced it had is a different fake — the frozen one below — and a test that
                    // meant "this seed has not got it" would be given "this seed is slow".
                    refused += message
                    socket.close()
                    return
                }
                if (freezeAfterBlocks != null && served.size >= freezeAfterBlocks) {
                    // Not an answer and not a hang-up: the client keeps its connection, its
                    // outstanding requests and everything it has already written to the disk,
                    // which is the state a test about interrupting a download needs it in.
                    continue
                }
                if (delayPerBlockMillis > 0) Thread.sleep(delayPerBlockMillis)
                served += message
                write(socket, PeerWire.encodePieceHeader(message.piece, message.begin, message.length))
                write(socket, block(message.piece, message.begin, message.length))
                pace(message.length)
            }
        }
    }

    /**
     * BEP 10's handshake, then BEP 9's blocks.
     *
     * The extension id this seed asks to be addressed by is its own choice and deliberately not
     * the client's: `m` is a per-peer mapping, and a fetcher that assumed both ends used the same
     * number would work against itself and nothing else.
     */
    private fun serveExtended(
        socket: SocketChannel,
        message: Message.Extended,
    ) {
        if (message.extensionId == ExtensionHandshake.HANDSHAKE_ID) {
            val theirs =
                ExtensionHandshake(
                    extensions =
                        if (metadata != null) mapOf(ExtensionHandshake.UT_METADATA to OUR_METADATA_ID) else emptyMap(),
                    metadataSize = metadata?.size,
                )
            write(
                socket,
                PeerWire.encode(Message.Extended(ExtensionHandshake.HANDSHAKE_ID, theirs.encode())),
            )
            return
        }
        val bytes = metadata ?: return
        val request = MetadataMessage.decode(message.payload)
        if (request.type != MetadataMessage.REQUEST) return
        val from = request.piece * MetadataMessage.BLOCK_SIZE
        if (from >= bytes.size) return
        val to = minOf(from + MetadataMessage.BLOCK_SIZE, bytes.size)
        val reply = MetadataMessage.data(request.piece, bytes.size, bytes.copyOfRange(from, to))
        // Addressed with the id the *client* advertised, which is what its handshake was for.
        write(socket, PeerWire.encode(Message.Extended(clientMetadataId, reply.encode())))
    }

    /** What the client asked to be addressed by, learned from its own extension handshake. */
    @Volatile
    private var clientMetadataId: Int = ExtensionHandshake.ID_UT_METADATA

    /** Whether this seed holds this piece; a seed given no subset holds the torrent. */
    private fun has(piece: Int): Boolean = holds?.contains(piece) ?: true

    /**
     * Holds this connection until the seed's shared budget has paid for [bytes], after sending them.
     *
     * After and not before, and the difference is a whole block: a rate applied before the write
     * delays the first byte of the download by a block's worth of nothing, and a test that measures
     * from its own `start` would count that as transfer time.
     *
     * The budget is one instant — when this seed is next free to have sent something — advanced
     * under a lock by what each block costs. Two connections sending at once therefore queue behind
     * each other exactly as they would behind one uplink, and neither of them sleeps while holding
     * the lock.
     */
    private fun pace(bytes: Int) {
        if (bytesPerSecond <= 0) return
        val cost = bytes.toLong() * NANOS_PER_SECOND / bytesPerSecond
        val until =
            synchronized(budget) {
                val now = System.nanoTime()
                // A seed that has been idle does not bank the time it was idle for.
                val from = if (freeAt - now > 0) freeAt else now
                freeAt = from + cost
                freeAt
            }
        val wait = until - System.nanoTime()
        if (wait > 0) Thread.sleep(wait / NANOS_PER_MILLI, (wait % NANOS_PER_MILLI).toInt())
    }

    /** The lock over [freeAt]; the sleep itself happens outside it. */
    private val budget = Any()

    /** `System.nanoTime` at which this seed will next be free to have sent a block. */
    private var freeAt: Long = System.nanoTime()

    private fun block(
        piece: PieceIndex,
        begin: Int,
        length: Int,
    ): ByteArray {
        val from = piece.value.toLong() * pieceLength + begin
        return content.copyOfRange(from.toInt(), (from + length).toInt())
    }

    private fun write(
        socket: SocketChannel,
        bytes: ByteArray,
    ) {
        val out = ByteBuffer.wrap(bytes)
        while (out.hasRemaining()) socket.write(out)
    }

    override fun close() {
        closeQuietly(server)
        sockets.forEach { closeQuietly(it) }
    }

    private fun closeQuietly(closeable: AutoCloseable) {
        try {
            closeable.close()
        } catch (ignored: IOException) {
            // Tearing down a fake seed; nothing left to tell anyone.
        }
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NANOS_PER_MILLI = 1_000_000L

        /** BEP 3's opener: the byte 19 and `BitTorrent protocol`. */

        private val PROTOCOL_HEADER: ByteArray =

            ByteArray(20).also {

                it[0] = 19

                "BitTorrent protocol".encodeToByteArray().copyInto(it, 1)
            }

        const val BACKLOG = 16

        /** This seed's own id for `ut_metadata`, chosen to differ from the client's default. */
        const val OUR_METADATA_ID = 3
    }
}
