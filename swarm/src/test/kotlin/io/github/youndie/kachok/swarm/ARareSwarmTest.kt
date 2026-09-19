package io.github.youndie.kachok.swarm

import io.github.youndie.kachok.engine.io.EngineDispatchers
import io.github.youndie.kachok.engine.runtime.RuntimeOptions
import io.github.youndie.kachok.engine.runtime.TorrentSet
import io.github.youndie.kachok.engine.wire.PeerWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A swarm in which one piece is held by one peer of five, driven by the real client.
 *
 * **This is the thing the stand could not do**, and its absence is written into
 * [B-65](../../../../../../../docs/backlog/B-65-sequential-download.md) as the reason a swarm cost
 * was never measured: with every seed holding everything, rarest-first and in-order ask for the
 * same pieces in a different order and any figure taken from it measures nothing. What is asserted
 * here is only that the stand poses the question — the piece nobody else has arrives, from the peer
 * that has it, and no client asked anybody for anything they had not announced
 * ([B-123](../../../../../../../docs/backlog/B-123-a-seed-that-holds-part-of-the-torrent.md)).
 * The comparisons it enables are B-125's, and they are not a gate.
 */
class ARareSwarmTest {
    private val root: Path = Files.createTempDirectory("kachok-rare")
    private var swarm: LocalSwarm? = null

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() {
        swarm?.close()
        root.deleteRecursively()
    }

    @Test
    fun aPieceOnlyOnePeerHasArrivesFromThatPeer(): Unit =
        runBlocking {
            val content = LocalSwarm.content(size = PeerWire.BLOCK_SIZE * PIECES)
            val everythingElse = (0 until PIECES).toSet() - RARE
            val local =
                LocalSwarm.start(
                    content = content,
                    pieceLength = PeerWire.BLOCK_SIZE,
                    // Five peers; the first holds one piece and nothing else, the rest hold
                    // everything but that piece. The download cannot finish without the first.
                    seeds = listOf(setOf(RARE)) + List(COMMON_SEEDS) { everythingElse },
                )
            swarm = local
            assertEquals(1 + COMMON_SEEDS, local.peers.size, "the tracker did not name every seed")

            val dispatchers = EngineDispatchers()
            val job = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + dispatchers.io + job)
            val set = TorrentSet(dispatchers, scope)
            val runtime = set.add(local.metainfo, RuntimeOptions(directory = root))
            try {
                runtime.restore()
                runtime.start(scope)

                val deadline = TimeSource.Monotonic.markNow() + TIMEOUT
                while (!runtime.state.value.isComplete) {
                    check(!deadline.hasPassedNow()) {
                        "the download stalled at ${runtime.state.value.completedPieces} of $PIECES " +
                            "with ${runtime.state.value.connectedPeers} peers"
                    }
                    delay(SAMPLE)
                }

                assertContentEquals(
                    content,
                    Files.readAllBytes(runtime.paths.single()),
                    "the file is not the torrent's content",
                )
                val rareSeed = local.peers.first()
                assertTrue(
                    rareSeed.served.any { it.piece.value == RARE },
                    "the piece only one peer had was never asked of it, so it cannot be on the disk",
                )
                assertTrue(
                    local.peers.drop(1).none { seed -> seed.served.any { it.piece.value == RARE } },
                    "a seed that does not hold the rare piece served it, so the subsets mean nothing",
                )
                assertTrue(
                    local.peers.all { it.refused.isEmpty() },
                    "the client asked a peer for a piece it had announced it has not got",
                )
            } finally {
                set.close()
                job.cancelAndJoin()
                dispatchers.close()
            }
        }

    private companion object {
        const val PIECES = 8
        const val RARE = 6
        const val COMMON_SEEDS = 4
        val TIMEOUT = 30.seconds
        val SAMPLE = 20.milliseconds
    }
}
