package io.github.youndie.kachok.cli.serve

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * RFC 6455, the half a server needs, and no more.
 *
 * **Written here rather than taken from a server library**, because of the artifact this goes in:
 * the headless client's run-time image is a measured thing whose module list is guarded by
 * `scripts/verify_runtime_image.sh`, and a server stack pulled in for one loopback socket changes
 * what phase 1 spent its measurements on. What is actually needed is a handshake and a frame codec.
 *
 * **The peer is a browser, which this project does not control.** So this is not "the subset our
 * own client happens to send": it reassembles fragments, answers pings, echoes a close, and refuses
 * an unmasked frame the way the specification says to. It is checked against implementations that
 * are not this one — the JDK's `java.net.http.WebSocket` and a real browser
 * ([B-40](../../../../../../../../docs/backlog/B-40-wasmjs-ui-is-a-client-of-the-headless-engine.md)).
 *
 * Text frames only. Nothing this protocol carries is binary: a `.torrent` arrives base64 inside
 * JSON, because the client may not be on the machine that has the file.
 */
internal object WebSocket {
    /** RFC 6455 §1.3. Not a secret and not a hash of one; it is a constant that says "this is a WebSocket". */
    private const val MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    const val OPCODE_CONTINUATION: Int = 0x0
    const val OPCODE_TEXT: Int = 0x1
    const val OPCODE_BINARY: Int = 0x2
    const val OPCODE_CLOSE: Int = 0x8
    const val OPCODE_PING: Int = 0x9
    const val OPCODE_PONG: Int = 0xA

    /** What a server answers a `Sec-WebSocket-Key` with, so the client knows it reached a server. */
    fun accept(key: String): String =
        Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key.trim() + MAGIC).encodeToByteArray()),
        )

    /**
     * One frame off the wire, or null at the end of it.
     *
     * A client's frames are **always** masked (RFC 6455 §5.1) and a server must fail the connection
     * if one is not: an unmasked frame from a browser means something is rewriting the stream, and
     * accepting it is how a proxy gets taught to cache a WebSocket.
     */
    fun readFrame(input: InputStream): Frame? {
        val first = input.read()
        if (first < 0) return null
        val second = input.readByteOrThrow()
        val fin = first and 0x80 != 0
        val opcode = first and 0x0F
        val masked = second and 0x80 != 0
        if (!masked) throw ProtocolException("a client frame was not masked")
        var length = (second and 0x7F).toLong()
        if (length == LENGTH_16) {
            length = ((input.readByteOrThrow() shl 8) or input.readByteOrThrow()).toLong()
        } else if (length == LENGTH_64) {
            length = 0
            repeat(Long.SIZE_BYTES) { length = (length shl 8) or input.readByteOrThrow().toLong() }
            // A length with the top bit set is invalid per §5.2, and on a JVM it is also a negative
            // array size — the kind of number that turns a protocol error into an exception three
            // frames later.
            if (length < 0) throw ProtocolException("a frame claimed a negative length")
        }
        if (length > MAX_FRAME) throw ProtocolException("a frame claimed $length bytes")
        val mask = ByteArray(MASK_BYTES).also { input.readFully(it) }
        val payload = ByteArray(length.toInt()).also { input.readFully(it) }
        // Unmasking is the same operation as masking; the key repeats every four bytes.
        for (at in payload.indices) payload[at] = (payload[at].toInt() xor mask[at % MASK_BYTES].toInt()).toByte()
        return Frame(fin, opcode, payload)
    }

    /**
     * One frame onto the wire, never masked.
     *
     * A server that masks is a server a browser hangs up on (§5.1), which is the mirror image of
     * the rule above and is just as easy to get backwards.
     */
    fun writeFrame(
        output: OutputStream,
        opcode: Int,
        payload: ByteArray,
    ) {
        val header = mutableListOf<Byte>()
        header += (0x80 or opcode).toByte()
        when {
            payload.size < LENGTH_16 -> {
                header += payload.size.toByte()
            }

            payload.size <= UNSIGNED_16 -> {
                header += LENGTH_16.toByte()
                header += (payload.size shr 8).toByte()
                header += payload.size.toByte()
            }

            else -> {
                header += LENGTH_64.toByte()
                for (shift in (Long.SIZE_BITS - Byte.SIZE_BITS) downTo 0 step Byte.SIZE_BITS) {
                    header += (payload.size.toLong() shr shift).toByte()
                }
            }
        }
        // One write for the header and the payload: two writes is two TCP segments for every
        // snapshot, and a reader that gets the header without the body has to wait for the next one.
        output.write(header.toByteArray() + payload)
        output.flush()
    }

    /** A close with a status code, which is what a browser shows in its console instead of "went away". */
    fun writeClose(
        output: OutputStream,
        code: Int,
        reason: String = "",
    ) {
        val bytes = reason.encodeToByteArray()
        val payload = ByteArray(2 + bytes.size)
        payload[0] = (code shr 8).toByte()
        payload[1] = code.toByte()
        bytes.copyInto(payload, 2)
        writeFrame(output, OPCODE_CLOSE, payload)
    }

    class Frame(
        val fin: Boolean,
        val opcode: Int,
        val payload: ByteArray,
    )

    class ProtocolException(
        message: String,
    ) : java.io.IOException(message)

    private fun InputStream.readByteOrThrow(): Int {
        val value = read()
        if (value < 0) throw EOFException("the connection ended inside a frame header")
        return value
    }

    private fun InputStream.readFully(into: ByteArray) {
        var at = 0
        while (at < into.size) {
            val read = read(into, at, into.size - at)
            if (read < 0) throw EOFException("the connection ended inside a frame")
            at += read
        }
    }

    private const val LENGTH_16 = 126L
    private const val LENGTH_64 = 127L
    private const val UNSIGNED_16 = 0xFFFF
    private const val MASK_BYTES = 4

    /**
     * Sixteen mebibytes, which is far more than anything this protocol sends and far less than a
     * length a client can make this process allocate. A `.torrent` of a hundred thousand pieces is
     * two megabytes of base64.
     */
    private const val MAX_FRAME = 16L * 1024 * 1024
}
