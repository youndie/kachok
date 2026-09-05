package ru.workinprogress.kachok.cli

import ru.workinprogress.kachok.engine.InfoHash
import ru.workinprogress.kachok.engine.PeerId
import ru.workinprogress.kachok.engine.PieceIndex
import ru.workinprogress.kachok.engine.wire.Handshake
import ru.workinprogress.kachok.engine.wire.Message
import ru.workinprogress.kachok.engine.wire.PeerWire
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
 * a real socket proves rather more. It is deliberately simple — no choking policy, no rate limit,
 * unchoke on sight — because what is under test is the downloader.
 */
class SeedingPeer(
    private val infoHash: InfoHash,
    private val content: ByteArray,
    private val pieceLength: Int,
) : AutoCloseable {
    private val server: ServerSocketChannel =
        ServerSocketChannel.open().bind(InetSocketAddress("127.0.0.1", 0), BACKLOG)

    val port: Int = (server.localAddress as InetSocketAddress).port

    /** Requests served, so a test can tell "it downloaded" from "it had it already". */
    val served: ConcurrentLinkedQueue<Message.Request> = ConcurrentLinkedQueue()

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
        Handshake.decode(theirs.array())

        write(socket, Handshake(infoHash, PeerId("-SEED01-000000000000".encodeToByteArray())).encode())
        // A seed has everything, and says so before anything else (BEP 3).
        val bitfield = ByteArray((pieces + 7) / 8)
        (0 until pieces).forEach { bitfield[it / 8] = (bitfield[it / 8].toInt() or (0x80 ushr (it % 8))).toByte() }
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
            if (message is Message.Request) {
                served += message
                write(socket, PeerWire.encodePieceHeader(message.piece, message.begin, message.length))
                write(socket, block(message.piece, message.begin, message.length))
            }
        }
    }

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
        const val BACKLOG = 16
    }
}
