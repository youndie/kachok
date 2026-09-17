package io.github.youndie.kachok.engine.mse

/**
 * The two operations the MSE handshake needs from a connection, and nothing else.
 *
 * **It exists so the handshake can be tested without a socket.** The exchange is five messages with
 * variable padding and two markers that have to be *scanned* for, which is the part that goes
 * wrong; against a real socket a scanning bug looks like a peer that went quiet, and there is
 * nothing to step through. Behind this interface it is an in-memory pipe and an assertion.
 *
 * Blocking, like everything else on a peer connection here: the reader runs on a virtual thread and
 * parking one is what this design is for (research §1.1).
 */
internal interface ByteStream {
    /** Fills at most `toIndex - fromIndex` bytes, returns how many, or -1 at end of stream. */
    fun read(
        into: ByteArray,
        fromIndex: Int,
        toIndex: Int,
    ): Int

    fun write(
        bytes: ByteArray,
        fromIndex: Int = 0,
        toIndex: Int = bytes.size,
    )
}

/** Reads exactly [count] bytes or throws, because every fixed-width field in MSE is mandatory. */
internal fun ByteStream.readFully(count: Int): ByteArray {
    val buffer = ByteArray(count)
    var read = 0
    while (read < count) {
        val got = read(buffer, read, count)
        if (got < 0) throw MseException("the peer closed after $read of $count bytes")
        read += got
    }
    return buffer
}

/** A peer that did not speak MSE, or spoke it wrongly. Never a bug in this client by itself. */
internal class MseException(
    message: String,
) : Exception(message)
