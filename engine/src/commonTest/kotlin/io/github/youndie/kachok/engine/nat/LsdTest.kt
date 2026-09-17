package io.github.youndie.kachok.engine.nat

import io.github.youndie.kachok.engine.InfoHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** B-102: what goes on the segment, and what comes back off it. */
class LsdTest {
    private val infoHash = InfoHash(ByteArray(20) { (it * 11).toByte() })
    private val ours = "kachok-0123456789ab"

    @Test
    fun theAnnounceIsBep14sRequestWithTheBoundPort() {
        val text = Lsd.announce(infoHash, port = 6882, cookie = ours).decodeToString()

        assertTrue(text.startsWith("BT-SEARCH * HTTP/1.1\r\n"), text)
        assertTrue(text.contains("Host: ${Lsd.GROUP_V4}:${Lsd.PORT}\r\n"), text)
        assertTrue(text.contains("Port: 6882\r\n"), "the port announced is the one that was bound")
        assertTrue(text.contains("cookie: $ours\r\n"), text)
        assertTrue(text.endsWith("\r\n\r\n"), "BEP 14's request ends with a blank line")
    }

    @Test
    fun theInfoHashTravelsAsFortyHexCharacters() {
        val text = Lsd.announce(infoHash, port = 6881, cookie = ours).decodeToString()
        val header =
            text
                .lineSequence()
                .first { it.startsWith("Infohash:") }
                .substringAfter(':')
                .trim()
        assertEquals(40, header.length, header)
        assertTrue(header.all { it in "0123456789abcdef" }, "lower-case hex, and nothing else: $header")
    }

    /** What one client says is what another reads, which is the only round trip that matters. */
    @Test
    fun whatIsAnnouncedIsWhatIsParsed() {
        val heard = assertNotNull(Lsd.parse(Lsd.announce(infoHash, 6881, "theirs").decodeToString(), ownCookie = ours))
        assertEquals(infoHash.bytes.toList(), heard.infoHash.bytes.toList())
        assertEquals(6881, heard.port)
    }

    /**
     * **This is the one that matters and the one an implementation forgets.**
     *
     * A multicast announce arrives back on the socket that sent it. A client with no cookie to
     * recognise reads its own packet, dials its own listening port, and connects to itself — which
     * succeeds, appears in the peer list, and transfers nothing for as long as anybody is watching.
     */
    @Test
    fun ourOwnAnnounceComingBackIsNotAPeer() {
        val mine = Lsd.announce(infoHash, 6881, cookie = ours).decodeToString()
        assertNull(Lsd.parse(mine, ownCookie = ours), "this client found itself")
        assertNotNull(Lsd.parse(mine, ownCookie = "somebody-else"), "and it must still hear everybody else")
    }

    /** A multicast group carries whatever anybody puts on it; none of this is a failure. */
    @Test
    fun aDatagramThatIsNotAnAnnouncementIsANonEvent() {
        assertNull(Lsd.parse("M-SEARCH * HTTP/1.1\r\nHost: x\r\n\r\n", ours), "somebody else's protocol")
        assertNull(Lsd.parse("BT-SEARCH * HTTP/1.1\r\nPort: 6881\r\n\r\n", ours), "no info hash")
        assertNull(Lsd.parse("BT-SEARCH * HTTP/1.1\r\nInfohash: ${"a".repeat(40)}\r\n\r\n", ours), "no port")
        assertNull(Lsd.parse("", ours), "nothing at all")
    }

    /** A malformed info hash is not a torrent, and neither is a port nobody can dial. */
    @Test
    fun aHashOrPortThatCannotBeReadIsRefusedRatherThanGuessedAt() {
        fun announcement(
            hash: String,
            port: String,
        ) = "BT-SEARCH * HTTP/1.1\r\nPort: $port\r\nInfohash: $hash\r\ncookie: theirs\r\n\r\n"

        assertNull(Lsd.parse(announcement("a".repeat(39), "6881"), ours), "thirty-nine characters is not a hash")
        assertNull(Lsd.parse(announcement("z".repeat(40), "6881"), ours), "not hexadecimal")
        assertNull(Lsd.parse(announcement("a".repeat(40), "0"), ours), "port zero is not a port")
        assertNull(Lsd.parse(announcement("a".repeat(40), "70000"), ours), "no such port")
        assertNull(Lsd.parse(announcement("a".repeat(40), "not-a-number"), ours))
        assertNotNull(Lsd.parse(announcement("a".repeat(40), "65535"), ours), "the last real port is a real port")
    }

    /** Headers are case-insensitive, and clients disagree about which case they send. */
    @Test
    fun headersAreReadWhateverCaseTheyArriveIn() {
        val shouted = "BT-SEARCH * HTTP/1.1\r\nPORT: 6881\r\nINFOHASH: ${"b".repeat(40)}\r\nCOOKIE: theirs\r\n\r\n"
        val heard = assertNotNull(Lsd.parse(shouted, ours))
        assertEquals(6881, heard.port)

        // And the cookie check has to be case-insensitive in the *header name* while staying exact
        // on the value: two clients may pick cookies differing only in case.
        assertNotNull(Lsd.parse(shouted, ownCookie = "THEIRS"), "the cookie value is compared exactly")
    }
}
