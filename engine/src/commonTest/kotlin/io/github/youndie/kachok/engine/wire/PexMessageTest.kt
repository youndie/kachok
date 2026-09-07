package io.github.youndie.kachok.engine.wire

import io.github.youndie.kachok.engine.bencode.BDictionary
import io.github.youndie.kachok.engine.bencode.BInteger
import io.github.youndie.kachok.engine.bencode.BString
import io.github.youndie.kachok.engine.bencode.Bencode
import io.github.youndie.kachok.engine.peer.CompactPeers
import io.github.youndie.kachok.engine.peer.PeerAddress
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** BEP 11's dictionary, and BEP 23's six bytes underneath it. */
class PexMessageTest {
    private val alice = PeerAddress("10.0.0.1", 6881)
    private val bob = PeerAddress("192.168.1.20", 51413)

    @Test
    fun aMessageRoundTripsWithItsFlags() {
        val original =
            PexMessage(
                added = listOf(alice, bob),
                dropped = listOf(PeerAddress("172.16.0.9", 1)),
                addedFlags = listOf(0, PexMessage.FLAG_SEED),
            )

        val read = PexMessage.decode(original.encode())

        assertEquals(listOf("10.0.0.1" to 6881, "192.168.1.20" to 51413), read.added.map { it.host to it.port })
        assertEquals(listOf("172.16.0.9" to 1), read.dropped.map { it.host to it.port })
        assertEquals(listOf(0, PexMessage.FLAG_SEED), read.addedFlags)
    }

    @Test
    fun aPeerWithOnlyDroppedPeersSendsOnlyDropped() {
        // Every key is optional, and treating an absent one as malformed would close the
        // connection over the commonest message there is.
        val payload =
            Bencode.encode(BDictionary(mapOf(BString("dropped") to BString(CompactPeers.encode(listOf(alice))))))

        val read = PexMessage.decode(payload)

        assertTrue(read.added.isEmpty())
        assertEquals(1, read.dropped.size)
        assertTrue(!read.isEmpty, "a message that drops a peer is not an empty message")
    }

    @Test
    fun anEmptyDictionaryIsAnEmptyMessageRatherThanAnError() {
        val read = PexMessage.decode(Bencode.encode(BDictionary(emptyMap())))

        assertTrue(read.isEmpty)
        assertTrue(read.added.isEmpty() && read.dropped.isEmpty() && read.addedFlags.isEmpty())
    }

    @Test
    fun ipv6PeersAreReadBesideTheIpv4Ones() {
        // BEP 7 in PEX (B-38): `added6` is read and never written — this client advertises only
        // what it can pack into the IPv4 form, because sending `added6` would mean claiming
        // something about its own IPv6 reachability.
        val v6 = ByteArray(18)
        v6[0] = 0x20
        v6[1] = 0x01
        v6[15] = 1
        v6[17] = 1
        val payload =
            Bencode.encode(
                BDictionary(
                    mapOf(
                        BString("added") to BString(CompactPeers.encode(listOf(alice))),
                        BString("added6") to BString(v6),
                        BString("dropped6") to BString(v6),
                    ),
                ),
            )

        val read = PexMessage.decode(payload)

        assertEquals(listOf("10.0.0.1", "2001::1"), read.added.map { it.host })
        assertEquals(listOf("2001::1"), read.dropped.map { it.host })
        assertEquals(
            0,
            PexMessage(added = read.added)
                .encode()
                .let { PexMessage.decode(it) }
                .added.size - 1,
        )
    }

    @Test
    fun moreFlagsThanPeersDoNotShiftOntoTheWrongPeer() {
        val payload =
            Bencode.encode(
                BDictionary(
                    mapOf(
                        BString("added") to BString(CompactPeers.encode(listOf(alice))),
                        BString("added.f") to BString(byteArrayOf(2, 2, 2, 2)),
                    ),
                ),
            )

        val read = PexMessage.decode(payload)

        assertEquals(listOf(PexMessage.FLAG_SEED), read.addedFlags, "one peer, one flag")
    }

    @Test
    fun aHostThatIsNotAnIpv4LiteralIsLeftOutRatherThanPacked() {
        // A tracker may answer with names. Four bytes of something that was not an address would
        // point every recipient at a peer that does not exist.
        val packed = CompactPeers.encode(listOf(alice, PeerAddress("tracker.example.org", 6881), bob))

        assertEquals(2 * CompactPeers.SIZE, packed.size)
        assertEquals(listOf("10.0.0.1", "192.168.1.20"), CompactPeers.decode(packed).map { it.host })
        assertTrue(!CompactPeers.isPackable("tracker.example.org"))
        assertTrue(!CompactPeers.isPackable("10.0.0.256"), "an octet out of range is not an address")
        assertTrue(!CompactPeers.isPackable("::1"))
    }

    @Test
    fun aTrailingFragmentIsDroppedRatherThanReadPast() {
        val packed = CompactPeers.encode(listOf(alice, bob))

        assertEquals(1, CompactPeers.decode(packed.copyOfRange(0, packed.size - 2)).size)
    }

    @Test
    fun somethingThatIsNotAMessageIsRefused() {
        listOf(ByteArray(0), "nonsense".encodeToByteArray(), Bencode.encode(BInteger(4))).forEach { payload ->
            val thrown = assertFailsWith<WireException> { PexMessage.decode(payload) }
            assertContains(thrown.message ?: "", "ut_pex")
        }
    }
}
