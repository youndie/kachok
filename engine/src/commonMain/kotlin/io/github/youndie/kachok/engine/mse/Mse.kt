package io.github.youndie.kachok.engine.mse

import io.github.youndie.kachok.engine.InfoHash
import kotlin.random.Random

/**
 * What a completed MSE handshake leaves behind: the two streams, or nothing.
 *
 * Null streams mean `crypto_select` chose plaintext — a legal outcome that both sides offer, and
 * the reason the connection above this does not have to care which happened.
 */
internal class MseResult(
    val encrypt: Rc4?,
    val decrypt: Rc4?,
    /**
     * Bytes the peer sent after its handshake that this one has already read.
     *
     * The accepting side receives the dialler's first BitTorrent message *inside* the MSE
     * handshake — that is what `IA` is for, and it saves a round trip. They are handed back rather
     * than pushed into the socket, because there is no putting them back.
     */
    val carried: ByteArray = ByteArray(0),
)

/**
 * The Message Stream Encryption handshake, both sides.
 *
 * ```
 * A -> B   Ya (96)            PadA (0..512)
 * B -> A   Yb (96)            PadB (0..512)
 * A -> B   req1 (20)          req2^req3 (20)   E(VC, provide, len(PadC), PadC, len(IA), IA)
 * B -> A   E(VC, select, len(PadD), PadD)
 * ```
 *
 * **Neither side knows where the other's padding ends**, which is the whole difficulty and the
 * reason both sides *scan*. B scans its input for `HASH('req1', S)`, which it can compute as soon
 * as it has `Ya`. A scans for B's encrypted `VC`, which it can compute exactly because that is the
 * first eight bytes of B's keystream and A holds the same key. Scanning is bounded on both sides:
 * a peer that sends neither marker is a peer that is not speaking this protocol, and waiting past
 * the bound is how a client hangs instead of failing.
 */
internal object Mse {
    /** The dialling side. [initial] is sent as `IA`, which is normally the BitTorrent handshake. */
    fun dial(
        stream: ByteStream,
        infoHash: InfoHash,
        initial: ByteArray,
        random: Random,
        allowPlaintext: Boolean = true,
    ): MseResult {
        val keys = generateDhKeyPair()
        stream.write(keys.publicKey)
        stream.write(padding(random))

        val theirs = stream.readFully(MseHandshake.KEY_SIZE)
        val secret = keys.agree(theirs)

        val encrypt = started(MseHandshake.keyA(secret, infoHash))
        val decrypt = started(MseHandshake.keyB(secret, infoHash))

        val provide =
            if (allowPlaintext) {
                MseHandshake.CRYPTO_RC4 or MseHandshake.CRYPTO_PLAINTEXT
            } else {
                MseHandshake.CRYPTO_RC4
            }
        val padC = padding(random)
        val body =
            buildBytes {
                append(MseHandshake.VC)
                appendInt(provide)
                appendShort(padC.size)
                append(padC)
                appendShort(initial.size)
                append(initial)
            }
        encrypt.apply(body)
        // **One write and not three.** The three parts are one message, and splitting them hands
        // the peer's parser three arrivals to reassemble for no reason — a difference that is
        // invisible against a pipe and is a real difference on a socket.
        stream.write(MseHandshake.req1(secret) + MseHandshake.req2Xor3(secret, infoHash) + body)

        // B's reply opens with its encrypted VC, which is the first eight bytes of *its* keystream
        // — so this side can predict them exactly and look for them inside padding it cannot
        // measure. A second stream is started for the prediction because consuming the real one
        // here would desynchronise it.
        val expected =
            ByteArray(MseHandshake.VC.size).also {
                started(MseHandshake.keyB(secret, infoHash)).apply(it)
            }
        skipUntil(stream, expected)
        decrypt.discard(MseHandshake.VC.size)

        val tail = stream.readFully(SELECT_SIZE + LENGTH_SIZE)
        decrypt.apply(tail)
        val select = readInt(tail, 0)
        val padD = readShort(tail, SELECT_SIZE)
        if (padD > 0) decrypt.apply(stream.readFully(padD))

        return chosen(select, encrypt, decrypt, provide)
    }

