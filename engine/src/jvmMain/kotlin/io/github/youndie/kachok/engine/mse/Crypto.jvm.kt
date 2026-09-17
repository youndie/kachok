package io.github.youndie.kachok.engine.mse

import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

// `BigInteger.modPow` and the JDK's own ARCFOUR, and neither is hand-written. `modPow` on MSE's
// 768-bit prime costs 3 ms here (measured on JDK 25.0.2), which is once per connection and beneath
// notice; a hand-rolled RC4 would be twenty lines of the kind nobody reviews twice.

/** MSE's prime and generator — the specification's own, not RFC 2409's; see [MseHandshake.PRIME_HEX]. */
private val P = BigInteger(MseHandshake.PRIME_HEX, 16)

private val G = BigInteger.valueOf(MseHandshake.GENERATOR.toLong())

private class JvmDhKeyPair(
    private val secret: BigInteger,
) : DhKeyPair {
    override val publicKey: ByteArray = G.modPow(secret, P).toFixedWidth()

    override fun agree(theirPublicKey: ByteArray): ByteArray =
        BigInteger(1, theirPublicKey).modPow(secret, P).toFixedWidth()

    /**
     * 96 bytes, left-padded, and never `BigInteger.toByteArray()` on its own.
     *
     * That method returns the two's-complement form: a leading zero byte whenever the top bit is
     * set, and *fewer* than 96 bytes whenever the value is small. Both are wrong on the wire, both
     * happen roughly half the time and one time in 2^8 respectively, and the failure is a peer that
     * disconnects with no message.
     */
    private fun BigInteger.toFixedWidth(): ByteArray {
        val raw = toByteArray()
        val out = ByteArray(KEY_SIZE)
        if (raw.size >= KEY_SIZE) {
            raw.copyInto(out, 0, raw.size - KEY_SIZE, raw.size)
        } else {
            raw.copyInto(out, KEY_SIZE - raw.size)
        }
        return out
    }

    private companion object {
        const val KEY_SIZE = MseHandshake.KEY_SIZE
    }
}

internal actual fun generateDhKeyPair(): DhKeyPair = JvmDhKeyPair(BigInteger(PRIVATE_BITS, SecureRandom()))

/** MSE's own choice: "160 bit random integer", which is what every client agrees on. */
private const val PRIVATE_BITS = 160

internal actual fun rc4(key: ByteArray): Rc4 = JvmRc4(key)

private class JvmRc4(
    key: ByteArray,
) : Rc4 {
    // ARCFOUR is the JCE's name for it. The key is a SHA-1 digest, so the provider's 40-bit floor
    // is never in question.
    private val cipher =
        Cipher.getInstance("ARCFOUR").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ARCFOUR"))
        }

    override fun apply(
        bytes: ByteArray,
        fromIndex: Int,
        toIndex: Int,
    ) {
        // `update` and never `doFinal`: the latter restarts the keystream. See the expect side.
        val out = cipher.update(bytes, fromIndex, toIndex - fromIndex)
        out.copyInto(bytes, fromIndex)
    }

    override fun discard(count: Int) {
        cipher.update(ByteArray(count))
    }
}
