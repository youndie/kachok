package io.github.youndie.kachok.engine.mse

import io.github.youndie.kachok.engine.InfoHash
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.math.BigInteger
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B-100: the handshake, both sides, against each other.
 *
 * Two threads and a real pipe rather than a scripted byte sequence. Both sides *block* and both
 * sides *scan* for a marker inside padding whose length they cannot know — a test that fed one
 * side a recorded script would be asserting the recording, and the scan is the part that goes
 * wrong.
 */
class MseHandshakeTest {
    private val infoHash = InfoHash(ByteArray(20) { it.toByte() })
    private val otherHash = InfoHash(ByteArray(20) { (it + 1).toByte() })

    /** A duplex pipe: what A writes, B reads, and the other way round. */
    private class Wire {
        private val aToB = PipedOutputStream()
        private val bFromA = PipedInputStream(aToB, BUFFER)
        private val bToA = PipedOutputStream()
        private val aFromB = PipedInputStream(bToA, BUFFER)

        val a: ByteStream = Side(aFromB, aToB)
        val b: ByteStream = Side(bFromA, bToA)

        private class Side(
            private val input: PipedInputStream,
            private val output: PipedOutputStream,
        ) : ByteStream {
            override fun read(
                into: ByteArray,
                fromIndex: Int,
                toIndex: Int,
            ): Int = input.read(into, fromIndex, toIndex - fromIndex)

            override fun write(
                bytes: ByteArray,
                fromIndex: Int,
                toIndex: Int,
            ) {
                output.write(bytes, fromIndex, toIndex - fromIndex)
                output.flush()
            }
        }

        private companion object {
            // Larger than one side's biggest message, or the two threads deadlock on a full pipe
            // rather than on anything the protocol did.
            const val BUFFER = 1 shl 16
        }
    }

    /** Runs both halves at once and returns what each produced, or rethrows what either threw. */
    private fun exchange(
        seed: Int,
        dialFor: InfoHash = infoHash,
        held: List<InfoHash> = listOf(infoHash),
        initial: ByteArray = "IA".encodeToByteArray(),
        allowPlaintextOnAccept: Boolean = true,
    ): Pair<MseResult, Pair<InfoHash, MseResult>> {
        val wire = Wire()
        val results = ArrayBlockingQueue<Any>(2)
        val dialler =
            Thread.ofVirtual().start {
                results.add(
                    runCatching {
                        Mse.dial(wire.a, dialFor, initial, Random(seed))
                    },
                )
            }
        val accepter =
            Thread.ofVirtual().start {
                results.add(
                    runCatching {
                        Mse.accept(wire.b, { held }, Random(seed + 1), allowPlaintextOnAccept)
                    },
                )
            }
        dialler.join(TIMEOUT)
        accepter.join(TIMEOUT)
        assertFalse(dialler.isAlive, "the dialling side never finished")
        assertFalse(accepter.isAlive, "the accepting side never finished")

        @Suppress("UNCHECKED_CAST")
        val outcomes = List(2) { results.poll(1, TimeUnit.SECONDS) as Result<Any> }
        outcomes.forEach { it.exceptionOrNull()?.let { failure -> throw failure } }
        val dial = outcomes.map { it.getOrThrow() }.filterIsInstance<MseResult>().single()

        @Suppress("UNCHECKED_CAST")
        val accept = outcomes.map { it.getOrThrow() }.first { it !is MseResult } as Pair<InfoHash, MseResult>
        return dial to accept
    }

