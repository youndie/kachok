package ru.workinprogress.kachok.engine.peer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** BEP 7's eighteen bytes, and the text they turn into. */
class CompactPeersV6Test {
    private fun address(
        vararg groups: Int,
        port: Int,
    ): ByteArray {
        val bytes = ByteArray(CompactPeers.SIZE_V6)
        groups.forEachIndexed { index, group ->
            bytes[index * 2] = (group ushr 8).toByte()
            bytes[index * 2 + 1] = group.toByte()
        }
        bytes[16] = (port ushr 8).toByte()
        bytes[17] = port.toByte()
        return bytes
    }

    @Test
    fun eighteenBytesAreOnePeerWithAnIpv6Address() {
        // The acceptance criterion of B-38's first half.
        val packed = address(0x2001, 0x0db8, 0, 0, 0, 0, 0, 1, port = 6881)

        val peers = CompactPeers.decode6(packed)

        assertEquals(1, peers.size)
        assertEquals("2001:db8::1", peers.single().host)
        assertEquals(6881, peers.single().port)
    }

    @Test
    fun theLongestRunOfZeroGroupsIsTheOneCollapsed() {
        assertEquals(
            "2001:0:0:1::1",
            CompactPeers.decode6(address(0x2001, 0, 0, 1, 0, 0, 0, 1, port = 1)).single().host,
            "the later run of three is longer than the earlier run of two",
        )
        assertEquals("::1", CompactPeers.decode6(address(0, 0, 0, 0, 0, 0, 0, 1, port = 1)).single().host)
        assertEquals("::", CompactPeers.decode6(address(port = 1)).single().host)
    }

    @Test
    fun aSingleZeroGroupIsWrittenOutRatherThanCollapsed() {
        // RFC 5952: `::` must not stand for one group.
        assertEquals(
            "2001:db8:0:1:1:1:1:1",
            CompactPeers.decode6(address(0x2001, 0xdb8, 0, 1, 1, 1, 1, 1, port = 1)).single().host,
        )
    }

    @Test
    fun aTrailingFragmentIsDroppedAndTheTwoFormsAreNotInterchangeable() {
        val packed = address(0x2001, 0xdb8, 0, 0, 0, 0, 0, 1, port = 1)

        assertTrue(CompactPeers.decode6(packed.copyOfRange(0, packed.size - 1)).isEmpty())
        // Eighteen bytes read as the IPv4 form are three peers made of one peer's pieces, which is
        // why the two are separate functions and separate fields on the wire.
        assertEquals(3, CompactPeers.decode(packed).size)
    }

    @Test
    fun anIpv6PeerPrintsInBracketsSoItCanBeReadBack() {
        val peer = CompactPeers.decode6(address(0x2001, 0xdb8, 0, 0, 0, 0, 0, 1, port = 6881)).single()

        assertEquals("[2001:db8::1]:6881", peer.toString())
        assertEquals("10.0.0.1:6881", PeerAddress("10.0.0.1", 6881).toString())
    }

    @Test
    fun anIpv6HostIsNotPackedIntoTheIpv4Form() {
        assertTrue(!CompactPeers.isPackable("2001:db8::1"))
        assertEquals(0, CompactPeers.encode(listOf(PeerAddress("2001:db8::1", 6881))).size)
    }
}
