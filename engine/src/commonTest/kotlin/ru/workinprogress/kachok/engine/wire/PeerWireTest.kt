package ru.workinprogress.kachok.engine.wire

import ru.workinprogress.kachok.engine.InfoHash
import ru.workinprogress.kachok.engine.PeerId
import ru.workinprogress.kachok.engine.PieceIndex
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The acceptance criteria of B-06 and the wire scenarios of feature-download.
 *
 * Every constant here is quoted from BEP 3's *peer protocol* and *peer messages* sections: the
 * 68-byte handshake, the identifiers `0`–`8`, the zero-length keep-alive, `2^14` blocks.
 */
class PeerWireTest {
    private val infoHash = InfoHash(ByteArray(20) { it.toByte() })
    private val peerId = PeerId("-KA0001-0123456789AB".encodeToByteArray())

    @Test
    fun theHandshakeIsSixtyEightBytesAndRoundTrips() {
        val handshake = Handshake(infoHash, peerId)
        val bytes = handshake.encode()

        assertEquals(68, bytes.size)
        assertEquals(19, bytes[0].toInt())
        assertEquals("BitTorrent protocol", bytes.decodeToString(1, 20))

        val decoded = Handshake.decode(bytes)
        assertTrue(decoded.infoHash.bytes.contentEquals(infoHash.bytes))
        assertTrue(decoded.peerId.bytes.contentEquals(peerId.bytes))
        assertFalse(decoded.supportsExtensionProtocol)
        assertFalse(decoded.supportsFastExtension)
    }

    @Test
    fun theReservedBitsSayWhichExtensionsAPeerSpeaks() {
        val both =
            Handshake(
                infoHash,
                peerId,
                Handshake.reservedBits(extensionProtocol = true, fastExtension = true),
            )
        val decoded = Handshake.decode(both.encode())
        assertTrue(decoded.supportsExtensionProtocol, "BEP 10: reserved[5] & 0x10")
        assertTrue(decoded.supportsFastExtension, "BEP 6: reserved[7] |= 0x04")
        assertEquals(0x10, decoded.reserved[5].toInt())
        assertEquals(0x04, decoded.reserved[7].toInt())
    }

    @Test
    fun aHandshakeWithAnotherProtocolNameIsRefused() {
        val bytes = Handshake(infoHash, peerId).encode()
        bytes[1] = 'X'.code.toByte()
        val thrown = assertFailsWith<WireException> { Handshake.decode(bytes) }
        assertContains(thrown.message ?: "", "protocol name")
    }

    @Test
    fun aTruncatedHandshakeIsAnErrorNotAPartialValue() {
        val bytes = Handshake(infoHash, peerId).encode().copyOfRange(0, 67)
        val thrown = assertFailsWith<WireException> { Handshake.decode(bytes) }
        assertContains(thrown.message ?: "", "68")
    }

    @Test
    fun aZeroLengthFrameIsAKeepAlive() {
        assertEquals(Message.KeepAlive, PeerWire.decode(ByteArray(0)))
        // On the wire that is four zero bytes and nothing else.
        assertTrue(PeerWire.encode(Message.KeepAlive).contentEquals(ByteArray(4)))
    }

    @Test
    fun everyIdentifierRoundTrips() {
        val cases =
            listOf(
                Message.Choke to PeerWire.CHOKE,
                Message.Unchoke to PeerWire.UNCHOKE,
                Message.Interested to PeerWire.INTERESTED,
                Message.NotInterested to PeerWire.NOT_INTERESTED,
            )
        cases.forEach { (message, id) ->
            val frame = PeerWire.encode(message)
            assertEquals(5, frame.size, "$message is a one-byte payload")
            assertEquals(1, PeerWire.readInt(frame, 0))
            assertEquals(id, frame[4].toInt())
            assertEquals(message, PeerWire.decode(frame, 4, frame.size))
        }
    }

    @Test
    fun haveCarriesOnePieceIndex() {
        val frame = PeerWire.encode(Message.Have(PieceIndex(258)))
        assertEquals(5, PeerWire.readInt(frame, 0))
        assertEquals(PeerWire.HAVE, frame[4].toInt())
        val decoded = PeerWire.decode(frame, 4, frame.size) as Message.Have
        assertEquals(258, decoded.piece.value)
    }

    @Test
    fun bitfieldCarriesItsBytesUnchanged() {
        val bits = byteArrayOf(0b1010_0000.toByte(), 0x00, 0xFF.toByte())
        val frame = PeerWire.encode(Message.Bitfield(bits))
        assertEquals(4, PeerWire.readInt(frame, 0))
        val decoded = PeerWire.decode(frame, 4, frame.size) as Message.Bitfield
        assertTrue(decoded.bits.contentEquals(bits))
    }

