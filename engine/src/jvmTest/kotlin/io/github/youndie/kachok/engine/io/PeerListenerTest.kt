package io.github.youndie.kachok.engine.io

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.PeerId
import io.github.youndie.kachok.engine.peer.PeerConnection
import io.github.youndie.kachok.engine.tracker.TrackerProtocol
import io.github.youndie.kachok.engine.wire.Handshake
import io.github.youndie.kachok.engine.wire.WireException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.BindException
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The acceptance criteria of B-09. */
class PeerListenerTest {
    private val infoHash = InfoHash(ByteArray(20) { it.toByte() })
    private val otherHash = InfoHash(ByteArray(20) { (it + 1).toByte() })
    private val peerId = PeerId("-KA0001-0123456789AB".encodeToByteArray())

    private val dispatchers = EngineDispatchers()
    private val scope = CoroutineScope(dispatchers.io + SupervisorJob())
    private val occupied = mutableListOf<ServerSocketChannel>()
    private var listener: PeerListener? = null

    @AfterTest
    fun shutDown() {
        listener?.close()
        occupied.forEach { it.close() }
        scope.cancel()
        dispatchers.close()
    }

    /**
     * `SO_REUSEADDR`, the same option [PeerListener] binds with.
     *
     * Without it this helper is stricter than the code it is standing in for, and the class defeats
     * itself: `aPortLeftInTimeWaitIsStillOurs` deliberately leaves a connection to the bottom of
     * the range in TIME_WAIT, and the next test's `occupy` of that port then fails to bind at all —
     * a `BindException` from the *fixture*, reported as the listener misbehaving.
     */
    private fun occupy(port: Int) {
        occupied +=
            ServerSocketChannel.open().apply {
                setOption(StandardSocketOptions.SO_REUSEADDR, true)
                bind(InetSocketAddress("127.0.0.1", port), 1)
            }
    }

    /**
     * Nine consecutive ports this machine will actually give us, found rather than hoped for.
     *
     * The fixed range this used to be was wrong on the build machine: its `ip_local_port_range` is
     * 40525-44620 and contained it, so the kernel could hand one of those out as an ephemeral
     * client port to anything on the box — and `aFullyOccupiedRangeIsAnErrorNamingIt`, whose whole
     * job is to occupy all nine, then failed inside its own fixture with a `BindException` that
     * read like the listener misbehaving.
     *
     * There is no range that is safe everywhere; the reserved ones need root. So this claims one,
     * from candidates well above the usual ephemeral window, and keeps it bound for the life of the
     * test — which is also what makes it a range nobody else can take mid-test.
     */
    private val range: IntRange by lazy {
        CANDIDATES.firstNotNullOfOrNull { first ->
            val claimed = mutableListOf<ServerSocketChannel>()
            try {
                (first..first + RANGE_SIZE - 1).forEach { port ->
                    claimed +=
                        ServerSocketChannel.open().apply {
                            setOption(StandardSocketOptions.SO_REUSEADDR, true)
                            bind(InetSocketAddress("127.0.0.1", port), 1)
                        }
                }
                claimed.forEach { it.close() }
                first..first + RANGE_SIZE - 1
            } catch (taken: BindException) {
                claimed.forEach { it.close() }
                null
            }
        } ?: throw AssertionError("no free range of $RANGE_SIZE ports among $CANDIDATES")
    }

    @Test
    fun aPortLeftInTimeWaitIsStillOurs() {
        // Not a test-only concern: a client restarted inside the two-minute TIME_WAIT window would
        // otherwise skip its own port and announce a different one, and peers holding the old
        // address would find nobody there. This test was written because the failure happened.
        val first = PeerListener.bind(range, host = "127.0.0.1")
        val port = first.port
        val client = SocketChannel.open(InetSocketAddress("127.0.0.1", port))
        client.close()
        first.close()

        val again = PeerListener.bind(port..port, host = "127.0.0.1").also { listener = it }
        assertEquals(port, again.port, "a port this client just used must still be bindable")
    }

    @Test
    fun theListenerTakesTheFirstFreePortOfTheRange() {
        occupy(range.first)
        val bound = PeerListener.bind(range, host = "127.0.0.1").also { listener = it }
        assertEquals(range.first + 1, bound.port, "6881 taken means 6882, as BEP 3 describes")
    }

