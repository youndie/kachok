package io.github.youndie.kachok.ui.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import io.github.youndie.kachok.engine.io.EngineDispatchers
import io.github.youndie.kachok.engine.runtime.RuntimeOptions
import io.github.youndie.kachok.engine.runtime.TorrentSet
import io.github.youndie.kachok.swarm.LocalSwarm
import io.github.youndie.kachok.ui.list.TorrentState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The acceptance criterion of [B-54](../../../../../../../../docs/backlog/B-54-many-torrents.md):
 * two torrents download at once in one process, and the numbers the window shows are the set's.
 *
 * Two swarms, one `TorrentSet`. What is being proved is the sharing: one port both trackers are
 * told about, one dispatcher, and an incoming peer routed to the session whose info hash it named
 * — with the pools still one each, because the cap is the back-pressure and it is per session.
 */
class ManyTorrentsTest {
    private val root: Path = Files.createTempDirectory("kachok-many")
    private val swarms = mutableListOf<LocalSwarm>()

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() {
        swarms.forEach { it.close() }
        root.deleteRecursively()
    }

    /**
     * Four mebibytes in 256 KiB pieces, which is sixteen blocks a piece.
     *
     * Big enough that the buffer pool is actually asked for something: with the default one block
     * a piece it hands out eight buffers however large the torrent is, and a measurement of that
     * is a measurement of the fixture.
     */
    private fun swarm(seed: Int): LocalSwarm =
        LocalSwarm
            .start(
                content = ByteArray(SIZE) { (it * seed and BYTE).toByte() },
                delayPerBlockMillis = 1,
                pieceLength = PIECE,
            ).also { swarms += it }

    @Test
    fun twoTorrentsDownloadAtOnceAndTheStatusBarAddsThemUp(): Unit =
        runBlocking {
            val first = swarm(seed = 7)
            val second = swarm(seed = 11)
            val dispatchers = EngineDispatchers()
            val job = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + dispatchers.io + job)
            val set = TorrentSet(dispatchers, scope)
            try {
                val runtimes =
                    listOf(first, second).mapIndexed { at, local ->
                        val directory = root.resolve("t$at").also { Files.createDirectories(it) }
                        set.add(local.metainfo, RuntimeOptions(directory = directory)).also {
                            it.restore()
                            it.start(scope)
                        }
                    }
                assertEquals(2, set.torrents.size)
                // One port for the process, and both trackers were told the same one.
                assertTrue(set.listenPort > 0)
                assertEquals(runtimes.map { set.listenPort }, runtimes.map { it.listenPort })
                // And a pool each, sized from that torrent's own pieces.
                assertEquals(2, runtimes.map { it.pool }.toSet().size, "the pools are not shared")

                withTimeout(60.seconds) {
                    while (set.torrents.any { !it.state.value.isComplete }) delay(SAMPLE)
                }

                val samples =
                    set.torrents.map { runtime ->
                        val state = runtime.state.value
                        state to ratesOf(state)
                    }
                val rows = samples.map { (state, rates) -> rowOf(state, rates) }
                assertTrue(rows.all { it.state == TorrentState.Seeding }, "both finished: ${rows.map { it.state }}")

                val window =
                    windowOf(
                        rows = rows,
                        rates = Rates(samples.sumOf { it.second.down }, samples.sumOf { it.second.up }),
                        listenPort = set.listenPort,
                        dhtNodes = null,
                        heapUsedBytes = 0,
                        heapMaxBytes = 128L * 1024 * 1024,
                    )
                assertEquals("2 torrents, 2 seeding, 0 paused", window.status.torrents)
                assertTrue(first.served > 0 && second.served > 0, "both came off the wire")

                // The measurement B-54 asks for, reported rather than asserted against a bound:
                // what a second torrent costs is the sum of two peaks, not one shared cap.
                val peaks = set.torrents.map { it.pool.peakOutstanding to it.pool.capacity }
                println("buffer pools with two torrents: ${peaks.joinToString { "${it.first} of ${it.second}" }}")
                assertTrue(peaks.all { it.first > 0 }, "a pool that handed out nothing served nothing")
                assertTrue(peaks.all { it.first <= it.second }, "a pool cannot exceed its own cap")
            } finally {
                set.torrents.forEach { it.stop() }
                set.close()
                job.cancelAndJoin()
                dispatchers.close()
            }
        }

    /**
     * Two sessions on one info hash would announce twice, dial the same peers twice and write the
     * same pieces into two directories. Refused where it is cheap to refuse.
     */
    @Test
    fun theSameTorrentTwiceIsRefused(): Unit =
        runBlocking {
            val local = swarm(seed = 3)
            val dispatchers = EngineDispatchers()
            val job = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + dispatchers.io + job)
            val set = TorrentSet(dispatchers, scope)
            try {
                set.add(local.metainfo, RuntimeOptions(directory = root))
                val thrown =
                    assertFailsWith<IllegalArgumentException> {
                        set.add(local.metainfo, RuntimeOptions(directory = root))
                    }
                assertTrue(thrown.message.orEmpty().contains(local.metainfo.name))
                assertEquals(1, set.torrents.size)
            } finally {
                set.close()
                job.cancelAndJoin()
                dispatchers.close()
            }
        }

    private companion object {
        val SAMPLE = 50.milliseconds
        const val SIZE = 4 * 1024 * 1024
        const val PIECE = 256 * 1024
        const val BYTE = 0xFF
    }
}