    @Test
    fun requestAndCancelCarryIndexBeginAndLength() {
        val request = PeerWire.encode(Message.Request(PieceIndex(7), 16384, 16384))
        assertEquals(13, PeerWire.readInt(request, 0))
        val decodedRequest = PeerWire.decode(request, 4, request.size) as Message.Request
        assertEquals(7, decodedRequest.piece.value)
        assertEquals(16384, decodedRequest.begin)
        assertEquals(16384, decodedRequest.length)

        val cancel = PeerWire.encode(Message.Cancel(PieceIndex(7), 16384, 16384))
        assertEquals(PeerWire.CANCEL, cancel[4].toInt())
        val decodedCancel = PeerWire.decode(cancel, 4, cancel.size) as Message.Cancel
        assertEquals(decodedRequest.begin, decodedCancel.begin)
    }

    @Test
    fun aRequestLargerThanSixteenKibibytesCannotBeBuilt() {
        // BEP 3: peers "close connections which request an amount greater than that", so the
        // refusal is at the point of writing — a request that cannot be built cannot be sent.
        val thrown =
            assertFailsWith<WireException> {
                PeerWire.encode(Message.Request(PieceIndex(0), 0, PeerWire.BLOCK_SIZE + 1))
            }
        assertContains(thrown.message ?: "", "16384")
        assertFailsWith<WireException> { PeerWire.encode(Message.Request(PieceIndex(0), 0, 0)) }
    }

    @Test
    fun aPieceIsDecodedWithoutCopyingItsBlock() {
        val block = ByteArray(PeerWire.BLOCK_SIZE) { (it and 0xFF).toByte() }
        val header = PeerWire.encodePieceHeader(PieceIndex(3), 32768, block.size)
        val frame = header + block

        assertEquals(13, header.size)
        assertEquals(9 + block.size, PeerWire.readInt(frame, 0))

        val decoded = PeerWire.decode(frame, 4, frame.size) as Message.Piece
        assertEquals(3, decoded.piece.value)
        assertEquals(32768, decoded.begin)
        assertEquals(block.size, decoded.blockLength)
        // The offsets point into the frame that was handed in; nothing was copied out of it.
        assertEquals(13, decoded.blockFrom)
        assertTrue(
            frame
                .copyOfRange(decoded.blockFrom, decoded.blockFrom + decoded.blockLength)
                .contentEquals(block),
        )
    }

    @Test
    fun theLastBlockOfAPieceIsShorterAndStillValid() {
        val block = ByteArray(7232) { 1 }
        val frame = PeerWire.encodePieceHeader(PieceIndex(2), 32768, block.size) + block
        val decoded = PeerWire.decode(frame, 4, frame.size) as Message.Piece
        assertEquals(7232, decoded.blockLength)
    }

    @Test
    fun aPieceIsNotEncodedThroughTheMessagePath() {
        // The block is a file, not a byte array; the upload path writes the header and transfers
        // the block. An encode() that took the block would have invited a copy of every byte
        // this client uploads.
        val thrown =
            assertFailsWith<WireException> {
                PeerWire.encode(Message.Piece(PieceIndex(0), 0, 0, 16))
            }
        assertContains(thrown.message ?: "", "encodePieceHeader")
    }

    @Test
    fun aPayloadOfTheWrongSizeIsRefused() {
        val cases =
            mapOf(
                "choke with a payload" to byteArrayOf(PeerWire.CHOKE.toByte(), 0),
                "have with three bytes" to byteArrayOf(PeerWire.HAVE.toByte(), 0, 0, 1),
                "request with eight bytes" to ByteArray(9).also { it[0] = PeerWire.REQUEST.toByte() },
                "piece with a truncated header" to ByteArray(5).also { it[0] = PeerWire.PIECE.toByte() },
            )
        cases.forEach { (label, frame) ->
            assertFailsWith<WireException>(label) { PeerWire.decode(frame) }
        }
    }

    @Test
    fun anUnknownIdentifierIsAnError() {
        val thrown = assertFailsWith<WireException> { PeerWire.decode(byteArrayOf(99)) }
        assertContains(thrown.message ?: "", "99")
    }

    @Test
    fun extendedMessagesCarryTheirExtensionId() {
        val payload = "d1:ai1ee".encodeToByteArray()
        val frame = PeerWire.encode(Message.Extended(3, payload))
        assertEquals(PeerWire.EXTENDED, frame[4].toInt())
        val decoded = PeerWire.decode(frame, 4, frame.size) as Message.Extended
        assertEquals(3, decoded.extensionId)
        assertTrue(decoded.payload.contentEquals(payload))
    }

    @Test
    fun integersOnTheWireAreBigEndian() {
        val bytes = ByteArray(4)
        PeerWire.writeInt(bytes, 0, 0x01020304)
        assertTrue(bytes.contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertEquals(0x01020304, PeerWire.readInt(bytes, 0))
        // And an index near the top of the range survives the round trip unsigned-shifted.
        PeerWire.writeInt(bytes, 0, Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, PeerWire.readInt(bytes, 0))
    }
}