    /**
     * The accepting side.
     *
     * [torrents] answers "do I hold this torrent?", because the dialler names it only as
     * `HASH('req2', SKEY) xor HASH('req3', S)` — a value only somebody who already knows the info
     * hash can recognise, which is the point of it. The lookup is therefore a scan over the
     * torrents this process holds and not a map lookup.
     */
    fun accept(
        stream: ByteStream,
        torrents: () -> List<InfoHash>,
        random: Random,
        allowPlaintext: Boolean = true,
    ): Pair<InfoHash, MseResult> {
        val keys = generateDhKeyPair()
        val theirs = stream.readFully(MseHandshake.KEY_SIZE)
        stream.write(keys.publicKey)
        stream.write(padding(random))

        val secret = keys.agree(theirs)
        skipUntil(stream, MseHandshake.req1(secret))

        val masked = stream.readFully(MASK_SIZE)
        val infoHash =
            torrents().firstOrNull { MseHandshake.req2Xor3(secret, it).contentEquals(masked) }
                ?: throw MseException("the peer asked for a torrent this client does not hold")

        val decrypt = started(MseHandshake.keyA(secret, infoHash))
        val encrypt = started(MseHandshake.keyB(secret, infoHash))

        val head = stream.readFully(MseHandshake.VC.size + SELECT_SIZE + LENGTH_SIZE)
        decrypt.apply(head)
        if (!head.copyOfRange(0, MseHandshake.VC.size).contentEquals(MseHandshake.VC)) {
            // The one check that says "both sides derived the same secret". Without it a wrong key
            // reads as a peer sending nonsense, one layer further on and much harder to place.
            throw MseException("the verification constant did not decrypt to zeros")
        }
        val provide = readInt(head, MseHandshake.VC.size)
        val padC = readShort(head, MseHandshake.VC.size + SELECT_SIZE)
        if (padC > 0) decrypt.apply(stream.readFully(padC))

        val initialLength = stream.readFully(LENGTH_SIZE).also { decrypt.apply(it) }.let { readShort(it, 0) }
        val initial =
            if (initialLength > 0) {
                stream.readFully(initialLength).also { decrypt.apply(it) }
            } else {
                ByteArray(0)
            }

        val select =
            when {
                provide and MseHandshake.CRYPTO_RC4 != 0 -> MseHandshake.CRYPTO_RC4
                allowPlaintext && provide and MseHandshake.CRYPTO_PLAINTEXT != 0 -> MseHandshake.CRYPTO_PLAINTEXT
                else -> throw MseException("the peer offered no encryption this client accepts: $provide")
            }
        val padD = padding(random)
        val reply =
            buildBytes {
                append(MseHandshake.VC)
                appendInt(select)
                appendShort(padD.size)
                append(padD)
            }
        encrypt.apply(reply)
        stream.write(reply)

        val result = chosen(select, encrypt, decrypt, provide)
        return infoHash to MseResult(result.encrypt, result.decrypt, initial)
    }

    /**
     * Whether these bytes open a plaintext BEP 3 handshake rather than an MSE one.
     *
     * **This is the accepting side's first decision and the expensive one to get wrong**: answer
     * "MSE" to a plaintext peer and that peer is lost, which trades one kind of unreachable peer
     * for another. It is safe to decide on the first twenty bytes because BEP 3's are a fixed
     * constant and MSE's are the top of a 768-bit public key — a number whose first twenty bytes
     * spell the BitTorrent header is one this client will not see.
     */
    fun looksPlaintext(head: ByteArray): Boolean {
        if (head.size < PLAINTEXT_PREFIX.size) return false
        return PLAINTEXT_PREFIX.indices.all { head[it] == PLAINTEXT_PREFIX[it] }
    }

    private val PLAINTEXT_PREFIX: ByteArray =
        ByteArray(20).also {
            it[0] = 19
            "BitTorrent protocol".encodeToByteArray().copyInto(it, 1)
        }

    private const val SELECT_SIZE = 4
    private const val LENGTH_SIZE = 2
    private const val MASK_SIZE = 20

    /**
     * How far either side will scan for a marker before giving up.
     *
     * The other side's padding is at most 512 bytes and the marker at most 20, so anything beyond
     * this is a peer that is not speaking MSE. The bound is the difference between failing and
     * hanging, which is the same lesson as
     * [B-96](../../../../../../../../docs/backlog/B-96-the-handshake-read-has-no-deadline.md) one
     * protocol up.
     */
    private const val SCAN_LIMIT = MseHandshake.MAX_PAD + 64

    private fun started(key: ByteArray): Rc4 = rc4(key).also { it.discard(MseHandshake.KEYSTREAM_DISCARD) }

    private fun padding(random: Random): ByteArray =
        ByteArray(random.nextInt(MseHandshake.MAX_PAD + 1)).also { random.nextBytes(it) }

    private fun chosen(
        select: Int,
        encrypt: Rc4,
        decrypt: Rc4,
        provide: Int,
    ): MseResult =
        when (select) {
            MseHandshake.CRYPTO_RC4 -> MseResult(encrypt, decrypt)
            MseHandshake.CRYPTO_PLAINTEXT -> MseResult(null, null)
            else -> throw MseException("the peer selected $select, which was not among the offered $provide")
        }

    /** Reads one byte at a time until [marker] has just gone past, or the bound is reached. */
    private fun skipUntil(
        stream: ByteStream,
        marker: ByteArray,
    ) {
        val window = ByteArray(marker.size)
        var filled = 0
        var seen = 0
        val one = ByteArray(1)
        while (seen < SCAN_LIMIT + marker.size) {
            if (stream.read(one, 0, 1) < 0) {
                throw MseException("the peer closed after $seen bytes while this client looked for its marker")
            }
            seen++
            if (filled < window.size) {
                window[filled++] = one[0]
            } else {
                window.copyInto(window, 0, 1, window.size)
                window[window.size - 1] = one[0]
            }
            if (filled == window.size && window.contentEquals(marker)) return
        }
        throw MseException("no MSE marker in $SCAN_LIMIT bytes; the peer is not speaking it")
    }

    private fun readInt(
        bytes: ByteArray,
        at: Int,
    ): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)

    private fun readShort(
        bytes: ByteArray,
        at: Int,
    ): Int = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    private class Builder {
        private var bytes = ByteArray(0)

        fun append(more: ByteArray) {
            bytes += more
        }

        fun appendInt(value: Int) {
            append(
                byteArrayOf(
                    (value ushr 24).toByte(),
                    (value ushr 16).toByte(),
                    (value ushr 8).toByte(),
                    value.toByte(),
                ),
            )
        }

        fun appendShort(value: Int) {
            append(byteArrayOf((value ushr 8).toByte(), value.toByte()))
        }

        fun bytes(): ByteArray = bytes
    }

    private fun buildBytes(build: Builder.() -> Unit): ByteArray = Builder().apply(build).bytes()
}