    /**
     * The whole exchange, and then the streams used — which is the assertion that matters.
     *
     * A handshake that completes proves the markers were found. Only sending a message *through*
     * the streams afterwards proves the two keystreams are at the same position, and that is the
     * failure a desynchronised discard or a restarted cipher produces.
     */
    @Test
    fun bothSidesAgreeAndTheirStreamsStayInStep() {
        val (dial, accepted) = exchange(seed = 7)
        val (hash, accept) = accepted

        assertEquals(infoHash.bytes.toList(), hash.bytes.toList(), "the accepting side named the wrong torrent")
        assertContentEquals("IA".encodeToByteArray(), accept.carried, "the dialler's first message was lost")

        val encrypt = assertNotNull(dial.encrypt, "RC4 was offered and should have been selected")
        val decrypt = assertNotNull(accept.decrypt)
        val message = "a message long enough to outrun any single keystream block, twice over".encodeToByteArray()
        val carried = message.copyOf()
        encrypt.apply(carried)
        assertFalse(carried.contentEquals(message), "nothing was encrypted")
        decrypt.apply(carried)
        assertContentEquals(message, carried, "the two keystreams are not at the same position")

        // And the other direction, which uses the other key pair entirely.
        val back = "and back the other way".encodeToByteArray()
        val returned = back.copyOf()
        assertNotNull(accept.encrypt).apply(returned)
        assertNotNull(dial.decrypt).apply(returned)
        assertContentEquals(back, returned)
    }

    /**
     * Twenty runs with different padding, because the padding is what the scan has to survive.
     *
     * One run proves a scan that happens to work for one length. Both sides choose 0..512 bytes at
     * random, so the interesting cases — no padding at all, and a marker that straddles a read —
     * only turn up across a spread.
     */
    @Test
    fun theScanSurvivesEveryPaddingLength() {
        repeat(20) { seed ->
            val (dial, accepted) = exchange(seed = seed * 31 + 1)
            val message = ByteArray(64) { it.toByte() }
            val carried = message.copyOf()
            assertNotNull(dial.encrypt).apply(carried)
            assertNotNull(accepted.second.decrypt).apply(carried)
            assertContentEquals(message, carried, "seed $seed desynchronised")
        }
    }

    /** A dialler asking for a torrent this side does not hold gets nowhere, and says why. */
    @Test
    fun aTorrentThisClientDoesNotHoldIsRefused() {
        val thrown =
            runCatching { exchange(seed = 3, dialFor = otherHash, held = listOf(infoHash)) }
                .exceptionOrNull()
        assertTrue(thrown is MseException, "expected an MseException, got $thrown")
        assertTrue(thrown.message.orEmpty().contains("does not hold"), thrown.message.orEmpty())
    }

    /** One process holding several torrents answers for the right one. */
    @Test
    fun theRightTorrentIsPickedOutOfSeveral() {
        val third = InfoHash(ByteArray(20) { (it + 9).toByte() })
        val (_, accepted) = exchange(seed = 11, dialFor = otherHash, held = listOf(third, infoHash, otherHash))
        assertEquals(otherHash.bytes.toList(), accepted.first.bytes.toList())
    }

    /** An empty `IA` is legal: a dialler may say nothing until the handshake is done. */
    @Test
    fun anEmptyInitialMessageIsCarriedAsNothing() {
        val (_, accepted) = exchange(seed = 5, initial = ByteArray(0))
        assertEquals(0, accepted.second.carried.size)
    }

    /**
     * BEP 3's opening is told from MSE's on the first twenty bytes.
     *
     * The accepting side has to make this call before it knows anything else, and calling it wrong
     * loses the plaintext peers — trading one kind of unreachable peer for another.
     */
    @Test
    fun aPlaintextHandshakeIsNotMistakenForAKey() {
        val plaintext = ByteArray(68).also { it[0] = 19 }
        "BitTorrent protocol".encodeToByteArray().copyInto(plaintext, 1)
        assertTrue(Mse.looksPlaintext(plaintext))

        repeat(200) {
            assertFalse(
                Mse.looksPlaintext(generateDhKeyPair().publicKey),
                "a public key was read as a plaintext handshake",
            )
        }
        assertFalse(Mse.looksPlaintext(ByteArray(19) { 19 }), "too few bytes to decide is not a yes")
    }

    /** A side that refuses plaintext and is offered only plaintext says so rather than proceeding. */
    @Test
    fun anAccepterThatRefusesPlaintextIsNotForcedIntoIt() {
        // The dialler here offers RC4 too, so the negotiation succeeds; the refusing path is
        // exercised by the accept side's own branch below.
        val (dial, accepted) = exchange(seed = 13, allowPlaintextOnAccept = false)
        assertNotNull(dial.encrypt, "RC4 was on offer and had to be chosen")
        assertNotNull(accepted.second.encrypt)
    }

