package io.github.youndie.kachok.engine.wire

import io.github.youndie.kachok.engine.PieceIndex
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** BEP 6's five messages, byte by byte: the codec half of B-33. */
class FastExtensionTest {
    private fun roundTrip(message: Message): Message {
        val frame = PeerWire.encode(message)
        assertEquals(frame.size - PeerWire.LENGTH_PREFIX_SIZE, PeerWire.readInt(frame, 0), "length prefix")
        return PeerWire.decode(frame, from = PeerWire.LENGTH_PREFIX_SIZE)
    }

    @Test
    fun theIdsAreTheOnesBep6Assigns() {
        // 0x0D..0x11, and getting one wrong means talking past every deployed client.
        assertEquals(0x0D, PeerWire.SUGGEST)
        assertEquals(0x0E, PeerWire.HAVE_ALL)
        assertEquals(0x0F, PeerWire.HAVE_NONE)
        assertEquals(0x10, PeerWire.REJECT)
        assertEquals(0x11, PeerWire.ALLOWED_FAST)

        listOf(
            Message.Suggest(PieceIndex(1)) to PeerWire.SUGGEST,
            Message.HaveAll to PeerWire.HAVE_ALL,
            Message.HaveNone to PeerWire.HAVE_NONE,
            Message.Reject(PieceIndex(1), 0, 16) to PeerWire.REJECT,
            Message.AllowedFast(PieceIndex(1)) to PeerWire.ALLOWED_FAST,
        ).forEach { (message, id) ->
            val frame = PeerWire.encode(message)
            assertEquals(id, frame[PeerWire.LENGTH_PREFIX_SIZE].toInt(), "$message")
        }
    }

    @Test
    fun haveAllAndHaveNoneAreOneByteEach() {
        // The reason they exist: a seed's bitfield for two million pieces is 250 KiB, and a peer
        // with nothing sends the same 250 KiB of zeros.
        assertEquals(PeerWire.LENGTH_PREFIX_SIZE + 1, PeerWire.encode(Message.HaveAll).size)
        assertEquals(PeerWire.LENGTH_PREFIX_SIZE + 1, PeerWire.encode(Message.HaveNone).size)
        assertTrue(roundTrip(Message.HaveAll) === Message.HaveAll)
        assertTrue(roundTrip(Message.HaveNone) === Message.HaveNone)
    }

    @Test
    fun aRejectCarriesTheWholeRequestItRefuses() {
        // Index, begin *and* length: a peer may have several requests outstanding within one piece,
        // and a reject naming only the piece would free the wrong block.
        val rejected = roundTrip(Message.Reject(PieceIndex(7), 32_768, 16_384)) as Message.Reject

        assertEquals(7, rejected.piece.value)
        assertEquals(32_768, rejected.begin)
        assertEquals(16_384, rejected.length)
    }

    @Test
    fun suggestAndAllowedFastCarryAPieceIndex() {
        assertEquals(9, (roundTrip(Message.Suggest(PieceIndex(9))) as Message.Suggest).piece.value)
        assertEquals(9, (roundTrip(Message.AllowedFast(PieceIndex(9))) as Message.AllowedFast).piece.value)
    }

    @Test
    fun aFastMessageOfTheWrongLengthIsRefused() {
        listOf(
            byteArrayOf(PeerWire.HAVE_ALL.toByte(), 0) to "have all",
            byteArrayOf(PeerWire.HAVE_NONE.toByte(), 0) to "have none",
            byteArrayOf(PeerWire.SUGGEST.toByte(), 0, 0) to "suggest",
            byteArrayOf(PeerWire.REJECT.toByte(), 0, 0, 0, 1) to "reject",
            byteArrayOf(PeerWire.ALLOWED_FAST.toByte()) to "allowed fast",
        ).forEach { (frame, name) ->
            val thrown = assertFailsWith<WireException>("$name should be refused") { PeerWire.decode(frame) }
            assertContains(thrown.message ?: "", name)
        }
    }

    @Test
    fun theReservedBitsAnswerTheSameQuestionOfBytesAndOfAHandshake() {
        val bits = Handshake.reservedBits(extensionProtocol = true, fastExtension = true)

        assertTrue(Handshake.hasFastExtension(bits))
        assertTrue(Handshake.hasExtensionProtocol(bits))
        assertTrue(!Handshake.hasFastExtension(Handshake.reservedBits(extensionProtocol = true)))
        assertEquals(0x04, bits[7].toInt() and 0xFF, "BEP 6 is reserved[7] |= 0x04")
        assertEquals(0x10, bits[5].toInt() and 0xFF, "BEP 10 is reserved[5] & 0x10")
    }
}
