package io.github.youndie.kachok.engine.mse

/**
 * The two primitives MSE needs that Kotlin common cannot express.
 *
 * **Interfaces with an `expect` factory, and not `expect class`.** The latter is still Beta and
 * this project compiles with warnings as errors, but the shape is the right one anyway and is the
 * rule the engine already follows: common code sees a capability through an interface, and which
 * implementation it gets is the platform's business.
 */
internal interface DhKeyPair {
    /** This side's public key, 96 bytes, big-endian, left-padded (MSE calls it `Ya` or `Yb`). */
    val publicKey: ByteArray

    /** The shared secret `S` from the other side's public key: 96 bytes, padded the same way. */
    fun agree(theirPublicKey: ByteArray): ByteArray
}

/**
 * A running RC4 keystream.
 *
 * **Running is the whole requirement**, and the JDK makes it easy to get wrong: `Cipher.doFinal`
 * restarts the stream, so a client using it re-encrypts every message with the same keystream
 * bytes. Against a loopback fake that is symmetric and looks fine; against a real peer it is
 * gibberish after the first message, and no test written against this client's own other half
 * would catch it. Measured on JDK 25.0.2: `update` returns `b2396305f03dc027` then
 * `ccc3524a0a1118a8` for RFC 6229's 40-bit key, and `doFinal` returns the first block twice.
 */
internal interface Rc4 {
    /** Encrypts or decrypts in place — RC4 is its own inverse — and advances the stream. */
    fun apply(
        bytes: ByteArray,
        fromIndex: Int = 0,
        toIndex: Int = bytes.size,
    )

    /** Advances the stream by [count] bytes and throws them away. */
    fun discard(count: Int)
}

internal expect fun generateDhKeyPair(): DhKeyPair

internal expect fun rc4(key: ByteArray): Rc4
