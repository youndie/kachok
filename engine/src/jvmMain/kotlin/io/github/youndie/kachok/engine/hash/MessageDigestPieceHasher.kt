package io.github.youndie.kachok.engine.hash

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import io.github.youndie.kachok.engine.peer.Block
import io.github.youndie.kachok.engine.storage.PieceHasher
import java.nio.ByteBuffer
import java.security.MessageDigest

/** A block whose bytes this platform can read. Every JVM block is one; the interface names that. */
public interface JvmBlock : Block {
    /** The block's bytes, positioned at the start. Reading must not disturb the original. */
    public val bytes: ByteBuffer
}

/**
 * Whole-piece SHA-1 on a bounded dispatcher, with a small pool of reused digests.
 *
 * **Why a pool and not a `ThreadLocal`.** The brief said "one `MessageDigest` per thread, reused",
 * which is right on platform threads and useless here: every coroutine on the engine's dispatcher
 * runs on a *virtual* thread, virtual threads are created per task, and a thread-local on one is
 * therefore a fresh digest per piece — reuse that reuses nothing. The concurrency is bounded to
 * [parallelism] by `limitedParallelism`, so exactly that many digests are needed and a channel of
 * them is the whole mechanism.
 *
 * **Why bounded at all.** Hashing is the only CPU-bound work in the engine. Unbounded, it would
 * take every carrier and stall the sockets that feed it; at `availableProcessors()` it saturates
 * the machine and no more (research D1).
 */
public class MessageDigestPieceHasher(
    dispatcher: CoroutineDispatcher,
    public val parallelism: Int = Runtime.getRuntime().availableProcessors(),
    digestFactory: () -> MessageDigest = { MessageDigest.getInstance(ALGORITHM) },
) : PieceHasher {
    private val hashing = dispatcher.limitedParallelism(parallelism)

    /** Exactly [parallelism] digests, leased for the duration of one piece and handed back. */
    private val digests =
        Channel<MessageDigest>(parallelism).apply {
            repeat(parallelism) { trySend(digestFactory()) }
        }

    override suspend fun hash(blocks: List<Block>): ByteArray =
        withContext(hashing) {
            val digest = digests.receive()
            try {
                digest.reset()
                blocks.forEach { block ->
                    val jvmBlock =
                        block as? JvmBlock
                            ?: error("this hasher reads JVM blocks; got ${block::class.simpleName}")
                    // A duplicate so that hashing does not consume the block: the writer still
                    // has to write these very bytes.
                    digest.update(jvmBlock.bytes.duplicate())
                }
                digest.digest()
            } finally {
                digests.send(digest)
            }
        }

    public companion object {
        /**
         * SHA-1 through `MessageDigest` because the JDK's is intrinsified — `UseSHA1Intrinsics`
         * is on for this hardware, research §1.1 — and no Kotlin loop will approach it.
         */
        public const val ALGORITHM: String = "SHA-1"
    }
}