    @Test
    fun theDefaultBindAcceptsOverIpv6AndOverIpv4(): Unit =
        runBlocking {
            // The acceptance criterion of B-38's second half. Binding `0.0.0.0`, which this used
            // to, is a client that announces a port no IPv6 peer can reach — and the failure is
            // invisible from an IPv4 test.
            val bound = PeerListener.bind(range).also { listener = it }

            listOf("::1", "127.0.0.1").forEach { host ->
                val client =
                    try {
                        SocketChannel.open(InetSocketAddress(host, bound.port))
                    } catch (unsupported: java.io.IOException) {
                        // A machine with no loopback for that family says so here rather than by
                        // failing an assertion about the listener.
                        throw AssertionError("this machine cannot connect to $host: ${unsupported.message}")
                    }
                client.use {
                    assertTrue(it.isConnected, "the listener refused a connection over $host")
                }
            }
        }

    @Test
    fun aFullyOccupiedRangeIsAnErrorNamingIt() {
        range.forEach { occupy(it) }
        val thrown = assertFailsWith<BindException> { PeerListener.bind(range, host = "127.0.0.1") }
        assertContains(thrown.message ?: "", "$range")
    }

    @Test
    fun theRangeIsTheOneBep3Names() {
        assertEquals(6881..6889, TrackerProtocol.PORT_RANGE)
    }

    @Test
    fun anIncomingPeerIsAdoptedAfterItsHandshake(): Unit =
        runBlocking {
            val accepted = Channel<PeerConnection>(Channel.UNLIMITED)
            val bound = PeerListener.bind(range, host = "127.0.0.1").also { listener = it }
            bound.start(scope) { socket ->
                accepted.send(
                    SocketPeerConnection.accept(scope, socket, infoHash, peerId, BufferPool(4)),
                )
            }

            // A peer dials us and speaks first, which is the difference from the dialling path.
            val client = SocketChannel.open(InetSocketAddress("127.0.0.1", bound.port))
            val theirId = PeerId("-OT0001-000000000000".encodeToByteArray())
            client.write(ByteBuffer.wrap(Handshake(infoHash, theirId).encode()))

            val connection = withTimeout(TIMEOUT) { accepted.receive() }
            assertTrue(
                connection.handshake.peerId.bytes
                    .contentEquals(theirId.bytes),
            )
            assertEquals("127.0.0.1", connection.address.host)

            val ours = ByteBuffer.allocate(Handshake.SIZE)
            while (ours.hasRemaining()) client.read(ours)
            assertTrue(
                Handshake
                    .decode(ours.array())
                    .peerId.bytes
                    .contentEquals(peerId.bytes),
            )

            connection.close()
            client.close()
        }

    @Test
    fun anIncomingPeerAskingForAnotherTorrentIsClosed(): Unit =
        runBlocking {
            val refusals = Channel<Throwable>(Channel.UNLIMITED)
            val bound = PeerListener.bind(range, host = "127.0.0.1").also { listener = it }
            bound.start(scope) { socket ->
                try {
                    SocketPeerConnection.accept(scope, socket, infoHash, peerId, BufferPool(4))
                } catch (refused: WireException) {
                    refusals.send(refused)
                }
            }

            val client = SocketChannel.open(InetSocketAddress("127.0.0.1", bound.port))
            client.write(ByteBuffer.wrap(Handshake(otherHash, peerId).encode()))

            val refused = withTimeout(TIMEOUT) { refusals.receive() }
            assertContains(refused.message ?: "", "another torrent")
            // And we said nothing about the torrent we do have: the socket is closed, not answered.
            val answer = ByteBuffer.allocate(1)
            assertEquals(-1, client.read(answer), "the connection should be closed, not answered")
            client.close()
        }

    private companion object {
        const val RANGE_SIZE = 9

        /** Tried in order. Nine consecutive ports is not much to ask of any of them. */
        val CANDIDATES = listOf(47_881, 49_881, 51_881, 53_881)

        const val TIMEOUT = 10_000L
    }
}
