package io.github.youndie.kachok.engine.mse

import io.github.youndie.kachok.engine.InfoHash
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** B-100: the two primitives, before anything is built on them. */
class CryptoTest {
    private val infoHash = InfoHash(ByteArray(20) { it.toByte() })

    @Test
    fun twoSidesReachTheSameSecretAndItIsNinetySixBytes() {
        val a = generateDhKeyPair()
        val b = generateDhKeyPair()

        assertEquals(MseHandshake.KEY_SIZE, a.publicKey.size, "a public key is 96 bytes on the wire")
        assertEquals(MseHandshake.KEY_SIZE, b.publicKey.size)
        assertContentEquals(a.agree(b.publicKey), b.agree(a.publicKey), "the two sides disagree on S")
        assertEquals(MseHandshake.KEY_SIZE, a.agree(b.publicKey).size, "S is padded to 96 too")
    }

    /**
     * **The padding is the point and it is why this asserts a width rather than a value.**
     * `BigInteger.toByteArray()` gives the two's-complement form: 97 bytes when the top bit is set,
     * fewer than 96 when the value is small. Both are wrong on the wire, both happen often, and the
     * symptom is a peer that hangs up without saying anything.
     */
    @Test
    fun everyKeyIsPaddedToTheFullWidth() {
        repeat(40) {
            val pair = generateDhKeyPair()
            assertEquals(MseHandshake.KEY_SIZE, pair.publicKey.size, "key $it was ${pair.publicKey.size} bytes")
        }
    }

    /**
     * RFC 6229's 40-bit vector, and then the thing that actually breaks MSE.
     *
     * A cipher that restarts its keystream per call passes every symmetric test — this client
     * talking to itself — and produces gibberish against any other implementation. So the second
     * block is asserted explicitly, and it is a different block from the first.
     */
    @Test
    fun theKeystreamContinuesAcrossCallsRatherThanRestarting() {
        val stream = rc4(byteArrayOf(1, 2, 3, 4, 5))
        val first = ByteArray(8)
        stream.apply(first)
        val second = ByteArray(8)
        stream.apply(second)

        assertEquals("b2396305f03dc027", first.hex(), "RFC 6229's first eight keystream bytes")
        assertEquals("ccc3524a0a1118a8", second.hex(), "the stream restarted instead of continuing")
        assertFalse(first.hex() == second.hex())
    }

    @Test
    fun discardingAdvancesTheStreamByExactlyThatMany() {
        val discarded = rc4(byteArrayOf(1, 2, 3, 4, 5)).also { it.discard(8) }
        val byStepping = rc4(byteArrayOf(1, 2, 3, 4, 5)).also { it.apply(ByteArray(8)) }
        val one = ByteArray(8).also { discarded.apply(it) }
        val two = ByteArray(8).also { byStepping.apply(it) }
        assertContentEquals(two, one, "discard(8) and encrypting eight bytes must leave the same position")
    }

    /** RC4 is its own inverse, which is what lets one implementation serve both directions. */
    @Test
    fun whatOneStreamEncryptsAnotherWithTheSameKeyDecrypts() {
        val plain = "the first message of the wire, unencrypted".encodeToByteArray()
        val carried = plain.copyOf()
        rc4(byteArrayOf(9, 8, 7, 6, 5)).apply(carried)
        assertFalse(carried.contentEquals(plain), "nothing was encrypted")
        rc4(byteArrayOf(9, 8, 7, 6, 5)).apply(carried)
        assertContentEquals(plain, carried)
    }

    /** Encrypting a slice leaves the bytes around it alone, because frames are written in place. */
    @Test
    fun aRangeIsEncryptedAndItsSurroundingsAreNot() {
        val buffer = ByteArray(16) { 0x5A }
        rc4(byteArrayOf(1, 2, 3, 4, 5)).apply(buffer, fromIndex = 4, toIndex = 12)
        assertTrue(buffer.copyOfRange(0, 4).all { it == 0x5A.toByte() }, "the head was touched")
        assertTrue(buffer.copyOfRange(12, 16).all { it == 0x5A.toByte() }, "the tail was touched")
        assertFalse(buffer.copyOfRange(4, 12).all { it == 0x5A.toByte() }, "the middle was not encrypted")
    }

    /**
     * The five derived values are five different values.
     *
     * MSE's prefixes exist so that keys, the request marker and the obfuscated hash cannot be
     * confused with one another; a hash function called without them would give one value five
     * times and nothing would visibly fail until another client refused the handshake.
     */
    @Test
    fun thePrefixesMakeFiveDistinctValuesFromOneSecret() {
        val secret = ByteArray(MseHandshake.KEY_SIZE) { (it * 7).toByte() }
        val derived =
            listOf(
                MseHandshake.keyA(secret, infoHash),
                MseHandshake.keyB(secret, infoHash),
                MseHandshake.req1(secret),
                MseHandshake.req2Xor3(secret, infoHash),
                MseHandshake.hash("req3", secret),
            ).map { it.hex() }
        assertEquals(derived.size, derived.toSet().size, "two derived values collided: $derived")
        derived.forEach { assertEquals(40, it.length, "every one of them is a SHA-1") }
    }

    /** The obfuscated hash is recoverable by a peer that knows the torrent, and only by one. */
    @Test
    fun theObfuscatedInfoHashUnmasksWithTheSecret() {
        val secret = ByteArray(MseHandshake.KEY_SIZE) { (it * 3 + 1).toByte() }
        val masked = MseHandshake.req2Xor3(secret, infoHash)
        val three = MseHandshake.hash("req3", secret)
        val recovered = ByteArray(masked.size) { (masked[it].toInt() xor three[it].toInt()).toByte() }
        assertContentEquals(MseHandshake.hash("req2", infoHash.bytes), recovered)

        val other = InfoHash(ByteArray(20) { (it + 1).toByte() })
        assertFalse(
            MseHandshake.req2Xor3(secret, other).contentEquals(masked),
            "a different torrent produced the same masked hash",
        )
    }

    private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
