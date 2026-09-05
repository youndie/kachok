package ru.workinprogress.kachok.engine.tracker

import ru.workinprogress.kachok.engine.peer.PeerAddress
import ru.workinprogress.kachok.engine.wire.PeerWire

/** The tracker's address, taken apart. BEP 15 speaks to a host and a port, not to a path. */
public class UdpTrackerAddress(
    public val host: String,
    public val port: Int,
)

/** What a datagram from a tracker turned out to be. */
public sealed interface UdpTrackerReply {
    /** The connection id to put in the next announce. Valid for one minute (BEP 15). */
    public class Connected(
        public val connectionId: Long,
    ) : UdpTrackerReply

    public class Announced(
        public val response: AnnounceResponse,
    ) : UdpTrackerReply

    /** `action = 3`: the tracker's own words for why it refused. */
    public class Refused(
        public val message: String,
    ) : UdpTrackerReply
}

/**
 * BEP 15 without the sockets: the two requests, the three replies and the retransmit schedule.
 *
 * The reason for the split is the same as [TrackerProtocol]'s and rather stronger here — a UDP
 * exchange is a state machine with a timer, and a state machine that can only be exercised through
 * a socket gets tested for the happy path and nothing else. Every rule below that says "ignore this
 * datagram" is a rule about hostile or stale input, which is exactly what a socket test cannot
 * arrange reliably.
 */
public object UdpTrackerProtocol {
    /** The magic BEP 15 opens with. Anything else on the wire is not this protocol. */
    public const val PROTOCOL_ID: Long = 0x41727101980L

    public const val ACTION_CONNECT: Int = 0
    public const val ACTION_ANNOUNCE: Int = 1
    public const val ACTION_ERROR: Int = 3

    public const val CONNECT_REQUEST_SIZE: Int = 16
    public const val ANNOUNCE_REQUEST_SIZE: Int = 98
    private const val CONNECT_REPLY_SIZE = 16
    private const val ANNOUNCE_REPLY_HEADER = 20
    private const val COMPACT_PEER_SIZE = 6
    private const val HEADER_SIZE = 8

    /**
     * BEP 15: a connection id is good for one minute. Kept a little under, because the minute is
     * the tracker's and the clock is ours.
     */
    public const val CONNECTION_ID_LIFETIME_MILLIS: Long = 50_000

    /** BEP 15: wait 15 · 2ⁿ seconds for attempt n, and give up after n = 8. */
    public const val FIRST_TIMEOUT_MILLIS: Long = 15_000
    public const val MAX_ATTEMPTS: Int = 9

    /**
     * The timeout for attempt [attempt], counting from zero.
     *
     * The doubling is the whole of BEP 15's congestion control: a tracker that is down must not be
     * hammered, and a datagram that was merely dropped must be resent soon enough to matter.
     */
    public fun timeoutMillis(
        attempt: Int,
        first: Long = FIRST_TIMEOUT_MILLIS,
    ): Long {
        require(attempt in 0 until MAX_ATTEMPTS) { "attempt $attempt is outside 0..${MAX_ATTEMPTS - 1}" }
        return first shl attempt
    }

    /** `udp://host:port/announce` → host and port. The path is not part of the protocol. */
    public fun parseAddress(tracker: String): UdpTrackerAddress {
        val withoutScheme =
            tracker.removePrefix("udp://").ifEmpty {
                throw TrackerException("`$tracker` is not a udp:// tracker")
            }
        val authority = withoutScheme.substringBefore('/').substringBefore('?')
        val separator = authority.lastIndexOf(':')
        if (separator <= 0 || separator == authority.length - 1) {
            throw TrackerException("udp tracker `$tracker` has no port, and BEP 15 defines no default")
        }
        val host = authority.substring(0, separator)
        val port =
            authority.substring(separator + 1).toIntOrNull()
                ?: throw TrackerException("udp tracker `$tracker` has a port that is not a number")
        if (port !in 1..MAX_PORT) throw TrackerException("udp tracker `$tracker` has port $port")
        return UdpTrackerAddress(host, port)
    }

    public fun connectRequest(transactionId: Int): ByteArray {
        val packet = ByteArray(CONNECT_REQUEST_SIZE)
        writeLong(packet, 0, PROTOCOL_ID)
        PeerWire.writeInt(packet, Long.SIZE_BYTES, ACTION_CONNECT)
        PeerWire.writeInt(packet, Long.SIZE_BYTES + Int.SIZE_BYTES, transactionId)
        return packet
    }

