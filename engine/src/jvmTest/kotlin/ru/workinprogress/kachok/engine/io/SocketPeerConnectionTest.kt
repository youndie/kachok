package ru.workinprogress.kachok.engine.io

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.workinprogress.kachok.engine.InfoHash
import ru.workinprogress.kachok.engine.PeerId
import ru.workinprogress.kachok.engine.PieceIndex
import ru.workinprogress.kachok.engine.peer.PeerEvent
import ru.workinprogress.kachok.engine.wire.Handshake
import ru.workinprogress.kachok.engine.wire.Message
import ru.workinprogress.kachok.engine.wire.PeerWire
import ru.workinprogress.kachok.engine.wire.WireException
import java.nio.ByteBuffer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The acceptance criteria of B-07, against a local fake peer.
 *
 * These use `runBlocking` rather than `runTest`: the sockets are real, the connection runs on the
 * engine's own virtual-thread dispatcher, and a test scheduler with virtual time has nothing to
 * contribute except confusion about which clock a timeout is on.
 */
class SocketPeerConnectionTest {
    private val infoHash = InfoHash(ByteArray(20) { it.toByte() })
    private val otherHash = InfoHash(ByteArray(20) { (it + 1).toByte() })
    private val peerId = PeerId("-KA0001-0123456789AB".encodeToByteArray())

    private val dispatchers = EngineDispatchers()
    private val scope = CoroutineScope(dispatchers.io + SupervisorJob())

    @AfterTest
    fun shutDown() {
        scope.cancel()
        dispatchers.close()
    }

    @Test
    fun aPeerAnsweringForAnotherTorrentIsDropped() =
        runBlocking {
            FakePeer(infoHash = otherHash).use { peer ->
                val thrown =
                    assertFailsWith<WireException> {
                        SocketPeerConnection.connect(scope, peer.address, infoHash, peerId, BufferPool(capacity = 4))
                    }
                assertContains(thrown.message ?: "", "another torrent")
            }
        }

    @Test
    fun theHandshakeWeSendIsTheOneBep3Describes() =
        runBlocking {
            FakePeer(infoHash = infoHash).use { peer ->
                val connection =
                    SocketPeerConnection.connect(
                        scope,
                        peer.address,
                        infoHash,
                        peerId,
                        BufferPool(capacity = 4),
                        Handshake.reservedBits(extensionProtocol = true),
                    )
                withTimeout(TIMEOUT) {
                    while (peer.handshakes.isEmpty()) Thread.sleep(1)
                }
                val theirs = peer.handshakes.first()
                assertTrue(theirs.infoHash.bytes.contentEquals(infoHash.bytes))
                assertTrue(theirs.peerId.bytes.contentEquals(peerId.bytes))
                assertTrue(theirs.supportsExtensionProtocol, "the reserved bits we set reached the peer")
                connection.close()
            }
        }

    @Test
    fun aBlockArrivesInAPoolBufferAndIsReleasedByItsReceiver() =
        runBlocking {
            val block = ByteArray(PeerWire.BLOCK_SIZE) { (it and 0x7F).toByte() }
            val pool = BufferPool(capacity = 4)
            FakePeer(
                infoHash = infoHash,
                afterHandshake = { socket ->
                    FakePeer.write(socket, PeerWire.encodePieceHeader(PieceIndex(3), 32768, block.size))
                    FakePeer.write(socket, block)
                    FakePeer.park(socket)
                },
            ).use { peer ->
                val connection = SocketPeerConnection.connect(scope, peer.address, infoHash, peerId, pool)
                val event = withTimeout(TIMEOUT) { connection.events.receive() }
                val received = assertIs<PeerEvent.BlockReceived>(event)
                val pooledBlock = received.block as PooledBlock

                assertEquals(3, pooledBlock.piece.value)
                assertEquals(32768, pooledBlock.begin)
                assertEquals(block.size, pooledBlock.length)
                assertEquals(1, pool.outstanding, "the block is in a pool buffer, not a copy of one")
                assertTrue(pooledBlock.buffer.isDirect)

                val copy = ByteArray(pooledBlock.length)
                pooledBlock.buffer.duplicate().get(copy)
                assertTrue(copy.contentEquals(block))

                pooledBlock.release()
                assertEquals(0, pool.outstanding)
                connection.close()
            }
        }

