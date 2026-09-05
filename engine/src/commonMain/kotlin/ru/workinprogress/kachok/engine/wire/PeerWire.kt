package ru.workinprogress.kachok.engine.wire

import ru.workinprogress.kachok.engine.PieceIndex

/**
 * The peer wire framing and message codec (BEP 3).
 *
 * A frame is a four-byte big-endian length prefix followed by that many bytes: zero of them is a
 * keep-alive, otherwise one identifier byte and a payload. This object owns both halves — reading
 * a frame's contents and writing one — and nothing else in the engine spells out an identifier.
 *
 * **Decoding is strict and an unknown identifier is an error.** A peer should not send a message
 * belonging to an extension the handshake did not advertise, and treating one as noise would turn
 * a framing bug of ours into "some messages are quietly ignored" — the failure that takes a week
 * to find. The caller closes the connection; [B-33] adds the fast extension's identifiers when
 * this client advertises them.
 *
 * **The block of a `piece` is never copied.** [decode] returns offsets into the caller's array.
 */
public object PeerWire {
    /**
     * BEP 3: "All current implementations use 2^14 (16 kiB), and close connections which request
     * an amount greater than that." Not a tunable — it is what the swarm accepts.
     */
    public const val BLOCK_SIZE: Int = 1 shl 14

    /** The length prefix itself. */
    public const val LENGTH_PREFIX_SIZE: Int = 4

    /**
     * A sanity cap on a length prefix, so that a hostile peer cannot ask for a gigabyte buffer.
     * Comfortably above both a full block frame (16 393 bytes) and the bitfield of a torrent with
     * two million pieces (250 KiB).
     */
    public const val MAX_FRAME_SIZE: Int = 1 shl 18

    public const val CHOKE: Int = 0
    public const val UNCHOKE: Int = 1
    public const val INTERESTED: Int = 2
    public const val NOT_INTERESTED: Int = 3
    public const val HAVE: Int = 4
    public const val BITFIELD: Int = 5
    public const val REQUEST: Int = 6
    public const val PIECE: Int = 7
    public const val CANCEL: Int = 8
    public const val EXTENDED: Int = 20

    private const val PIECE_HEADER_SIZE = 9
    private const val REQUEST_PAYLOAD_SIZE = 12

    /**
     * Decodes one frame's contents: `[from, to)` is the frame **without** its length prefix.
     *
     * The dispatch is a `when` over an integer constant, which is the shape that compiles to a
     * jump table; guard conditions read well elsewhere and are not what happens here.
     */
    public fun decode(
        frame: ByteArray,
        from: Int = 0,
        to: Int = frame.size,
    ): Message {
        val size = to - from
        if (size < 0) throw WireException("frame range $from..$to is inverted")
        if (size == 0) return Message.KeepAlive

        val payloadSize = size - 1
        return when (val id = frame[from].toInt() and 0xFF) {
            CHOKE -> {
                Message.Choke.also { expectEmpty(payloadSize, "choke") }
            }

            UNCHOKE -> {
                Message.Unchoke.also { expectEmpty(payloadSize, "unchoke") }
            }

            INTERESTED -> {
                Message.Interested.also { expectEmpty(payloadSize, "interested") }
            }

            NOT_INTERESTED -> {
                Message.NotInterested.also { expectEmpty(payloadSize, "not interested") }
            }

            HAVE -> {
                expectSize(payloadSize, Int.SIZE_BYTES, "have")
                Message.Have(PieceIndex(readInt(frame, from + 1)))
            }

            BITFIELD -> {
                Message.Bitfield(frame.copyOfRange(from + 1, to))
            }

            REQUEST -> {
                expectSize(payloadSize, REQUEST_PAYLOAD_SIZE, "request")
                Message.Request(
                    piece = PieceIndex(readInt(frame, from + 1)),
                    begin = readInt(frame, from + 1 + Int.SIZE_BYTES),
                    length = readInt(frame, from + 1 + Int.SIZE_BYTES * 2),
                )
            }

            CANCEL -> {
                expectSize(payloadSize, REQUEST_PAYLOAD_SIZE, "cancel")
                Message.Cancel(
                    piece = PieceIndex(readInt(frame, from + 1)),
                    begin = readInt(frame, from + 1 + Int.SIZE_BYTES),
                    length = readInt(frame, from + 1 + Int.SIZE_BYTES * 2),
                )
            }

            PIECE -> {
                if (size < PIECE_HEADER_SIZE) {
                    throw WireException("a piece message is at least $PIECE_HEADER_SIZE bytes, got $size")
                }
                Message.Piece(
                    piece = PieceIndex(readInt(frame, from + 1)),
                    begin = readInt(frame, from + 1 + Int.SIZE_BYTES),
                    blockFrom = from + PIECE_HEADER_SIZE,
                    blockLength = size - PIECE_HEADER_SIZE,
                )
            }

            EXTENDED -> {
                if (payloadSize < 1) throw WireException("an extended message carries no extension id")
                Message.Extended(
                    extensionId = frame[from + 1].toInt() and 0xFF,
                    payload = frame.copyOfRange(from + 2, to),
                )
            }

            else -> {
                throw WireException("unknown message id $id")
            }
        }
    }

