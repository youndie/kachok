package io.github.youndie.kachok.engine.mse

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.platform.sha1

/**
 * Message Stream Encryption: the constants and the key schedule.
 *
 * **This is not a BEP.** MSE was specified on the Vuze wiki in 2006 and every mainstream client
 * implements it; there is no numbered document to cite, which is why the layout is spelled out
 * here rather than referenced.
 *
 * **It is obfuscation and interoperability, not privacy, and this file says so once so that
 * nobody has to infer it.** RC4 is broken, the prime is 768 bits, and the info hash — the one thing
 * an observer wants — is recoverable by anyone who already knows which torrent to test for. What it
 * buys is the two things this client actually needs: peers that refuse plaintext connections, and a
 * first packet that does not begin with the literal string a traffic shaper looks for.
 *
 * The exchange, A dialling and B accepting:
 *
 * ```
 * A -> B   Ya                              96 bytes, then 0..512 bytes of padding
 * B -> A   Yb                              96 bytes, then 0..512 bytes of padding
 * A -> B   HASH('req1', S)                 20 bytes, and the first thing B can search for
 *          HASH('req2', SKEY) xor HASH('req3', S)
 *          ENCRYPT(VC, crypto_provide, len(PadC), PadC, len(IA), IA)
 * B -> A   ENCRYPT(VC, crypto_select, len(PadD), PadD)
 * ```
 *
 * `SKEY` is the info hash, which is how B knows *which* torrent to answer for without A naming it
 * in the clear.
 */
internal object MseHandshake {
    /**
     * MSE's own 768-bit prime, in hexadecimal — **and not RFC 2409's group 1, which it is not.**
     *
     * The two agree for the first 180 hexadecimal digits, because both are built from the digits
     * of π, and differ in the last twelve: RFC 2409 ends `...A63A3620FFFFFFFFFFFFFFFF` and this one
     * ends `...A63A36210000000000090563`. An implementation that reads "the 768-bit MODP prime" and
     * reaches for the RFC gets a number that agrees with every other implementation that made the
     * same reading — this client and its independent Python check both did — and with nobody
     * else. libtorrent's `pe_crypto.cpp` and Transmission's `crypto.c` carry this one.
     *
     * It is common code and a string because the platform `actual` is its only reader and common
     * code has no 768-bit integer; the test that pins its tail lives beside it for that reason.
     */
    const val PRIME_HEX: String =
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
            "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
            "4FE1356D6D51C245E485B576625E7EC6F44C42E9A63A36210000000000090563"

    /** The generator, which is 2 in both documents. */
    const val GENERATOR: Int = 2

    /** Public keys are this wide on the wire, left-padded, whatever the number's magnitude. */
    const val KEY_SIZE: Int = 96

    /** Each side sends up to this much random padding, so that no two handshakes are the same length. */
    const val MAX_PAD: Int = 512

    /**
     * The verification constant: eight zero bytes, encrypted.
     *
     * It is how each side proves it derived the same secret — and, for B, how it finds the start of
     * A's third message inside a stream whose padding length it does not know.
     */
    val VC: ByteArray = ByteArray(8)

    /** `crypto_provide` / `crypto_select` bits. Both are offered; the peer picks. */
    const val CRYPTO_PLAINTEXT: Int = 0x01
    const val CRYPTO_RC4: Int = 0x02

    /**
     * MSE's own keyed hash: `SHA1(prefix || parts...)`.
     *
     * The prefixes are ASCII and are part of the specification — `req1`, `req2`, `req3`, `keyA`,
     * `keyB` — and exist so that five different values derived from one secret cannot be confused
     * with one another.
     */
    fun hash(
        prefix: String,
        vararg parts: ByteArray,
    ): ByteArray {
        var size = prefix.length
        parts.forEach { size += it.size }
        val buffer = ByteArray(size)
        var at = 0
        prefix.forEach { buffer[at++] = it.code.toByte() }
        parts.forEach {
            it.copyInto(buffer, at)
            at += it.size
        }
        return sha1(buffer, 0, buffer.size)
    }

    /**
     * The two RC4 keys, and which side uses which.
     *
     * **The dialling side encrypts with `keyA` and decrypts with `keyB`**, and the accepting side
     * does the opposite. One key for both directions would be a stream reused twice, which for RC4
     * is the textbook way to lose the plaintext.
     */
    fun keyA(
        secret: ByteArray,
        infoHash: InfoHash,
    ): ByteArray = hash("keyA", secret, infoHash.bytes)

    fun keyB(
        secret: ByteArray,
        infoHash: InfoHash,
    ): ByteArray = hash("keyB", secret, infoHash.bytes)

    /** What B searches its input for to find where A's third message begins. */
    fun req1(secret: ByteArray): ByteArray = hash("req1", secret)

    /**
     * The obfuscated info hash: `HASH('req2', SKEY) xor HASH('req3', S)`.
     *
     * B cannot look up the torrent by a hash it can read, because then anyone watching could too;
     * it computes this for every torrent it holds and compares. That is a scan over open torrents
     * per incoming connection, which is why a client with thousands of them caches it.
     */
    fun req2Xor3(
        secret: ByteArray,
        infoHash: InfoHash,
    ): ByteArray {
        val two = hash("req2", infoHash.bytes)
        val three = hash("req3", secret)
        return ByteArray(two.size) { (two[it].toInt() xor three[it].toInt()).toByte() }
    }

    /**
     * The keystream's first 1 024 bytes are thrown away, on both keys and in both directions.
     *
     * RC4's early output leaks key material — the Fluhrer–Mantin–Shamir result — and MSE's answer
     * is the usual one. Forgetting it is invisible against this client's own other half and is
     * gibberish against everybody else.
     */
    const val KEYSTREAM_DISCARD: Int = 1024
}
