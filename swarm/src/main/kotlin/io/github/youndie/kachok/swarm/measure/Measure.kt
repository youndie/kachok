package io.github.youndie.kachok.swarm.measure

import io.github.youndie.kachok.engine.io.EngineDispatchers
import io.github.youndie.kachok.engine.runtime.TorrentSet
import io.github.youndie.kachok.swarm.LocalSwarm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * Runs a [Scenario], either to assert that it works or to compare its two variants.
 *
 * **The two entry points return different things, and that is the design.** [verify] cannot report a
 * timing because it does not return one: it belongs in `build`, where a number would be measuring a
 * shared runner and a threshold in seconds would be a gate that goes red on a noisy Tuesday and
 * gets raised until it asserts nothing. [compare] returns timings and is never a gate
 * ([B-125](../../../../../../../../docs/backlog/B-125-a-measurement-that-is-a-pair.md)).
 */
public object Measure {
    /**
     * Runs both variants once and checks the download is real: every byte, from the swarm.
     *
     * No duration crosses this boundary. A scenario that stops working — a stand whose seeds
     * between them cannot serve the torrent, a variant whose options no longer parse — is a broken
     * measurement long before anybody runs one, and this is what notices.
     */
    public fun verify(scenario: Scenario) {
        listOf(scenario.control, scenario.variant).forEach { variant ->
            val outcome = runOnce(scenario.stand, variant)
            check(outcome.bytesMatch) { "${scenario.name}/${variant.name}: the file is not the torrent's content" }
            check(outcome.servedBlocks > 0) {
                "${scenario.name}/${variant.name}: nothing came off the wire, so this measures the disk"
            }
        }
    }

    /**
     * Runs the pair [repetitions] times, **interleaved**, and reports the ratio between them.
     *
     * Interleaved and not one after the other: a machine that gets slower during the run — a
     * background build, a thermal limit, another agent — then slows both variants, and the ratio
     * survives it. Every comparison this repository has been wrong about was taken across two
     * sessions.
     *
     * The first run of each variant is discarded: the first download after a JVM starts measures
     * class loading and a cold page cache, which is warm-up wearing the costume of a result.
     */
    public fun compare(
        scenario: Scenario,
        repetitions: Int,
    ): Report {
        require(repetitions >= MINIMUM_RUNS) {
            "a comparison of $repetitions runs is not a comparison: the same variant has given 1.14 and 2.42 " +
                "back to back, so at least $MINIMUM_RUNS are taken and the first of each is thrown away"
        }
        val control = mutableListOf<Long>()
        val variant = mutableListOf<Long>()
        repeat(repetitions) { round ->
            // Both, in the same round, before either is repeated.
            val first = runOnce(scenario.stand, scenario.control)
            val second = runOnce(scenario.stand, scenario.variant)
            if (round > 0) {
                control += first.millis
                variant += second.millis
            }
        }
        return Report(scenario, control, variant)
    }

    /** One download, from a stand built for it and torn down after it. */
    private fun runOnce(
        stand: Stand,
        variant: Variant,
    ): Outcome =
        runBlocking {
            val content = LocalSwarm.content(size = stand.size)
            val directory = Files.createTempDirectory("kachok-measure")
            val local =
                LocalSwarm.start(
                    content = content,
                    pieceLength = stand.pieceLength,
                    seeds = stand.seeds,
                    bytesPerSecond = stand.bytesPerSecond,
                )
            val dispatchers = EngineDispatchers()
            val job = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + dispatchers.io + job)
            val set = TorrentSet(dispatchers, scope)
            try {
                val runtime = set.add(local.metainfo, variant.options(directory))
                runtime.restore()
                // The clock starts at the first dial and not at `start`, because everything before
                // it — building the set, reading an empty directory — is the same for both variants
                // and is not what anybody is asking about.
                val started = TimeSource.Monotonic.markNow()
                runtime.start(scope)
                val deadline = started + TIMEOUT
                while (!runtime.state.value.isComplete) {
                    check(!deadline.hasPassedNow()) {
                        "${variant.name} stalled at ${runtime.state.value.completedPieces} of " +
                            "${stand.pieces} pieces with ${runtime.state.value.connectedPeers} peers"
                    }
                    delay(SAMPLE)
                }
                val took = started.elapsedNow().inWholeMilliseconds
                Outcome(
                    millis = took,
                    bytesMatch = Files.readAllBytes(runtime.paths.single()).contentEquals(content),
                    servedBlocks = local.served,
                )
            } finally {
                set.close()
                job.cancelAndJoin()
                dispatchers.close()
                local.close()
                @OptIn(kotlin.io.path.ExperimentalPathApi::class)
                directory.deleteRecursively()
            }
        }

    private class Outcome(
        val millis: Long,
        val bytesMatch: Boolean,
        val servedBlocks: Int,
    )

    private val TIMEOUT = 5.minutes
    private val SAMPLE = 10.milliseconds

    /**
     * Four rounds, three kept. Fewer is a coin toss with a decimal point.
     */
    private const val MINIMUM_RUNS = 4
}
