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
        val stand = scenario.stand.scaledDown()
        listOf(scenario.control, scenario.variant).forEach { variant ->
            val outcome = runOnce(stand, variant)
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
        val controlFromSeed = mutableListOf<Int>()
        val variantFromSeed = mutableListOf<Int>()
        repeat(repetitions) { round ->
            // Both, in the same round, before either is repeated.
            val first = runOnce(scenario.stand, scenario.control)
            val second = runOnce(scenario.stand, scenario.variant)
            if (round > 0) {
                control += first.millis
                variant += second.millis
                controlFromSeed += first.servedBlocks
                variantFromSeed += second.servedBlocks
            }
        }
        return Report(
            scenario,
            control,
            variant,
            controlFromSeed,
            variantFromSeed,
            everythingFromTheSeed = everythingFromTheSeed(scenario.stand),
        )
    }

    /**
     * What a swarm of clients gave each other, over how long they were in it.
     *
     * **Not a comparison, and it deliberately does not use [compare].** The question is not "which
     * of two variants is faster" but "does this client contribute anything, and after how long" —
     * a curve rather than a pair, taken at several sizes of the same stand, because the only way to
     * keep four clients in a swarm for longer is to give them more to download
     * ([B-127](../../../../../../../../docs/backlog/B-127-trading-barely-starts-before-a-download-ends.md)).
     *
     * Two runs at each size, both printed. A single run of anything is not a measurement, and with
     * two the reader can see for themselves whether the second says what the first did.
     */
    public fun contribution(
        scenario: Scenario,
        scales: List<Int>,
        runsEach: Int = 2,
    ): Contribution {
        require(scenario.stand.leechers > 1) {
            "a swarm of one client gives nothing to anybody by construction; this measures a swarm"
        }
        val points =
            scales.flatMap { scale ->
                val stand = scenario.stand.times(scale)
                (0 until runsEach).map {
                    val outcome = runOnce(stand, scenario.control)
                    ContributionPoint(
                        bytes = stand.size.toLong(),
                        millis = outcome.millis,
                        gave = outcome.gave,
                        took = outcome.took,
                        fromSeed = outcome.servedBlocks.toLong() * BLOCK,
                    )
                }
            }
        return Contribution(scenario, points)
    }

    /**
     * Every client's whole copy in blocks: what the seed serves when nobody trades.
     *
     * Zero for a stand with one client, where the number would mean nothing — one downloader takes
     * its copy from the swarm however the swarm is arranged.
     */
    private fun everythingFromTheSeed(stand: Stand): Int =
        if (stand.leechers < 2) {
            0
        } else {
            stand.leechers * stand.pieces * ((stand.pieceLength + BLOCK - 1) / BLOCK)
        }

    /**
     * One run of a stand — all of its clients at once — built for it and torn down after it.
     *
     * **The result is the makespan: when the *last* client finished.** With one client that is its
     * own download time, which is what a stand of seeds measures. With four it is the swarm's time,
     * and the difference matters because a picker that serves its own client at the swarm's expense
     * is precisely a picker whose own clock looks fine (B-126).
     */
    private fun runOnce(
        stand: Stand,
        variant: Variant,
    ): Outcome =
        runBlocking {
            val content = LocalSwarm.content(size = stand.size)
            val local =
                LocalSwarm.start(
                    content = content,
                    pieceLength = stand.pieceLength,
                    seeds = stand.seeds,
                    bytesPerSecond = stand.bytesPerSecond,
                )
            val dispatchers = EngineDispatchers()
            val job = SupervisorJob()
            // One scope a client, under one job: closing a set must not take its neighbours' peers
            // with it, and cancelling the job at the end must take all of them.
            val clients =
                (0 until stand.leechers).map {
                    val directory = Files.createTempDirectory("kachok-measure")
                    val scope = CoroutineScope(coroutineContext + dispatchers.io + SupervisorJob(job))
                    Client(directory, scope, TorrentSet(dispatchers, scope))
                }
            try {
                val runtimes =
                    clients.map { client ->
                        client.set.add(local.metainfo, variant.options(client.directory)).also { it.restore() }
                    }
                // The clock starts at the first dial and not at `start`, because everything before
                // it — building the sets, reading empty directories — is the same for both variants
                // and is not what anybody is asking about.
                val started = TimeSource.Monotonic.markNow()
                runtimes.forEachIndexed { index, runtime -> runtime.start(clients[index].scope) }
                val deadline = started + TIMEOUT
                while (runtimes.any { !it.state.value.isComplete }) {
                    check(!deadline.hasPassedNow()) {
                        "${variant.name} stalled at " +
                            runtimes.joinToString(", ") { "${it.state.value.completedPieces}/${stand.pieces}" } +
                            " pieces with " +
                            runtimes.joinToString(", ") { "${it.state.value.connectedPeers}" } + " peers"
                    }
                    delay(SAMPLE)
                }
                Outcome(
                    millis = started.elapsedNow().inWholeMilliseconds,
                    bytesMatch = runtimes.all { Files.readAllBytes(it.paths.single()).contentEquals(content) },
                    servedBlocks = local.served,
                    // Read once the last client is done and before anything is closed: what each
                    // of them gave the others, against what it took.
                    gave = runtimes.map { it.state.value.uploaded },
                    took = runtimes.map { it.state.value.downloaded },
                )
            } finally {
                clients.forEach { it.set.close() }
                job.cancelAndJoin()
                dispatchers.close()
                local.close()
                @OptIn(kotlin.io.path.ExperimentalPathApi::class)
                clients.forEach { it.directory.deleteRecursively() }
            }
        }

    private class Client(
        val directory: Path,
        val scope: CoroutineScope,
        val set: TorrentSet,
    )

    private class Outcome(
        val millis: Long,
        val bytesMatch: Boolean,
        val servedBlocks: Int,
        val gave: List<Long> = emptyList(),
        val took: List<Long> = emptyList(),
    )

    private val TIMEOUT = 5.minutes
    private val SAMPLE = 10.milliseconds

    /**
     * Four rounds, three kept. Fewer is a coin toss with a decimal point.
     */
    private const val MINIMUM_RUNS = 4

    private const val BLOCK = 16 * 1024
}