    /**
     * The 98-byte announce.
     *
     * `key` is BEP 15's answer to a client whose IP changes mid-session: the tracker uses it, and
     * not the source address, to recognise the same client again.
     */
    public fun announceRequest(
        connectionId: Long,
        transactionId: Int,
        key: Int,
        request: AnnounceRequest,
    ): ByteArray {
        val packet = ByteArray(ANNOUNCE_REQUEST_SIZE)
        writeLong(packet, 0, connectionId)
        PeerWire.writeInt(packet, 8, ACTION_ANNOUNCE)
        PeerWire.writeInt(packet, 12, transactionId)
        request.infoHash.bytes.copyInto(packet, 16)
        request.peerId.bytes.copyInto(packet, 36)
        writeLong(packet, 56, request.downloaded)
        writeLong(packet, 64, request.left)
        writeLong(packet, 72, request.uploaded)
        PeerWire.writeInt(packet, 80, eventCode(request.event))
        // IP address 0: "the tracker should use the source address of this datagram", which is the
        // only answer a client behind NAT can give truthfully.
        PeerWire.writeInt(packet, 84, 0)
        PeerWire.writeInt(packet, 88, key)
        PeerWire.writeInt(packet, 92, request.numWant ?: DEFAULT_NUM_WANT)
        packet[96] = (request.port ushr 8).toByte()
        packet[97] = request.port.toByte()
        return packet
    }

    /**
     * What a datagram means, or `null` if it means nothing to this exchange.
     *
     * A UDP socket will hand over anything that reaches the port. Three things are therefore not
     * errors but non-events, and each of them would be an error if this returned a failure: a
     * datagram too short to carry a header, a datagram belonging to a transaction this client is
     * not waiting for — a duplicate of an earlier reply, or a guess by somebody else — and a reply
     * to the other kind of request. The caller keeps waiting; that is the point.
     */
    public fun parseReply(
        packet: ByteArray,
        length: Int,
        transactionId: Int,
        expectedAction: Int,
    ): UdpTrackerReply? {
        if (length < HEADER_SIZE) return null
        if (PeerWire.readInt(packet, Int.SIZE_BYTES) != transactionId) return null
        return when (PeerWire.readInt(packet, 0)) {
            ACTION_ERROR -> {
                UdpTrackerReply.Refused(
                    packet
                        .decodeToString(
                            HEADER_SIZE,
                            length,
                        ).trim()
                        .ifEmpty { "the tracker refused without saying why" },
                )
            }

            ACTION_CONNECT -> {
                if (expectedAction != ACTION_CONNECT || length < CONNECT_REPLY_SIZE) {
                    null
                } else {
                    UdpTrackerReply.Connected(readLong(packet, HEADER_SIZE))
                }
            }

            ACTION_ANNOUNCE -> {
                if (expectedAction != ACTION_ANNOUNCE || length < ANNOUNCE_REPLY_HEADER) {
                    null
                } else {
                    UdpTrackerReply.Announced(announceResponse(packet, length))
                }
            }

            else -> {
                null
            }
        }
    }

    private fun announceResponse(
        packet: ByteArray,
        length: Int,
    ): AnnounceResponse {
        val peers = mutableListOf<PeerAddress>()
        var at = ANNOUNCE_REPLY_HEADER
        // A trailing fragment of a peer is dropped rather than read past: the six bytes that are
        // there are an address without a port, which is not an address.
        while (at + COMPACT_PEER_SIZE <= length) {
            val host = (0 until 4).joinToString(".") { (packet[at + it].toInt() and 0xFF).toString() }
            val port = ((packet[at + 4].toInt() and 0xFF) shl 8) or (packet[at + 5].toInt() and 0xFF)
            peers += PeerAddress(host, port)
            at += COMPACT_PEER_SIZE
        }
        return AnnounceResponse(
            interval = PeerWire.readInt(packet, 8),
            peers = peers,
            leechers = PeerWire.readInt(packet, 12),
            seeders = PeerWire.readInt(packet, 16),
        )
    }

    private fun eventCode(event: AnnounceEvent?): Int =
        when (event) {
            null -> 0
            AnnounceEvent.COMPLETED -> 1
            AnnounceEvent.STARTED -> 2
            AnnounceEvent.STOPPED -> 3
        }

    private fun writeLong(
        bytes: ByteArray,
        at: Int,
        value: Long,
    ) {
        PeerWire.writeInt(bytes, at, (value ushr Int.SIZE_BITS).toInt())
        PeerWire.writeInt(bytes, at + Int.SIZE_BYTES, value.toInt())
    }

    private fun readLong(
        bytes: ByteArray,
        at: Int,
    ): Long =
        (PeerWire.readInt(bytes, at).toLong() shl Int.SIZE_BITS) or
            (PeerWire.readInt(bytes, at + Int.SIZE_BYTES).toLong() and 0xFFFFFFFFL)

    /** BEP 15's "default", which trackers read as "as many as you would normally give". */
    private const val DEFAULT_NUM_WANT = -1
    private const val MAX_PORT = 65_535
}
