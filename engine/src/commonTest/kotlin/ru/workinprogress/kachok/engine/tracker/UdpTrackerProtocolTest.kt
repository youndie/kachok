package ru.workinprogress.kachok.engine.tracker

import ru.workinprogress.kachok.engine.InfoHash
import ru.workinprogress.kachok.engine.PeerId
import ru.workinprogress.kachok.engine.wire.PeerWire
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BEP 15's packets, byte by byte: the acceptance criteria of B-32 that need no socket. */
class UdpTrackerProtocolTest {
    private val infoHash = InfoHash(ByteArray(20) { (it + 1).toByte() })
    private val peerId = PeerId("-KA0001-abcdefghijkl".encodeToByteArray())

    private fun request(
        event: AnnounceEvent? = AnnounceEvent.STARTED,
        numWant: Int? = null,
    ) = AnnounceRequest(
        infoHash = infoHash,
        peerId = peerId,
        port = 6881,
        uploaded = 1_000,
        downloaded = 2_000,
        left = 3_000,
        event = event,
        numWant = numWant,
    )

    @Test
    fun aConnectRequestIsTheMagicTheActionAndTheTransaction() {
        val packet = UdpTrackerProtocol.connectRequest(0x0BADF00D)

        assertEquals(UdpTrackerProtocol.CONNECT_REQUEST_SIZE, packet.size)
        assertEquals(0x417, PeerWire.readInt(packet, 0), "the high half of BEP 15's protocol id")
        assertEquals(0x27101980, PeerWire.readInt(packet, 4), "the low half of BEP 15's protocol id")
        assertEquals(UdpTrackerProtocol.ACTION_CONNECT, PeerWire.readInt(packet, 8))
        assertEquals(0x0BADF00D, PeerWire.readInt(packet, 12))
    }

    @Test
    fun anAnnounceRequestIsNinetyEightBytesInBep15sOrder() {
        val packet = UdpTrackerProtocol.announceRequest(0x1122334455667788L, 7, key = 99, request = request())

        assertEquals(UdpTrackerProtocol.ANNOUNCE_REQUEST_SIZE, packet.size)
        assertEquals(0x11223344, PeerWire.readInt(packet, 0), "connection id, high half")
        assertEquals(0x55667788, PeerWire.readInt(packet, 4), "connection id, low half")
        assertEquals(UdpTrackerProtocol.ACTION_ANNOUNCE, PeerWire.readInt(packet, 8))
        assertEquals(7, PeerWire.readInt(packet, 12))
        assertTrue(infoHash.bytes.contentEquals(packet.copyOfRange(16, 36)), "info hash")
        assertTrue(peerId.bytes.contentEquals(packet.copyOfRange(36, 56)), "peer id")
        assertEquals(2_000, PeerWire.readInt(packet, 60), "downloaded, low half")
        assertEquals(3_000, PeerWire.readInt(packet, 68), "left, low half")
        assertEquals(1_000, PeerWire.readInt(packet, 76), "uploaded, low half")
        assertEquals(2, PeerWire.readInt(packet, 80), "BEP 15 numbers `started` 2, not 1")
        assertEquals(0, PeerWire.readInt(packet, 84), "IP 0: the tracker uses the source address")
        assertEquals(99, PeerWire.readInt(packet, 88), "key")
        assertEquals(-1, PeerWire.readInt(packet, 92), "numwant defaults to -1, not to 0")
        assertEquals(6881, ((packet[96].toInt() and 0xFF) shl 8) or (packet[97].toInt() and 0xFF))
    }

    @Test
    fun theEventCodesAreBep15sAndNotBep3sOrder() {
        // BEP 3 sends words and BEP 15 sends numbers, and the numbers are not in the order the
        // words are usually listed in: none 0, completed 1, started 2, stopped 3.
        val codes =
            listOf(null, AnnounceEvent.COMPLETED, AnnounceEvent.STARTED, AnnounceEvent.STOPPED)
                .map { event ->
                    PeerWire.readInt(UdpTrackerProtocol.announceRequest(1, 1, 1, request(event = event)), 80)
                }
        assertEquals(listOf(0, 1, 2, 3), codes)
    }

    @Test
    fun numWantIsSentWhenTheCallerAsksForOne() {
        val packet = UdpTrackerProtocol.announceRequest(1, 1, 1, request(numWant = 30))
        assertEquals(30, PeerWire.readInt(packet, 92))
    }

    @Test
    fun aConnectReplyCarriesAConnectionIdWithItsTopBitIntact() {
        // A connection id is random and half of them have the sign bit set; reading one through a
        // sign-extending int is the classic way to send an announce no tracker recognises.
        val reply = ByteArray(16)
        PeerWire.writeInt(reply, 0, UdpTrackerProtocol.ACTION_CONNECT)
        PeerWire.writeInt(reply, 4, 42)
        PeerWire.writeInt(reply, 8, 0x7FFFFFFF)
        PeerWire.writeInt(reply, 12, -1)

        val parsed =
            UdpTrackerProtocol.parseReply(reply, reply.size, 42, UdpTrackerProtocol.ACTION_CONNECT)

        assertEquals(0x7FFFFFFFFFFFFFFFL, (parsed as UdpTrackerReply.Connected).connectionId)
    }