    /**
     * Encodes a message **with** its length prefix, ready to be written.
     *
     * [Message.Piece] is deliberately absent: its payload is a file, not a byte array, and the
     * upload path writes [encodePieceHeader] followed by a zero-copy transfer of the block
     * (B-20). An overload taking the block would have invited a copy of every byte this client
     * uploads.
     */
    public fun encode(message: Message): ByteArray =
        when (message) {
            Message.KeepAlive -> {
                frame(0)
            }

            Message.Choke -> {
                frame(1) { it[LENGTH_PREFIX_SIZE] = CHOKE.toByte() }
            }

            Message.Unchoke -> {
                frame(1) { it[LENGTH_PREFIX_SIZE] = UNCHOKE.toByte() }
            }

            Message.Interested -> {
                frame(1) { it[LENGTH_PREFIX_SIZE] = INTERESTED.toByte() }
            }

            Message.NotInterested -> {
                frame(1) { it[LENGTH_PREFIX_SIZE] = NOT_INTERESTED.toByte() }
            }

            is Message.Have -> {
                frame(1 + Int.SIZE_BYTES) {
                    it[LENGTH_PREFIX_SIZE] = HAVE.toByte()
                    writeInt(it, LENGTH_PREFIX_SIZE + 1, message.piece.value)
                }
            }

            is Message.Bitfield -> {
                frame(1 + message.bits.size) {
                    it[LENGTH_PREFIX_SIZE] = BITFIELD.toByte()
                    message.bits.copyInto(it, LENGTH_PREFIX_SIZE + 1)
                }
            }

            is Message.Request -> {
                requireBlockSize(message.length, "request")
                blockFrame(REQUEST, message.piece, message.begin, message.length)
            }

            is Message.Cancel -> {
                blockFrame(CANCEL, message.piece, message.begin, message.length)
            }

            is Message.Extended -> {
                frame(2 + message.payload.size) {
                    it[LENGTH_PREFIX_SIZE] = EXTENDED.toByte()
                    it[LENGTH_PREFIX_SIZE + 1] = message.extensionId.toByte()
                    message.payload.copyInto(it, LENGTH_PREFIX_SIZE + 2)
                }
            }

            is Message.Piece -> {
                throw WireException(
                    "a piece is sent as encodePieceHeader() followed by the block itself, so that the " +
                        "block is never copied into the heap",
                )
            }
        }

    /**
     * The 13 bytes that precede a block on the wire: length prefix, id `7`, index, begin. The
     * block follows straight from the file.
     */
    public fun encodePieceHeader(
        piece: PieceIndex,
        begin: Int,
        blockLength: Int,
    ): ByteArray {
        requireBlockSize(blockLength, "piece")
        val header = ByteArray(LENGTH_PREFIX_SIZE + PIECE_HEADER_SIZE)
        // The prefix counts the block, which is not in this array and never will be.
        writeInt(header, 0, PIECE_HEADER_SIZE + blockLength)
        header[LENGTH_PREFIX_SIZE] = PIECE.toByte()
        writeInt(header, LENGTH_PREFIX_SIZE + 1, piece.value)
        writeInt(header, LENGTH_PREFIX_SIZE + 1 + Int.SIZE_BYTES, begin)
        return header
    }

    public fun readInt(
        bytes: ByteArray,
        at: Int,
    ): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)

    public fun writeInt(
        bytes: ByteArray,
        at: Int,
        value: Int,
    ) {
        bytes[at] = (value ushr 24).toByte()
        bytes[at + 1] = (value ushr 16).toByte()
        bytes[at + 2] = (value ushr 8).toByte()
        bytes[at + 3] = value.toByte()
    }

    private fun blockFrame(
        id: Int,
        piece: PieceIndex,
        begin: Int,
        length: Int,
    ): ByteArray =
        frame(1 + REQUEST_PAYLOAD_SIZE) {
            it[LENGTH_PREFIX_SIZE] = id.toByte()
            writeInt(it, LENGTH_PREFIX_SIZE + 1, piece.value)
            writeInt(it, LENGTH_PREFIX_SIZE + 1 + Int.SIZE_BYTES, begin)
            writeInt(it, LENGTH_PREFIX_SIZE + 1 + Int.SIZE_BYTES * 2, length)
        }

    private inline fun frame(
        payloadSize: Int,
        fill: (ByteArray) -> Unit = {},
    ): ByteArray {
        val out = ByteArray(LENGTH_PREFIX_SIZE + payloadSize)
        writeInt(out, 0, payloadSize)
        fill(out)
        return out
    }

    /**
     * Refused at the point of writing rather than checked at the point of sending: a request that
     * cannot be built cannot be sent, and BEP 3 says peers close connections over this one.
     */
    private fun requireBlockSize(
        length: Int,
        what: String,
    ) {
        if (length <= 0 || length > BLOCK_SIZE) {
            throw WireException("$what length $length is outside 1..$BLOCK_SIZE")
        }
    }

    private fun expectEmpty(
        payloadSize: Int,
        what: String,
    ) {
        if (payloadSize != 0) throw WireException("a $what message carries no payload, got $payloadSize bytes")
    }

    private fun expectSize(
        payloadSize: Int,
        expected: Int,
        what: String,
    ) {
        if (payloadSize != expected) {
            throw WireException("a $what payload is $expected bytes, got $payloadSize")
        }
    }
}
