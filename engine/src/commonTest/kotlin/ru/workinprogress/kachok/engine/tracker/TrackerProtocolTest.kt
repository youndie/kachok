package ru.workinprogress.kachok.engine.tracker

import ru.workinprogress.kachok.engine.InfoHash
import ru.workinprogress.kachok.engine.PeerId
import ru.workinprogress.kachok.engine.peer.PeerAddress
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The acceptance criteria of B-15's protocol half, and the tracker scenarios of feature-download. */
class TrackerProtocolTest {
    private val infoHash = InfoHash(ByteArray(20) { (it * 11).toByte() })
    private val peerId = PeerId("-KA0001-0123456789AB".encodeToByteArray())

    private fun request(event: AnnounceEvent? = null) =
        AnnounceRequest(
            infoHash = infoHash,
            peerId = peerId,
            port = 6882,
            uploaded = 0,
            downloaded = 1024,
            left = 2048,
            event = event,
        )

    @Test
    fun theInfoHashIsPercentEncodedOneRawByteAtATime() {
        val url = TrackerProtocol.announceUrl("http://tracker.example/annc", request(AnnounceEvent.STARTED))
        // Byte 0 is 0x00, byte 1 is 0x0B, byte 5 is 0x37 which is the digit '7' and stays literal.
        assertContains(url, "info_hash=%00%0B%16%21%2C7BMX")
        assertContains(url, "&port=6882")
        assertContains(url, "&left=2048")
        assertContains(url, "&compact=1")
        assertContains(url, "&event=started")
        assertTrue(url.startsWith("http://tracker.example/annc?"))
    }

    @Test
    fun aTrackerUrlThatAlreadyHasAQueryGetsAnAmpersand() {
        val url = TrackerProtocol.announceUrl("http://tracker.example/annc?pass=abc", request())
        assertContains(url, "annc?pass=abc&info_hash=")
    }

    @Test
    fun theEventIsOmittedForARegularAnnounce() {
        assertTrue(!TrackerProtocol.announceUrl("http://x/a", request()).contains("event="))
        assertContains(TrackerProtocol.announceUrl("http://x/a", request(AnnounceEvent.STOPPED)), "event=stopped")
        assertContains(TrackerProtocol.announceUrl("http://x/a", request(AnnounceEvent.COMPLETED)), "event=completed")
    }

    @Test
    fun aCompactPeerListOfTwelveBytesIsTwoPeers() {
        // 127.0.0.1:6881 and 10.0.0.5:51413, as BEP 23 packs them.
        val packed = byteArrayOf(127, 0, 0, 1, 0x1A, 0xE1.toByte(), 10, 0, 0, 5, 0xC8.toByte(), 0xD5.toByte())
        val body = "d8:intervali1800e5:peers12:".encodeToByteArray() + packed + "e".encodeToByteArray()
        val response = TrackerProtocol.parseResponse(body)
        assertEquals(1800, response.interval)
        assertEquals(
            listOf(PeerAddress("127.0.0.1", 6881), PeerAddress("10.0.0.5", 51413)),
            response.peers,
        )
    }

    @Test
    fun aCompactListOfTheWrongLengthIsRefused() {
        val body = "d8:intervali1800e5:peers7:1234567e".encodeToByteArray()
        val thrown = assertFailsWith<TrackerException> { TrackerProtocol.parseResponse(body) }
        assertContains(thrown.message ?: "", "multiple of 6")
    }

    @Test
    fun theOriginalListOfDictionariesIsReadToo() {
        // `compact=1` asks; it does not compel. A client that reads only the packed form finds no
        // peers at all on a tracker that answers the other way.
        val body =
            "d8:intervali900e5:peersld2:ip9:127.0.0.14:porti6881eed2:ip8:10.0.0.54:porti51413eeee"
                .encodeToByteArray()
        val response = TrackerProtocol.parseResponse(body)
        assertEquals(
            listOf(PeerAddress("127.0.0.1", 6881), PeerAddress("10.0.0.5", 51413)),
            response.peers,
        )
    }

    @Test
    fun aFailureReasonIsTheTrackersOwnWords() {
        val body = "d14:failure reason9:forbiddene".encodeToByteArray()
        val thrown = assertFailsWith<TrackerException> { TrackerProtocol.parseResponse(body) }
        assertEquals("forbidden", thrown.message)
    }

    @Test
    fun anAnswerWithoutAnIntervalIsRefused() {
        val thrown =
            assertFailsWith<TrackerException> {
                TrackerProtocol.parseResponse("d5:peers0:e".encodeToByteArray())
            }
        assertContains(thrown.message ?: "", "interval")
    }

    @Test
    fun somethingThatIsNotBencodeIsATrackerErrorNotACrash() {
        val thrown =
            assertFailsWith<TrackerException> {
                TrackerProtocol.parseResponse("<html>rate limited</html>".encodeToByteArray())
            }
        assertContains(thrown.message ?: "", "not bencode")
    }

    @Test
    fun theOptionalCountsAreReadWhenPresent() {
        val body =
            "d8:intervali1800e12:min intervali900e8:completei5e10:incompletei3e5:peers0:e"
                .encodeToByteArray()
        val response = TrackerProtocol.parseResponse(body)
        assertEquals(900, response.minInterval)
        assertEquals(5, response.seeders)
        assertEquals(3, response.leechers)
        assertTrue(response.peers.isEmpty())
    }

    @Test
    fun thePortRangeIsTheOneBep3Names() {
        assertEquals(6881, TrackerProtocol.PORT_RANGE.first)
        assertEquals(6889, TrackerProtocol.PORT_RANGE.last)
    }
}