    @Test
    fun ordinaryMessagesArriveDecodedAndKeepAlivesAreSeen() =
        runBlocking {
            FakePeer(
                infoHash = infoHash,
                afterHandshake = { socket ->
                    FakePeer.write(socket, PeerWire.encode(Message.Unchoke))
                    FakePeer.write(socket, PeerWire.encode(Message.KeepAlive))
                    FakePeer.write(socket, PeerWire.encode(Message.Have(PieceIndex(7))))
                    FakePeer.park(socket)
                },
            ).use { peer ->
                val connection = SocketPeerConnection.connect(scope, peer.address, infoHash, peerId, BufferPool(4))
                withTimeout(TIMEOUT) {
                    assertTrue(assertIs<PeerEvent.Received>(connection.events.receive()).message === Message.Unchoke)
                    assertTrue(assertIs<PeerEvent.Received>(connection.events.receive()).message === Message.KeepAlive)
                    val have = assertIs<PeerEvent.Received>(connection.events.receive()).message as Message.Have
                    assertEquals(7, have.piece.value)
                }
                connection.close()
            }
        }

    @Test
    fun whatWeSendReachesThePeerInOrder() =
        runBlocking {
            val received = ArrayDeque<Message>()
            FakePeer(
                infoHash = infoHash,
                afterHandshake = { socket ->
                    val length = ByteBuffer.allocate(4)
                    repeat(2) {
                        length.clear()
                        while (length.hasRemaining()) if (socket.read(length) < 0) return@repeat
                        val size = length.flip().int
                        val frame = ByteBuffer.allocate(size)
                        while (frame.hasRemaining()) if (socket.read(frame) < 0) return@repeat
                        synchronized(received) { received += PeerWire.decode(frame.array()) }
                    }
                    FakePeer.park(socket)
                },
            ).use { peer ->
                val connection = SocketPeerConnection.connect(scope, peer.address, infoHash, peerId, BufferPool(4))
                connection.send(Message.Interested)
                connection.send(Message.Request(PieceIndex(1), 0, PeerWire.BLOCK_SIZE))
                withTimeout(TIMEOUT) {
                    while (synchronized(received) { received.size } < 2) Thread.sleep(1)
                }
                synchronized(received) {
                    assertTrue(received[0] === Message.Interested)
                    assertEquals(1, (received[1] as Message.Request).piece.value)
                }
                connection.close()
            }
        }

    @Test
    fun aPeerHangingUpIsAnOrderlyClose() =
        runBlocking {
            FakePeer(infoHash = infoHash, afterHandshake = { it.close() }).use { peer ->
                val connection = SocketPeerConnection.connect(scope, peer.address, infoHash, peerId, BufferPool(4))
                val event = withTimeout(TIMEOUT) { connection.events.receive() }
                assertNull(assertIs<PeerEvent.Closed>(event).cause, "a peer hanging up is not a failure")
                connection.close()
            }
        }

    /**
     * The claim the whole transport rests on, asserted rather than assumed: a thousand connections
     * parked on a blocking read cost a thousand *virtual* threads and no platform threads beyond
     * the carriers the scheduler already had.
     */
    @Test
    fun aThousandIdleConnectionsHoldNoExtraPlatformThreads() =
        runBlocking {
            val pool = BufferPool(capacity = 16)
            FakePeer(infoHash = infoHash).use { peer ->
                val before = platformThreads()
                val connections =
                    (1..CONNECTIONS).map {
                        SocketPeerConnection.connect(scope, peer.address, infoHash, peerId, pool)
                    }
                try {
                    val after = platformThreads()
                    assertEquals(CONNECTIONS, connections.size)
                    assertTrue(
                        after - before <= CARRIER_HEADROOM,
                        "$CONNECTIONS parked connections added ${after - before} platform threads; " +
                            "more than $CARRIER_HEADROOM means they are not parking as virtual threads",
                    )
                } finally {
                    connections.forEach { it.close() }
                }
            }
        }

    private fun platformThreads(): Int = Thread.getAllStackTraces().keys.count { !it.isVirtual }

    private inline fun <reified T> assertIs(value: Any?): T {
        assertTrue(value is T, "expected ${T::class.simpleName}, got $value")
        return value
    }

    private companion object {
        const val TIMEOUT = 10_000L
        const val CONNECTIONS = 1000

        /**
         * The carrier pool is sized to the processors and may add a few threads for reasons that
         * have nothing to do with these sockets. What this bound rules out is one platform thread
         * per connection, which is what the design exists to avoid.
         */
        val CARRIER_HEADROOM: Int = Runtime.getRuntime().availableProcessors() + 32
    }
}
