package io.github.youndie.kachok.engine.io

import io.github.youndie.kachok.engine.mse.ByteStream
import io.github.youndie.kachok.engine.mse.Rc4
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SocketChannel
import java.nio.channels.WritableByteChannel
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * The socket as the MSE handshake sees it: bytes in, bytes out, one deadline over the whole
 * exchange.
 *
 * **Read through the socket's input stream and not the channel**, for the reason
 * `SocketPeerConnection.readHandshakeBy` gives at length: a blocking channel read has no deadline
 * and ignores `SO_TIMEOUT`, and the MSE handshake is five messages with two scans in it — far more
 * places for a peer to go quiet than the sixty-eight bytes of a plaintext one. The deadline is
 * recomputed before every read, because `SO_TIMEOUT` is per read and a peer sending one byte a
 * second would otherwise renew it for ever.
 *
 * [prefix] is what the caller has already taken off the socket. The accepting side must read the
 * first bytes to tell a plaintext handshake from a public key, and there is no putting them back.
 */
internal class SocketByteStream(
    private val socket: SocketChannel,
    private val timeout: Duration,
    private val prefix: ByteArray = ByteArray(0),
) : ByteStream {
    private val deadline = TimeSource.Monotonic.markNow() + timeout
    private val adaptor = socket.socket()
    private val stream = adaptor.getInputStream()
    private var served = 0

    override fun read(
        into: ByteArray,
        fromIndex: Int,
        toIndex: Int,
    ): Int {
        if (served < prefix.size) {
            val taken = minOf(toIndex - fromIndex, prefix.size - served)
            prefix.copyInto(into, fromIndex, served, served + taken)
            served += taken
            return taken
        }
        val left = -deadline.elapsedNow()
        if (!left.isPositive()) {
            throw SocketTimeoutException("the peer did not finish its encrypted handshake in $timeout")
        }
        // At least one millisecond: zero is `SO_TIMEOUT` for "wait for ever".
        adaptor.soTimeout = left.inWholeMilliseconds.coerceAtLeast(1).toInt()
        return stream.read(into, fromIndex, toIndex - fromIndex)
    }

    override fun write(
        bytes: ByteArray,
        fromIndex: Int,
        toIndex: Int,
    ) {
        val out = ByteBuffer.wrap(bytes, fromIndex, toIndex - fromIndex)
        while (out.hasRemaining()) socket.write(out)
    }

    /**
     * Back to no deadline, and the caller owes this on every path.
     *
     * Past the handshake the wire is read through the channel, where silence is legitimate: a seed
     * with nothing to say sends a keep-alive every two minutes and nothing in between.
     */
    fun finish() {
        try {
            adaptor.soTimeout = 0
        } catch (gone: SocketException) {
            // The socket is already closed, which is every failing path through here.
        }
    }
}

/**
 * The keystream, applied to everything that follows the handshake.
 *
 * **An encrypted connection cannot use `transferTo`**, and that is the one real cost of this
 * feature rather than an implementation detail: the zero-copy upload path research §1.3d measured
 * moves a block from the page cache to the socket without it ever entering this process, and there
 * is nowhere in that path to apply RC4. So a block on an encrypted connection is read into a heap
 * array, encrypted, and written — which is what [EncryptingChannel] is, and why `transferBlock` is
 * handed *this* rather than the socket. A plaintext connection is untouched and still pays
 * nothing.
 *
 * RC4 is a stream: the bytes must go through in the order they leave or arrive, which is why each
 * direction has its own object and each is touched by exactly one coroutine — the connection's
 * single reader and its single writer.
 */
internal class DecryptingChannel(
    private val socket: SocketChannel,
    private val rc4: Rc4,
) : ReadableByteChannel {
    private val scratch = ByteArray(CHUNK)

    override fun read(destination: ByteBuffer): Int {
        val want = minOf(destination.remaining(), scratch.size)
        if (want == 0) return 0
        val read = socket.read(ByteBuffer.wrap(scratch, 0, want))
        if (read <= 0) return read
        rc4.apply(scratch, 0, read)
        destination.put(scratch, 0, read)
        return read
    }

    override fun isOpen(): Boolean = socket.isOpen

    override fun close() {
        socket.close()
    }
}

/** The other direction; see [DecryptingChannel]. */
internal class EncryptingChannel(
    private val socket: SocketChannel,
    private val rc4: Rc4,
) : WritableByteChannel {
    private val scratch = ByteArray(CHUNK)

    override fun write(source: ByteBuffer): Int {
        var written = 0
        while (source.hasRemaining()) {
            val taken = minOf(source.remaining(), scratch.size)
            source.get(scratch, 0, taken)
            rc4.apply(scratch, 0, taken)
            val out = ByteBuffer.wrap(scratch, 0, taken)
            while (out.hasRemaining()) socket.write(out)
            written += taken
        }
        return written
    }

    override fun isOpen(): Boolean = socket.isOpen

    override fun close() {
        socket.close()
    }
}

/**
 * Bytes the handshake already took off the wire, served before the socket is read again.
 *
 * The accepting side receives the dialler's first message *inside* the MSE handshake — that is
 * what `IA` is for, and it saves a round trip. Those bytes are decrypted already, so they must not
 * go through the decryptor a second time, which is why this sits *outside* it rather than inside.
 */
internal class PrefixedChannel(
    private val prefix: ByteArray,
    private val then: ReadableByteChannel,
) : ReadableByteChannel {
    private var served = 0

    override fun read(destination: ByteBuffer): Int {
        if (served < prefix.size) {
            val taken = minOf(destination.remaining(), prefix.size - served)
            destination.put(prefix, served, taken)
            served += taken
            return taken
        }
        return then.read(destination)
    }

    override fun isOpen(): Boolean = then.isOpen

    override fun close() {
        then.close()
    }
}

/** One block, which is the largest thing either direction moves in one go. */
private const val CHUNK = 16 * 1024
