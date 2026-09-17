package io.github.youndie.kachok.engine.nat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** B-103: the twelve bytes out and the sixteen back, before a socket is involved. */
class NatPmpTest {
    @Test
    fun aMapRequestIsTwelveBytesInRfc6886sOrder() {
        val packet = NatPmp.mapRequest(internalPort = 6881, suggestedExternalPort = 6881)

        assertEquals(NatPmp.REQUEST_SIZE, packet.size)
        assertEquals(0, packet[0].toInt(), "version 0 is NAT-PMP; 2 would be PCP")
        assertEquals(NatPmp.OPCODE_MAP_TCP, packet[1].toInt(), "TCP by default: peers dial a stream")
        assertEquals(0, packet[2].toInt(), "reserved")
        assertEquals(0, packet[3].toInt(), "reserved")
        assertEquals(6881, short(packet, 4))
        assertEquals(6881, short(packet, 6))
        assertEquals(NatPmp.LIFETIME_SECONDS, int(packet, 8))
    }

    /**
     * **Releasing sends a zero external port, and that is not tidiness.**
     *
     * RFC 6886 says the external port is ignored when the lifetime is zero — but a router that does
     * *not* ignore it reads the packet as a request for that port with a zero lifetime, which is
     * how an implementation asks for a mapping while meaning to drop one. The zero is what makes
     * the two packets unambiguous.
     */
    @Test
    fun releasingAsksForNothingAndForNoTime() {
        val packet =
            NatPmp.mapRequest(
                internalPort = 6881,
                suggestedExternalPort = 6881,
                lifetimeSeconds = NatPmp.RELEASE_LIFETIME,
            )
        assertEquals(6881, short(packet, 4), "the internal port still names what is being dropped")
        assertEquals(0, short(packet, 6), "a release must not name an external port")
        assertEquals(0, int(packet, 8))
    }

    @Test
    fun udpAndTcpAreDifferentOpcodes() {
        assertEquals(NatPmp.OPCODE_MAP_UDP, NatPmp.mapRequest(6881, tcp = false)[1].toInt())
        assertEquals(NatPmp.OPCODE_MAP_TCP, NatPmp.mapRequest(6881, tcp = true)[1].toInt())
    }

    @Test
    fun aSuccessfulResponseIsReadBackWhole() {
        val mapping = assertNotNull(NatPmp.parseResponse(response(result = 0, external = 49_152, lifetime = 7200)))

        assertTrue(mapping.succeeded)
        assertNull(mapping.refusal)
        assertEquals(6881, mapping.internalPort)
        assertEquals(49_152, mapping.externalPort, "the router may map a port other than the one asked for")
        assertEquals(7200, mapping.lifetimeSeconds)
        assertTrue(mapping.tcp)
    }

    /**
     * A refusal comes back as a mapping carrying its reason, not as a null.
     *
     * "The router refused" is not something a person can act on; "the router has port mapping
     * switched off" is, and it is a different action from "the router does not speak NAT-PMP".
     */
    @Test
    fun everyRefusalCodeSaysSomethingAPersonCanAct0n() {
        val reasons =
            (1..5).map { code ->
                val mapping = assertNotNull(NatPmp.parseResponse(response(result = code, external = 0, lifetime = 0)))
                assertFalse(mapping.succeeded, "code $code was read as success")
                assertNotNull(mapping.refusal, "code $code has no words")
            }
        assertEquals(reasons.size, reasons.toSet().size, "two codes give the same sentence: $reasons")

        val unknown = assertNotNull(NatPmp.parseResponse(response(result = 99, external = 0, lifetime = 0)))
        assertEquals("the router refused with code 99", unknown.refusal, "an unknown code must still say something")
    }

    /**
     * A UDP socket hands over whatever reaches the port, so three things are non-events.
     *
     * Each of them would be an error if this threw: a datagram too short to be a response, one
     * from a protocol this is not speaking, and one whose opcode is a *request* rather than a
     * reply — which is what arrives when something else on the segment is also mapping ports.
     */
    @Test
    fun aDatagramThatIsNotAResponseIsANonEventRatherThanAFailure() {
        assertNull(NatPmp.parseResponse(ByteArray(8)), "too short")
        assertNull(NatPmp.parseResponse(response(result = 0, external = 1, lifetime = 1).also { it[0] = 2 }), "PCP")
        assertNull(
            NatPmp.parseResponse(response(result = 0, external = 1, lifetime = 1).also { it[1] = 2 }),
            "somebody else's request, not a response to ours",
        )
    }

    /** A response longer than sixteen bytes is still a response; routers pad. */
    @Test
    fun trailingBytesDoNotMakeAResponseUnreadable() {
        val padded = response(result = 0, external = 49_152, lifetime = 7200) + ByteArray(8)
        val mapping = assertNotNull(NatPmp.parseResponse(padded, length = padded.size))
        assertEquals(49_152, mapping.externalPort)
    }

    private fun response(
        result: Int,
        external: Int,
        lifetime: Int,
        opcode: Int = NatPmp.OPCODE_MAP_TCP + NatPmp.RESPONSE_FLAG,
    ): ByteArray {
        val packet = ByteArray(NatPmp.RESPONSE_SIZE)
        packet[0] = NatPmp.VERSION.toByte()
        packet[1] = opcode.toByte()
        packet[2] = (result ushr 8).toByte()
        packet[3] = result.toByte()
        // Seconds since the router's epoch; a jump backwards means it rebooted and forgot.
        writeInt(packet, 4, 1_234_567)
        packet[8] = (6881 ushr 8).toByte()
        packet[9] = 6881.toByte()
        packet[10] = (external ushr 8).toByte()
        packet[11] = external.toByte()
        writeInt(packet, 12, lifetime)
        return packet
    }

    private fun writeInt(
        into: ByteArray,
        at: Int,
        value: Int,
    ) {
        into[at] = (value ushr 24).toByte()
        into[at + 1] = (value ushr 16).toByte()
        into[at + 2] = (value ushr 8).toByte()
        into[at + 3] = value.toByte()
    }

    private fun short(
        bytes: ByteArray,
        at: Int,
    ): Int = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    private fun int(
        bytes: ByteArray,
        at: Int,
    ): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
}
