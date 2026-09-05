package ru.workinprogress.kachok.engine.tracker

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * An announce over UDP (BEP 15), on a blocking [DatagramSocket] and a virtual thread.
 *
 * **A socket, not a channel.** BEP 15 is a protocol made of timeouts — every request is answered
 * within 15 · 2ⁿ seconds or resent — and a `DatagramChannel` in blocking mode has no receive
 * timeout at all, which would leave `withTimeout` cancelling a coroutine while the read underneath
 * it stayed blocked forever. `DatagramSocket.setSoTimeout` is the timeout the protocol asks for.
 * Measured on JDK 25 before choosing it: 200 virtual threads parked in `receive` with a 20 s
 * timeout cost **12 platform threads** on an 8-core machine, so it parks the virtual thread the way
 * a socket read does and does not hold a carrier for the length of the timeout.
 *
 * **The socket is connected.** UDP has no handshake, so anything that reaches the port arrives; a
 * connected socket makes the kernel drop everything not from the tracker. That is the cheap half of
 * ignoring what is not ours, and [UdpTrackerProtocol.parseReply]'s transaction id is the half that
 * survives an attacker who knows the tracker's address.
 *
 * **One handshake per announce.** BEP 15 allows a connection id to be reused for a minute. This
 * client does not keep one: the saving is a single round trip per announce interval, and the price
 * is a cache with an expiry rule and a path for the tracker that refuses a stale id — two states
 * that would be exercised once every half hour and tested never. When phase 1 grows to several
 * torrents on one tracker the arithmetic changes.
 */
public class UdpTrackerClient(
    private val dispatcher: CoroutineDispatcher,
    /** Overridden by tests, which cannot wait fifteen seconds to watch one retransmission. */
    private val firstTimeoutMillis: Long = UdpTrackerProtocol.FIRST_TIMEOUT_MILLIS,
    private val maxAttempts: Int = UdpTrackerProtocol.MAX_ATTEMPTS,
    private val random: Random = Random.Default,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : TrackerClient {
    /** Datagrams sent, retransmissions included. Read by the tests that check the schedule. */
    public var datagramsSent: Int = 0
        private set

    override suspend fun announce(
        tracker: String,
        request: AnnounceRequest,
    ): AnnounceResponse =
        withContext(dispatcher) {
            val address = UdpTrackerProtocol.parseAddress(tracker)
            val endpoint = InetSocketAddress(address.host, address.port)
            if (endpoint.isUnresolved) throw TrackerException("udp tracker `$tracker` does not resolve")

            DatagramSocket().use { socket ->
                try {
                    socket.connect(endpoint)
                } catch (unreachable: IOException) {
                    throw TrackerException("udp tracker $tracker is unreachable: ${unreachable.message}")
                }

                val connectionId = connect(socket, tracker)
                val transactionId = random.nextInt()
                val packet =
                    UdpTrackerProtocol.announceRequest(
                        connectionId = connectionId,
                        transactionId = transactionId,
                        // BEP 15's `key`: how a tracker recognises this client again when its
                        // address changes. One per announce is allowed and tells the tracker
                        // nothing it could correlate across announces.
                        key = random.nextInt(),
                        request = request,
                    )
                when (
                    val reply =
                        exchange(socket, packet, transactionId, UdpTrackerProtocol.ACTION_ANNOUNCE, tracker)
                ) {
                    is UdpTrackerReply.Announced -> {
                        reply.response
                    }

                    is UdpTrackerReply.Refused -> {
                        throw TrackerException(reply.message)
                    }

                    is UdpTrackerReply.Connected -> {
                        throw TrackerException("udp tracker $tracker answered an announce with a connect")
                    }
                }
            }
        }

    private fun connect(
        socket: DatagramSocket,
        tracker: String,
    ): Long {
        val transactionId = random.nextInt()
        val packet = UdpTrackerProtocol.connectRequest(transactionId)
        return when (
            val reply = exchange(socket, packet, transactionId, UdpTrackerProtocol.ACTION_CONNECT, tracker)
        ) {
            is UdpTrackerReply.Connected -> {
                reply.connectionId
            }

            is UdpTrackerReply.Refused -> {
                throw TrackerException(reply.message)
            }

            is UdpTrackerReply.Announced -> {
                throw TrackerException("udp tracker $tracker answered a connect with an announce")
            }
        }
    }

    /**
     * Send, wait, resend: BEP 15's retransmit schedule.
     *
     * The inner loop is what makes this more than a request-response. A datagram that arrives
     * inside the window and is not this exchange's — a duplicate, a straggler from a previous
     * attempt, somebody guessing — does not end the wait and does not fail the announce; the
     * remaining time is what is left of it.
     */
    private fun exchange(
        socket: DatagramSocket,
        request: ByteArray,
        transactionId: Int,
        expectedAction: Int,
        tracker: String,
    ): UdpTrackerReply {
        val buffer = ByteArray(MAX_DATAGRAM)
        repeat(maxAttempts) { attempt ->
            send(socket, request, tracker)
            val budget = UdpTrackerProtocol.timeoutMillis(attempt, firstTimeoutMillis).milliseconds
            val started = timeSource.markNow()
            while (true) {
                val remaining = budget - started.elapsedNow()
                if (!remaining.isPositive()) break
                val datagram = DatagramPacket(buffer, buffer.size)
                socket.soTimeout = remaining.inWholeMilliseconds.coerceAtLeast(1).toInt()
                try {
                    socket.receive(datagram)
                } catch (expired: SocketTimeoutException) {
                    break
                } catch (failed: IOException) {
                    throw TrackerException("udp tracker $tracker: ${failed.message}")
                }
                UdpTrackerProtocol
                    .parseReply(datagram.data, datagram.length, transactionId, expectedAction)
                    ?.let { return it }
            }
        }
        throw TrackerException("udp tracker $tracker did not answer in $maxAttempts attempts")
    }

    private fun send(
        socket: DatagramSocket,
        request: ByteArray,
        tracker: String,
    ) {
        try {
            socket.send(DatagramPacket(request, request.size))
        } catch (failed: IOException) {
            throw TrackerException("udp tracker $tracker refused a datagram: ${failed.message}")
        }
        datagramsSent++
    }

    private companion object {
        /**
         * Larger than any announce reply a tracker sends: `numwant` is capped well below the 1 000
         * peers this would hold, and a datagram longer than this would be truncated by the socket
         * rather than by us.
         */
        const val MAX_DATAGRAM = 8192
    }
}