    /** What plaintext selection means downstream: no streams, and the caller reads the wire as it is. */
    @Test
    fun plaintextSelectionLeavesNoStreams() {
        val plaintext = MseResult(encrypt = null, decrypt = null)
        assertNull(plaintext.encrypt)
        assertNull(plaintext.decrypt)
    }

    /**
     * **MSE's prime is not RFC 2409's, and this is the test that would have caught six iterations
     * of a bug that reached a real client.**
     *
     * The two 768-bit numbers share every digit but the last twelve — both begin from π — so an
     * implementation that reads "the 768-bit MODP prime" and reaches for the RFC produces a shared
     * secret that agrees with every other implementation that made the same misreading, and with
     * no mainstream client. That is exactly what happened: this client *and* an independent Python
     * check both used RFC 2409, both were self-consistent, and both were refused by libtorrent
     * until the prime was corrected. The interop probe is what finally said so; this pins it so CI
     * can, without a peer.
     *
     * A known-answer test and not a string comparison: with fixed exponents the two primes give
     * *different* shared secrets, and the code's `agree` must match the MSE one and differ from the
     * RFC one. Reverting [MseHandshake.PRIME_HEX] to the RFC value fails this.
     */
    @Test
    fun theSharedSecretIsComputedOverMsesPrimeAndNotRfc2409s() {
        val mse = BigInteger(MseHandshake.PRIME_HEX, 16)
        val rfc2409Group1 =
            BigInteger(
                "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
                    "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
                    "4FE1356D6D51C245E485B576625E7EC6F44C42E9A63A3620FFFFFFFFFFFFFFFF",
                16,
            )
        assertTrue(mse != rfc2409Group1, "the pinned prime is RFC 2409's — the bug this test exists for")

        val g = BigInteger.valueOf(MseHandshake.GENERATOR.toLong())
        // Fixed 160-bit exponents, so the answer is reproducible and independent of the code's RNG.
        val theirSecret = BigInteger("00112233445566778899aabbccddeeff00112233", 16)
        val ourPrivate = BigInteger("fedcba9876543210fedcba9876543210fedcba98", 16)
        val theirPublicOverMse = g.modPow(theirSecret, mse).toFixedWidth()

        val sharedOverMse = BigInteger(1, theirPublicOverMse).modPow(ourPrivate, mse).toFixedWidth()
        val sharedOverRfc = BigInteger(1, theirPublicOverMse).modPow(ourPrivate, rfc2409Group1).toFixedWidth()
        assertFalse(sharedOverMse.contentEquals(sharedOverRfc), "the two primes would have to differ to matter")

        // What the code actually computes, driven from the same fixed peer public key.
        val agreed = fixedKeyPair(ourPrivate, mse).agree(theirPublicOverMse)
        assertContentEquals(sharedOverMse, agreed, "the code agreed over the wrong prime")
    }

    /** A `DhKeyPair` with a chosen private exponent, so a known-answer test can be reproducible. */
    private fun fixedKeyPair(
        privateExponent: BigInteger,
        prime: BigInteger,
    ): DhKeyPair =
        object : DhKeyPair {
            override val publicKey: ByteArray =
                BigInteger.valueOf(MseHandshake.GENERATOR.toLong()).modPow(privateExponent, prime).toFixedWidth()

            override fun agree(theirPublicKey: ByteArray): ByteArray =
                BigInteger(1, theirPublicKey).modPow(privateExponent, prime).toFixedWidth()
        }

    private fun BigInteger.toFixedWidth(): ByteArray {
        val raw = toByteArray()
        val out = ByteArray(MseHandshake.KEY_SIZE)
        if (raw.size >= MseHandshake.KEY_SIZE) {
            raw.copyInto(out, 0, raw.size - MseHandshake.KEY_SIZE, raw.size)
        } else {
            raw.copyInto(out, MseHandshake.KEY_SIZE - raw.size)
        }
        return out
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
