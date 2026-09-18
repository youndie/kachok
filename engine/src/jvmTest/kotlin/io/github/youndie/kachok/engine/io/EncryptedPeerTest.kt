package io.github.youndie.kachok.engine.io

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.PeerId
import io.github.youndie.kachok.engine.PieceIndex
import io.github.youndie.kachok.engine.mse.Mse
import io.github.youndie.kachok.engine.peer.Encryption
import io.github.youndie.kachok.engine.peer.PeerAddress
import io.github.youndie.kachok.engine.peer.PeerEvent
import io.github.youndie.kachok.engine.wire.Handshake
import io.github.youndie.kachok.engine.wire.Message
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.channels.WritableByteChannel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Both ends of this client, over a real socket, with the handshake encrypted.
 *
 * `MseHandshakeTest` proves the exchange against an in-memory pipe and `MseInteropProbe` proves it
 * against libtorrent; neither touches a connection. This is the half B-100 was missing: the
 * keystreams actually applied to the wire, which is where the upload path loses `transferTo` and
 * where a plaintext peer must still be understood.
 */
class EncryptedPeerTest {
    private val infoHash = InfoHash(ByteArray(20) { (it * 7).toByte() })
    private val dialling = PeerId("-KA0001-dialler00000".encodeToByteArray())
    private val listening = PeerId("-KA0001-listener0000".encodeToByteArray())
    private val dispatchers = EngineDispatchers()
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val server: ServerSocketChannel =
        ServerSocketChannel.open().bind(InetSocketAddress("127.0.0.1", 0), BACKLOG)
    private val address =
        PeerAddress("127.0.0.1", (server.localAddress as InetSocketAddress).port)

    /** A block of something recognisable, big enough to cross more than one keystream chunk. */
    private val content = ByteArray(1 shl 14) { (it * 31 and 0xFF).toByte() }

    @AfterTest
    fun clean() {
        server.close()
        scope.cancel()
        dispatchers.close()
    }

    /** The one block this side serves, written straight at the channel the connection gives it. */
    private inner class OneBlock : BlockSource {
        override fun transferBlock(
            piece: PieceIndex,
            begin: Int,
            length: Int,
            target: WritableByteChannel,
        ): Long {
            val out = ByteBuffer.wrap(content, begin, length)
            var sent = 0L
            while (out.hasRemaining()) sent += target.write(out)
            return sent
        }
    }

    /** This client's own accepting half, on the listening socket, handed back when it is up. */
    private fun accepting(
        encryption: Encryption = Encryption.PREFERRED,
        blocks: BlockSource = NoBlocks,
    ): CompletableDeferred<SocketPeerConnection> {
        val ready = CompletableDeferred<SocketPeerConnection>()
        scope.launch {
            val socket = server.accept()
            try {
                ready.complete(
                    SocketPeerConnection.accept(
                        scope,
                        socket,
                        infoHash,
                        listening,
                        BufferPool(8),
                        blocks,
                        encryption = encryption,
                    ),
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (refused: Exception) {
                ready.completeExceptionally(refused)
            }
        }
        return ready
    }

    private suspend fun dial(encryption: Encryption = Encryption.PREFERRED): SocketPeerConnection =
        SocketPeerConnection.connect(
            scope,
            address,
            infoHash,
            dialling,
            BufferPool(8),
            NoBlocks,
            encryption = encryption,
        )

    @Test
    fun twoEndsOfThisClientNegotiateEncryptionAndAWholeBlockSurvivesIt(): Unit =
        runBlocking {
            val served = accepting(blocks = OneBlock())
            val dialled = dial()
            val accepted = served.await()

            assertTrue(dialled.encrypted, "the dialling side did not negotiate encryption")
            assertTrue(accepted.encrypted, "the accepting side did not negotiate encryption")
            // The handshakes crossed inside the encrypted exchange: each side is holding the
            // other's peer id, which is the one thing a mixed-up keystream cannot produce.
            assertContentEquals(listening.bytes, dialled.handshake.peerId.bytes)
            assertContentEquals(dialling.bytes, accepted.handshake.peerId.bytes)

            accepted.send(Message.Unchoke)
            val message = withTimeout(TIMEOUT) { dialled.events.receive() }
            assertTrue(message is PeerEvent.Received && message.message === Message.Unchoke, "$message")

            // The block, which on an encrypted connection cannot go through `transferTo` and is
            // copied through the keystream instead. Sixteen kilobytes of it.
            accepted.sendBlock(PieceIndex(3), 0, content.size)
            val block = withTimeout(TIMEOUT) { dialled.events.receive() }
            assertTrue(block is PeerEvent.BlockReceived, "$block")
            val bytes = ByteArray(block.block.length)
            (block.block as PooledBlock).bytes.get(bytes)
            block.block.release()
            assertContentEquals(content, bytes, "the block did not survive the keystream")

            dialled.close()
            accepted.close()
        }

    @Test
    fun aPeerThatWillNotSpeakMseIsDialledAgainInTheClear(): Unit =
        runBlocking {
            // What a client with encryption switched off does: it reads a handshake, and what it
            // gets instead is the top of a public key, so it hangs up.
            scope.launch {
                // Until the teardown closes the listening socket under it, which is this loop's
                // only ending and must not surface as an uncaught exception in whatever test runs
                // next — it did, once, and was reported against a test three files away.
                try {
                    while (true) {
                        val socket = server.accept()
                        if (!plaintextOnly(socket)) socket.close()
                    }
                } catch (shutDown: java.nio.channels.AsynchronousCloseException) {
                    // The test is over.
                } catch (shutDown: java.nio.channels.ClosedChannelException) {
                    // The same, reported the other way.
                }
            }

            val dialled = dial()

            assertFalse(dialled.encrypted, "the fall-back did not happen: this connection is encrypted")
            assertContentEquals(listening.bytes, dialled.handshake.peerId.bytes)
            dialled.close()
        }

    /** Answers a plaintext handshake and nothing else; false when the peer opened another way. */
    private fun plaintextOnly(socket: SocketChannel): Boolean {
        val head = ByteBuffer.allocate(SNIFF)
        while (head.hasRemaining()) if (socket.read(head) < 0) return false
        if (!Mse.looksPlaintext(head.array())) return false
        val rest = ByteBuffer.allocate(Handshake.SIZE - SNIFF)
        while (rest.hasRemaining()) if (socket.read(rest) < 0) return false
        val ours = ByteBuffer.wrap(Handshake(infoHash, listening).encode())
        while (ours.hasRemaining()) socket.write(ours)
        return true
    }

    @Test
    fun aPlaintextDiallerIsStillUnderstood(): Unit =
        runBlocking {
            val served = accepting()
            val dialled = dial(Encryption.PLAINTEXT)
            val accepted = served.await()

            assertFalse(dialled.encrypted)
            assertFalse(accepted.encrypted)
            assertContentEquals(dialling.bytes, accepted.handshake.peerId.bytes)
            dialled.close()
            accepted.close()
        }

    @Test
    fun aClientThatRequiresEncryptionTurnsAPlaintextPeerAway(): Unit =
        runBlocking {
            val served = accepting(encryption = Encryption.REQUIRED)
            // The dialler writes its sixty-eight bytes and waits for an answer that never comes.
            assertFailsWith<Exception> { dial(Encryption.PLAINTEXT) }
            val refused = assertFailsWith<Exception> { served.await() }
            assertTrue(
                "requires encryption" in refused.message.orEmpty(),
                "the refusal did not say why: ${refused.message}",
            )
        }

    private companion object {
        const val BACKLOG = 4
        const val SNIFF = 20
        const val TIMEOUT = 10_000L
    }
}
