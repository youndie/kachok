package ru.workinprogress.kachok.engine.tracker

import ru.workinprogress.kachok.engine.peer.PeerAddress
import ru.workinprogress.kachok.engine.wire.PeerWire
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * A UDP tracker that answers BEP 15, and misbehaves on request.
 *
 * The point of a fake here is not the happy path — which a single exchange would prove — but the
 * three things a real tracker does that no unit test can arrange: it drops datagrams, it answers
 * late, and it refuses. Each knob below exists because one of B-32's acceptance criteria is about
 * what the client does when the tracker does not simply reply.
 */
class FakeUdpTracker(
    private val peers: List<PeerAddress> = listOf(PeerAddress("10.1.2.3", 6881), PeerAddress("10.1.2.4", 51413)),
    private val interval: Int = 1800,
    /** Announce datagrams to swallow before answering one, so the client has to resend. */
    private val dropAnnounces: Int = 0,
    /** Reply to an announce with a well-formed packet carrying somebody else's transaction id. */
    private val junkBeforeReply: Boolean = false,
    private val refuseAnnounceWith: String? = null,
    /** Issue a new connection id after the connect, so the announce arrives carrying a stale one. */
    private val rotateConnectionId: Boolean = false,
    /** Answer nothing at all, to whatever arrives. */
    private val silent: Boolean = false,
) : AutoCloseable {
    /** BEP 15 puts the action at offset 8 and the transaction id at 12 in both requests. */
    private val socket = DatagramSocket(0, java.net.InetAddress.getLoopbackAddress())

    val port: Int get() = socket.localPort

    /** What the client actually sent, so a test can check the request and not only the answer. */
    val announces: ConcurrentLinkedQueue<SeenAnnounce> = ConcurrentLinkedQueue()
    val datagramsReceived: AtomicInteger = AtomicInteger()

    /** Deliberately one whose sign bit is set: half of all connection ids have it. */
    private var issued: Long = -0x778899AABBCCDDEFL
    private var announcesDropped = 0

    class SeenAnnounce(
        val connectionId: Long,
        val infoHash: ByteArray,
        val peerId: ByteArray,
        val downloaded: Long,
        val left: Long,
        val uploaded: Long,
        val event: Int,
        val key: Int,
        val numWant: Int,
        val port: Int,
    )

    init {
        Thread.ofVirtual().name("fake-udp-tracker").start {
            val buffer = ByteArray(2048)
            while (!socket.isClosed) {
                val datagram = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(datagram)
                } catch (closed: IOException) {
                    break
                }
                datagramsReceived.incrementAndGet()
                if (silent) continue
                try {
                    answer(datagram)
                } catch (gone: IOException) {
                    break
                }
            }
        }
    }

    private fun answer(datagram: DatagramPacket) {
        val request = datagram.data
        val transaction = PeerWire.readInt(request, 12)
        when (PeerWire.readInt(request, 8)) {
            UdpTrackerProtocol.ACTION_CONNECT -> {
                val reply = ByteArray(16)
                PeerWire.writeInt(reply, 0, UdpTrackerProtocol.ACTION_CONNECT)
                PeerWire.writeInt(reply, 4, transaction)
                PeerWire.writeInt(reply, 8, (issued ushr 32).toInt())
                PeerWire.writeInt(reply, 12, issued.toInt())
                send(reply, datagram)
                if (rotateConnectionId) issued += 1
            }

            UdpTrackerProtocol.ACTION_ANNOUNCE -> {
                val connectionId =
                    (PeerWire.readInt(request, 0).toLong() shl 32) or
                        (PeerWire.readInt(request, 4).toLong() and 0xFFFFFFFFL)
                if (connectionId != issued) {
                    send(error("connection id mismatch", transaction), datagram)
                    return
                }
                if (announcesDropped < dropAnnounces) {
                    announcesDropped++
                    return
                }
                announces += seen(request, connectionId)
                refuseAnnounceWith?.let {
                    send(error(it, transaction), datagram)
                    return
                }
                // A well-formed reply to a transaction that is not the client's: it must not be
                // mistaken for the answer, and it must not end the wait either.
                if (junkBeforeReply) send(announceReply(transaction + 1, listOf(PeerAddress("1.1.1.1", 1))), datagram)
                send(announceReply(transaction, peers), datagram)
            }
        }
    }

    private fun seen(
        request: ByteArray,
        connectionId: Long,
    ) = SeenAnnounce(
        connectionId = connectionId,
        infoHash = request.copyOfRange(16, 36),
        peerId = request.copyOfRange(36, 56),
        downloaded = readLong(request, 56),
        left = readLong(request, 64),
        uploaded = readLong(request, 72),
        event = PeerWire.readInt(request, 80),
        key = PeerWire.readInt(request, 88),
        numWant = PeerWire.readInt(request, 92),
        port = ((request[96].toInt() and 0xFF) shl 8) or (request[97].toInt() and 0xFF),
    )

    private fun readLong(
        bytes: ByteArray,
        at: Int,
    ): Long =
        (PeerWire.readInt(bytes, at).toLong() shl 32) or (PeerWire.readInt(bytes, at + 4).toLong() and 0xFFFFFFFFL)

    private fun error(
        message: String,
        transaction: Int,
    ): ByteArray {
        val words = message.encodeToByteArray()
        val reply = ByteArray(8 + words.size)
        PeerWire.writeInt(reply, 0, UdpTrackerProtocol.ACTION_ERROR)
        PeerWire.writeInt(reply, 4, transaction)
        words.copyInto(reply, 8)
        return reply
    }

    private fun announceReply(
        transaction: Int,
        answered: List<PeerAddress>,
    ): ByteArray {
        val reply = ByteArray(20 + answered.size * 6)
        PeerWire.writeInt(reply, 0, UdpTrackerProtocol.ACTION_ANNOUNCE)
        PeerWire.writeInt(reply, 4, transaction)
        PeerWire.writeInt(reply, 8, interval)
        PeerWire.writeInt(reply, 12, answered.size)
        PeerWire.writeInt(reply, 16, answered.size * 2)
        answered.forEachIndexed { index, peer ->
            val at = 20 + index * 6
            peer.host.split(".").forEachIndexed { part, text -> reply[at + part] = text.toInt().toByte() }
            reply[at + 4] = (peer.port ushr 8).toByte()
            reply[at + 5] = peer.port.toByte()
        }
        return reply
    }

    private fun send(
        reply: ByteArray,
        to: DatagramPacket,
    ) {
        socket.send(DatagramPacket(reply, reply.size, to.socketAddress))
    }

    override fun close() {
        socket.close()
    }
}
