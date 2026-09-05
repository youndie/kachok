package ru.workinprogress.kachok.engine.io

import ru.workinprogress.kachok.engine.InfoHash
import ru.workinprogress.kachok.engine.PeerId
import ru.workinprogress.kachok.engine.peer.PeerAddress
import ru.workinprogress.kachok.engine.wire.Handshake
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A peer that is not this client: an ephemeral local listener that performs the handshake and then
 * does whatever the test tells it to.
 *
 * It runs on virtual threads so that a test opening a thousand connections to it does not itself
 * create the platform threads that test is counting.
 */
class FakePeer(
    private val infoHash: InfoHash,
    private val peerId: PeerId = PeerId("-FAKE01-000000000000".encodeToByteArray()),
    private val reserved: ByteArray = Handshake.reservedBits(),
    private val afterHandshake: (SocketChannel) -> Unit = { park(it) },
) : AutoCloseable {
    private val server: ServerSocketChannel =
        ServerSocketChannel.open().bind(InetSocketAddress("127.0.0.1", 0), BACKLOG)

    val address: PeerAddress = PeerAddress("127.0.0.1", (server.localAddress as InetSocketAddress).port)

    /** What each accepted connection said in its handshake, for tests that assert on our side. */
    val handshakes: ConcurrentLinkedQueue<Handshake> = ConcurrentLinkedQueue()

    private val accepted = ConcurrentLinkedQueue<SocketChannel>()

    /** Kept rather than swallowed, so a test that wants to know can look. */
    val failures: ConcurrentLinkedQueue<IOException> = ConcurrentLinkedQueue()

    private fun serve(socket: SocketChannel) {
        val theirs = ByteBuffer.allocate(Handshake.SIZE)
        while (theirs.hasRemaining()) {
            if (socket.read(theirs) < 0) return
        }
        handshakes += Handshake.decode(theirs.array())
        val ours = ByteBuffer.wrap(Handshake(infoHash, peerId, reserved).encode())
        while (ours.hasRemaining()) socket.write(ours)
        afterHandshake(socket)
    }

    private val acceptor: Thread =
        Thread.ofVirtual().name("fake-peer-acceptor").start {
            while (server.isOpen) {
                val socket =
                    try {
                        server.accept()
                    } catch (closed: IOException) {
                        failures += closed
                        break
                    }
                accepted += socket
                Thread.ofVirtual().start {
                    try {
                        serve(socket)
                    } catch (closed: IOException) {
                        // The test closed the peer, or the client hung up. Both are how these
                        // tests end; a fake peer with an opinion about it would fail them.
                        failures += closed
                    }
                }
            }
        }

    override fun close() {
        closeQuietly(server)
        accepted.forEach { closeQuietly(it) }
        acceptor.interrupt()
    }

    private fun closeQuietly(closeable: AutoCloseable) {
        try {
            closeable.close()
        } catch (ignored: IOException) {
            // Tearing down a fake peer; there is nothing left to tell anyone.
        }
    }

    companion object {
        private const val BACKLOG = 2048

        /** Stay connected and say nothing, which is what an idle peer does. */
        fun park(socket: SocketChannel) {
            val ignored = ByteBuffer.allocate(1)
            while (socket.isOpen && socket.read(ignored) >= 0) ignored.clear()
        }

        /** Writes bytes exactly as given, so a test can hand a peer a malformed frame on purpose. */
        fun write(
            socket: SocketChannel,
            bytes: ByteArray,
        ) {
            val out = ByteBuffer.wrap(bytes)
            while (out.hasRemaining()) socket.write(out)
        }
    }
}
