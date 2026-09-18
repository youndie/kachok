package io.github.youndie.kachok.engine.io

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.PeerId
import io.github.youndie.kachok.engine.PieceIndex
import io.github.youndie.kachok.engine.peer.PeerEvent
import io.github.youndie.kachok.engine.wire.Handshake
import io.github.youndie.kachok.engine.wire.Message
import io.github.youndie.kachok.engine.wire.PeerWire
import io.github.youndie.kachok.engine.wire.WireException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
    fun aPeerThatNeverAnswersGivesUpRatherThanHoldingTheSlot(): Unit =
        runBlocking {
            // 203.0.113.0/24 is TEST-NET-3 (RFC 5737): reserved for documentation, routed nowhere,
            // so a connection there hangs rather than being refused — which is what a dead peer in
            // a real swarm does. Without a timeout this call waits for the operating system,
            // which is minutes, and holds one of the session's connection slots for all of it.
            val started = System.nanoTime()
            assertFailsWith<java.io.IOException> {
                SocketPeerConnection.connect(
                    scope,
                    io.github.youndie.kachok.engine.peer
                        .PeerAddress("203.0.113.1", 6881),
                    infoHash,
                    peerId,
                    BufferPool(capacity = 4),
                    NoBlocks,
                    connectTimeout = 1.seconds,
                )
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsed < 5_000, "the dial took ${elapsed}ms; the timeout was not applied")
        }

    @Test
    fun aPeerAnsweringForAnotherTorrentIsDropped(): Unit =
        runBlocking {
            FakePeer(infoHash = otherHash).use { peer ->
                val thrown =
                    assertFailsWith<WireException> {
                        SocketPeerConnection.connect(
                            scope,
                            peer.address,
                            infoHash,
                            peerId,
                            BufferPool(capacity = 4),
                            NoBlocks,
                        )
                    }
                assertContains(thrown.message ?: "", "another torrent")
            }
        }

    @Test
    fun theHandshakeWeSendIsTheOneBep3Describes(): Unit =
        runBlocking {
            FakePeer(infoHash = infoHash).use { peer ->
                val connection =
                    SocketPeerConnection.connect(
                        scope,
                        peer.address,
                        infoHash,
                        peerId,
                        BufferPool(capacity = 4),
                        NoBlocks,
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
    fun aBlockArrivesInAPoolBufferAndIsReleasedByItsReceiver(): Unit =
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
                val connection = SocketPeerConnection.connect(scope, peer.address, infoHash, peerId, pool, NoBlocks)
                val event = withTimeout(TIMEOUT) { connection.events.receive() }
                val received = assertIs<PeerEvent.BlockReceived>(event)
                val pooledBlock = received.block as PooledBlock

                assertEquals(3, pooledBlock.piece.value)
                assertEquals(32768, pooledBlock.begin)
                assertEquals(block.size, pooledBlock.length)
                assertEquals(1, pool.outstanding, "the block is in a pool buffer, not a copy of one")
                assertTrue(pooledBlock.bytes.isDirect)

                val copy = ByteArray(pooledBlock.length)
                pooledBlock.bytes.duplicate().get(copy)
                assertTrue(copy.contentEquals(block))

                pooledBlock.release()
                assertEquals(0, pool.outstanding)
                connection.close()
            }
        }

    @Test
    fun ordinaryMessagesArriveDecodedAndKeepAlivesAreSeen(): Unit =
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
                val connection =
                    SocketPeerConnection.connect(
                        scope,
                        peer.address,
                        infoHash,
                        peerId,
                        BufferPool(4),
                        NoBlocks,
                    )
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
    fun whatWeSendReachesThePeerInOrder(): Unit =
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
                val connection =
                    SocketPeerConnection.connect(
                        scope,
                        peer.address,
                        infoHash,
                        peerId,
                        BufferPool(4),
                        NoBlocks,
                    )
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

    /**
     * A peer that keeps the socket open and reads nothing must not be able to suspend whoever
     * sends to it: the queue refuses, the connection is closed, and the caller gets an exception
     * within a bounded time rather than a wait with no end.
     */
    @Test
    fun aPeerThatStopsReadingIsClosedRatherThanWaitedFor(): Unit =
        runBlocking {
            FakePeer(infoHash = infoHash, afterHandshake = { socket -> FakePeer.park(socket) }).use { peer ->
                val connection =
                    SocketPeerConnection.connect(
                        scope,
                        peer.address,
                        infoHash,
                        peerId,
                        BufferPool(4),
                        NoBlocks,
                    )
                // A megabyte a message: the kernel's buffers take a few, the queue takes
                // sixty-four, and the one after that has nowhere to go.
                val heavy = Message.Bitfield(ByteArray(1 shl 20))
                val refused =
                    withTimeout(TIMEOUT) {
                        var thrown: Exception? = null
                        repeat(200) {
                            try {
                                connection.send(heavy)
                            } catch (gone: java.io.IOException) {
                                thrown = gone
                                return@withTimeout thrown
                            }
                        }
                        thrown
                    }
                assertTrue(refused != null, "two hundred unread megabytes were queued without complaint")
                assertTrue("stopped reading" in refused.message.orEmpty(), refused.message)
                // Closed by the connection itself: the event stream ends.
                withTimeout(TIMEOUT) {
                    for (event in connection.events) {
                        // Drained until the channel closes; nothing in it matters here.
                    }
                }
            }
        }

    @Test
    fun aPeerHangingUpIsAnOrderlyClose(): Unit =
        runBlocking {
            FakePeer(infoHash = infoHash, afterHandshake = { it.close() }).use { peer ->
                val connection =
                    SocketPeerConnection.connect(
                        scope,
                        peer.address,
                        infoHash,
                        peerId,
                        BufferPool(4),
                        NoBlocks,
                    )
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
    fun aThousandIdleConnectionsHoldNoExtraPlatformThreads(): Unit =
        runBlocking {
            val pool = BufferPool(capacity = 16)
            FakePeer(infoHash = infoHash).use { peer ->
                val before = platformThreads()
                val connections =
                    (1..CONNECTIONS).map {
                        SocketPeerConnection.connect(scope, peer.address, infoHash, peerId, pool, NoBlocks)
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

    /**
     * B-96: a peer that accepts the connection and then says nothing.
     *
     * The connect timeout does not cover this — the TCP handshake succeeded — and before this item
     * the read that follows had no deadline at all, so the dial never ended: the address stayed out
     * of `connected` and out of `failed`, and the session redialled it at every top-up.
     */
    @Test
    fun aPeerThatAcceptsAndThenSaysNothingEndsTheDialAtTheDeadline(): Unit =
        runBlocking {
            SilentListener().use { listener ->
                val started = System.nanoTime()
                assertFailsWith<java.net.SocketTimeoutException> {
                    SocketPeerConnection.connect(
                        scope,
                        listener.address,
                        infoHash,
                        peerId,
                        BufferPool(capacity = 4),
                        NoBlocks,
                        handshakeTimeout = 700.milliseconds,
                    )
                }
                val elapsed = (System.nanoTime() - started) / 1_000_000
                assertTrue(elapsed in 500..4_000, "the dial ended after ${elapsed}ms, not at the deadline")
            }
        }

    /**
     * B-96: the deadline belongs to the handshake, not to each read of it.
     *
     * **A peer that stops dead does not test this, and the first version of this test did exactly
     * that.** `SO_TIMEOUT` is per read, so a peer that sends half and then falls silent expires it
     * anyway — one interval later — and that test passed with or without the total deadline. A
     * peer that keeps dribbling is what separates them: a byte every 300 ms renews a 600 ms
     * per-read timeout for ever, and would take 68 × 300 ms to finish if it ever did. The total
     * deadline ends it at 600 ms.
     */
    @Test
    fun aPeerThatDribblesTheHandshakeDoesNotRenewTheDeadline(): Unit =
        runBlocking {
            SilentListener(dribbleEvery = 300.milliseconds).use { listener ->
                val started = System.nanoTime()
                val thrown =
                    assertFailsWith<java.net.SocketTimeoutException> {
                        SocketPeerConnection.connect(
                            scope,
                            listener.address,
                            infoHash,
                            peerId,
                            BufferPool(capacity = 4),
                            NoBlocks,
                            handshakeTimeout = 600.milliseconds,
                        )
                    }
                val elapsed = (System.nanoTime() - started) / 1_000_000
                assertTrue(
                    elapsed < 3_000,
                    "the dial took ${elapsed}ms: the deadline is being renewed by every byte that arrives",
                )
                // Bytes did arrive, so this is not the silent case passing under another name.
                assertContains(thrown.message ?: "", "of 68 handshake bytes")
                assertFalse((thrown.message ?: "").startsWith("peer sent 0 "), "no byte arrived at all")
            }
        }

    /**
     * The positive control, and the reason it is here.
     *
     * The handshake is now read through the socket's adapted `InputStream` so that `SO_TIMEOUT`
     * applies to it, and the wire that follows is read through the channel. That only works because
     * the adaptor does not read ahead — if it buffered, the first message after the handshake would
     * be eaten and the symptom would be a peer that connects and then never says anything, which is
     * indistinguishable from the bug this item fixed. A timeout test alone would stay green
     * through that.
     */
    @Test
    fun theFirstWireMessageAfterTheHandshakeIsNotSwallowedByTheAdaptor(): Unit =
        runBlocking {
            FakePeer(
                infoHash = infoHash,
                afterHandshake = { socket ->
                    FakePeer.write(socket, PeerWire.encode(Message.Unchoke))
                    FakePeer.write(socket, PeerWire.encode(Message.Have(PieceIndex(7))))
                    FakePeer.park(socket)
                },
            ).use { peer ->
                val connection =
                    SocketPeerConnection.connect(
                        scope,
                        peer.address,
                        infoHash,
                        peerId,
                        BufferPool(capacity = 4),
                        NoBlocks,
                    )
                val seen =
                    withTimeout(5.seconds) {
                        listOf(connection.events.receive(), connection.events.receive())
                    }
                // `Message.Have` is a plain class, not a data one, so the list is compared by
                // what the messages carry rather than by equality they do not define.
                val messages = seen.map { (it as PeerEvent.Received).message }
                assertEquals(
                    listOf("Unchoke", "Have(7)"),
                    messages.map {
                        when (it) {
                            is Message.Have -> "Have(${it.piece.value})"
                            else -> it::class.simpleName
                        }
                    },
                    "a message was lost between the handshake reader and the wire reader",
                )
                connection.close()
            }
        }

    /**
     * B-96 on the accepting side, which is the side the deadline matters most on.
     *
     * A dial that hangs costs one address this client chose. An accept that hangs costs a
     * coroutine opened by whoever connected to us — not a peer this client picked, and not a number
     * it controls.
     */
    @Test
    fun anIncomingConnectionThatSendsNoHandshakeIsGivenUpOnToo(): Unit =
        runBlocking {
            java.nio.channels.ServerSocketChannel
                .open()
                .bind(java.net.InetSocketAddress("127.0.0.1", 0), 4)
                .use { server ->
                    val client =
                        java.nio.channels.SocketChannel
                            .open(server.localAddress)
                    client.use {
                        val incoming = server.accept()
                        incoming.use {
                            val started = System.nanoTime()
                            assertFailsWith<java.net.SocketTimeoutException> {
                                // The client above connected and will say nothing at all.
                                SocketPeerConnection.readHandshake(incoming, timeout = 700.milliseconds)
                            }
                            val elapsed = (System.nanoTime() - started) / 1_000_000
                            assertTrue(
                                elapsed in 500..4_000,
                                "the accept ended after ${elapsed}ms, not at the deadline",
                            )
                        }
                    }
                }
        }

    /**
     * A listener that accepts and then says nothing, or says only part of a handshake.
     *
     * Not [FakePeer], which completes the handshake by design; this is the peer that does not.
     */
    private class SilentListener(
        private val dribbleEvery: kotlin.time.Duration? = null,
    ) : AutoCloseable {
        private val server =
            java.nio.channels.ServerSocketChannel
                .open()
                .bind(java.net.InetSocketAddress("127.0.0.1", 0), 16)

        val address =
            io.github.youndie.kachok.engine.peer
                .PeerAddress(
                    "127.0.0.1",
                    (server.localAddress as java.net.InetSocketAddress).port,
                )

        private val accepted = java.util.concurrent.ConcurrentLinkedQueue<java.nio.channels.SocketChannel>()

        private val acceptor =
            Thread.ofVirtual().start {
                while (server.isOpen) {
                    val socket =
                        try {
                            server.accept()
                        } catch (closed: java.io.IOException) {
                            break
                        }
                    accepted += socket
                    if (dribbleEvery != null) {
                        Thread.ofVirtual().start {
                            try {
                                val bytes = Handshake(infoHashOf(), peerIdOf(), Handshake.reservedBits()).encode()
                                for (byte in bytes) {
                                    val one = ByteBuffer.wrap(byteArrayOf(byte))
                                    while (one.hasRemaining()) socket.write(one)
                                    Thread.sleep(dribbleEvery.inWholeMilliseconds)
                                }
                            } catch (closed: java.io.IOException) {
                                // The client gave up first, which is what this test is about.
                            } catch (interrupted: InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                        }
                    }
                }
            }

        private fun infoHashOf() = InfoHash(ByteArray(20) { it.toByte() })

        private fun peerIdOf() = PeerId("-FAKE01-000000000000".encodeToByteArray())

        override fun close() {
            try {
                server.close()
            } catch (ignored: java.io.IOException) {
                // Tearing down.
            }
            accepted.forEach {
                try {
                    it.close()
                } catch (ignored: java.io.IOException) {
                    // Tearing down.
                }
            }
            acceptor.interrupt()
        }
    }
}