    @Test
    fun anAnnounceReplyYieldsTheIntervalTheCountsAndThePeers() {
        val reply = announceReply(interval = 1800, leechers = 5, seeders = 9, peers = 2)

        val parsed =
            UdpTrackerProtocol.parseReply(reply, reply.size, 42, UdpTrackerProtocol.ACTION_ANNOUNCE)
        val response = (parsed as UdpTrackerReply.Announced).response

        assertEquals(1800, response.interval)
        assertEquals(5, response.leechers)
        assertEquals(9, response.seeders)
        assertEquals(2, response.peers.size)
        assertEquals("10.0.0.1", response.peers[0].host)
        assertEquals(6881, response.peers[0].port)
        assertEquals("10.0.0.2", response.peers[1].host)
        assertEquals(6882, response.peers[1].port)
    }

    @Test
    fun aTrailingFragmentOfAPeerIsDroppedRatherThanReadPast() {
        val full = announceReply(interval = 60, leechers = 0, seeders = 0, peers = 2)
        // Four bytes of the second peer arrived: an address without a port is not an address.
        val truncated = full.copyOfRange(0, full.size - 2)

        val parsed =
            UdpTrackerProtocol.parseReply(truncated, truncated.size, 42, UdpTrackerProtocol.ACTION_ANNOUNCE)

        assertEquals(1, (parsed as UdpTrackerReply.Announced).response.peers.size)
    }

    @Test
    fun anErrorReplyIsTheTrackersOwnWords() {
        val message = "torrent not registered"
        val reply = ByteArray(8 + message.length)
        PeerWire.writeInt(reply, 0, UdpTrackerProtocol.ACTION_ERROR)
        PeerWire.writeInt(reply, 4, 42)
        message.encodeToByteArray().copyInto(reply, 8)

        val parsed =
            UdpTrackerProtocol.parseReply(reply, reply.size, 42, UdpTrackerProtocol.ACTION_ANNOUNCE)

        assertEquals(message, (parsed as UdpTrackerReply.Refused).message)
    }

    @Test
    fun aDatagramThatIsNotThisExchangesIsANonEventRatherThanAFailure() {
        val reply = announceReply(interval = 60, leechers = 0, seeders = 0, peers = 1)
        val short = ByteArray(4)

        // Every one of these must be `null` and not an exception: the caller keeps waiting out its
        // window, and anything that turned one of them into a failure would let a stranger with the
        // tracker's address end an announce by sending four bytes.
        assertNull(
            UdpTrackerProtocol.parseReply(reply, reply.size, 43, UdpTrackerProtocol.ACTION_ANNOUNCE),
            "another transaction's reply",
        )
        assertNull(
            UdpTrackerProtocol.parseReply(short, short.size, 42, UdpTrackerProtocol.ACTION_ANNOUNCE),
            "too short to carry a header",
        )
        assertNull(
            UdpTrackerProtocol.parseReply(reply, reply.size, 42, UdpTrackerProtocol.ACTION_CONNECT),
            "an announce reply while waiting for a connect",
        )
        assertNull(
            UdpTrackerProtocol.parseReply(
                reply,
                UdpTrackerProtocol.CONNECT_REQUEST_SIZE,
                42,
                UdpTrackerProtocol.ACTION_ANNOUNCE,
            ),
            "an announce reply too short for its own header",
        )
    }

    @Test
    fun theRetransmitScheduleIsBep15sFifteenSecondsDoubling() {
        val schedule = (0 until UdpTrackerProtocol.MAX_ATTEMPTS).map { UdpTrackerProtocol.timeoutMillis(it) }

        assertEquals(
            listOf(15_000L, 30_000, 60_000, 120_000, 240_000, 480_000, 960_000, 1_920_000, 3_840_000),
            schedule,
            "15 · 2^n seconds for n in 0..8, which BEP 15 caps at 64 minutes",
        )
        assertFailsWith<IllegalArgumentException> { UdpTrackerProtocol.timeoutMillis(UdpTrackerProtocol.MAX_ATTEMPTS) }
    }

    @Test
    fun aTrackerUrlIsTakenApartAndOneWithoutAPortIsRefused() {
        val address = UdpTrackerProtocol.parseAddress("udp://tracker.example.org:1337/announce")
        assertEquals("tracker.example.org", address.host)
        assertEquals(1337, address.port)

        listOf(
            "udp://tracker.example.org",
            "udp://tracker.example.org/announce",
            "udp://tracker.example.org:nope/announce",
            "udp://tracker.example.org:0",
            "udp://",
        ).forEach { url ->
            val thrown =
                assertFailsWith<TrackerException>("`$url` should be refused") {
                    UdpTrackerProtocol.parseAddress(url)
                }
            assertContains(thrown.message ?: "", url.ifEmpty { "udp" })
        }
    }

    private fun announceReply(
        interval: Int,
        leechers: Int,
        seeders: Int,
        peers: Int,
    ): ByteArray {
        val reply = ByteArray(20 + peers * 6)
        PeerWire.writeInt(reply, 0, UdpTrackerProtocol.ACTION_ANNOUNCE)
        PeerWire.writeInt(reply, 4, 42)
        PeerWire.writeInt(reply, 8, interval)
        PeerWire.writeInt(reply, 12, leechers)
        PeerWire.writeInt(reply, 16, seeders)
        (0 until peers).forEach { index ->
            val at = 20 + index * 6
            reply[at] = 10
            reply[at + 1] = 0
            reply[at + 2] = 0
            reply[at + 3] = (index + 1).toByte()
            reply[at + 4] = ((6881 + index) ushr 8).toByte()
            reply[at + 5] = (6881 + index).toByte()
        }
        return reply
    }
}
