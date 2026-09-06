package ru.workinprogress.kachok.ui.session

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.workinprogress.kachok.engine.io.EngineDispatchers
import ru.workinprogress.kachok.engine.runtime.RuntimeOptions
import ru.workinprogress.kachok.engine.runtime.TorrentSet
import ru.workinprogress.kachok.swarm.LocalSwarm
import ru.workinprogress.kachok.ui.Client
import ru.workinprogress.kachok.ui.list.TorrentState
import ru.workinprogress.kachok.ui.theme.KachokTheme
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The acceptance criterion of [B-52](../../../../../../../../docs/backlog/B-52-ui-on-the-real-engine.md):
 * the window's rows come from a real download, not from a fixture.
 *
 * The same swarm the headless client is tested against — an HTTP tracker on loopback naming a peer
 * that speaks BEP 3 over a socket — and the same `TorrentRuntime` the desktop `main` builds. What
 * is asserted is what a person would see: a row that starts, progresses and ends up seeding, and a
 * status bar that names the port the client actually bound.
 */
class AppDownloadTest {
    private val root: Path = Files.createTempDirectory("kachok-ui")
    private var swarm: LocalSwarm? = null

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() {
        swarm?.close()
        root.deleteRecursively()
    }

    @Test
    fun theListShowsARealDownloadProgressingAndThenSeeding(): Unit =
        runBlocking {
            // Slow enough that a sample lands mid-download; a seed at full speed finishes before
            // the first tick and proves only that the last frame is right.
            val local = LocalSwarm.start(delayPerBlockMillis = 20).also { swarm = it }
            val dispatchers = EngineDispatchers()
            val job = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + dispatchers.io + job)
            val set = TorrentSet(dispatchers, scope)
            val runtime = set.add(local.metainfo, RuntimeOptions(directory = root))
            val meter = RateMeter(minimumInterval = 1.milliseconds)
            val seen = mutableListOf<TorrentState>()
            var midway: String? = null
            try {
                runtime.restore()
                runtime.start(scope)
                withTimeout(30.seconds) {
                    while (true) {
                        val state = runtime.state.value
                        val row = rowOf(state, meter.sample(state))
                        seen += row.state
                        if (state.downloaded > 0 && !state.isComplete) midway = row.percent
                        if (state.isComplete) break
                        delay(SAMPLE)
                    }
                }
                val state = runtime.state.value
                val row = rowOf(state, meter.sample(state))

                assertEquals(TorrentState.Seeding, row.state, "a finished torrent seeds")
                assertEquals("100%", row.percent)
                assertEquals(Figures.bytes(local.content.size.toLong()), row.size)
                assertEquals(Figures.FOREVER, row.eta)
                assertTrue(local.served > 0, "the bytes came from the peer rather than from disk")
                assertTrue(
                    TorrentState.Downloading in seen,
                    "the list showed it downloading before it showed it seeding: $seen",
                )
                assertTrue(midway != null && midway != "100%", "a percentage between nothing and all: $midway")

                val window =
                    windowOf(
                        rows = listOf(row),
                        rates = Rates(),
                        listenPort = set.listenPort,
                        dhtNodes = null,
                        heapUsedBytes = 0,
                        heapMaxBytes = 128L * 1024 * 1024,
                    )
                assertEquals("1 torrent, 1 seeding, 0 paused", window.status.torrents)
                assertContains(window.status.port, "${set.listenPort}")
                // Null, not "DHT 0 nodes": the runtime opened no DHT socket at all, and the status
                // bar draws "not asked for" differently from "asked and nothing answered".
                assertEquals(null, window.status.dht)
                assertEquals(null, window.degradedSummary, "a healthy session has no banner")
            } finally {
                runtime.stop()
                set.close()
                job.cancelAndJoin()
                dispatchers.close()
            }
        }

    /**
     * The other end-to-end the design has a screen for: a tracker that refuses.
     *
     * Not a degraded *session* — the loops are all fine — so no banner, and the row stays in the
     * state it is in with nobody to talk to. Asserting that is what keeps a future "any error is
     * the error row" from quietly turning a tracker's 403 into a broken client.
     */
    @Test
    fun aTrackerThatRefusesIsNotADegradedSession(): Unit =
        runBlocking {
            val local = LocalSwarm.start(failure = "forbidden").also { swarm = it }
            val dispatchers = EngineDispatchers()
            val job = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + dispatchers.io + job)
            val set = TorrentSet(dispatchers, scope)
            val runtime = set.add(local.metainfo, RuntimeOptions(directory = root))
            try {
                runtime.restore()
                runtime.start(scope)
                withTimeout(30.seconds) {
                    while (runtime.state.value.trackerError == null) delay(SAMPLE)
                }
                val state = runtime.state.value
                assertContains(state.trackerError.orEmpty(), "forbidden")
                assertEquals(null, state.sessionError, "a tracker's refusal is not the session failing")
                val window =
                    windowOf(
                        rows = listOf(rowOf(state, Rates())),
                        rates = Rates(),
                        listenPort = set.listenPort,
                        dhtNodes = null,
                        heapUsedBytes = 0,
                        heapMaxBytes = 128L * 1024 * 1024,
                        sessionError = state.sessionError,
                    )
                assertEquals(null, window.degradedSummary)
                assertEquals(TorrentState.Downloading, window.torrents.single().state)
            } finally {
                runtime.stop()
                set.close()
                job.cancelAndJoin()
                dispatchers.close()
            }
        }

    /**
     * The same download, through the composable `main` builds.
     *
     * The two tests above prove the mapping and the runtime; this proves the window is wired to
     * them — that the loop in `Torrent` opens the torrent, starts the session and puts what comes
     * back on the screen. Without it, `App.kt` is the one file in this module that nothing runs.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theWindowItselfShowsTheDownload() =
        runComposeUiTest {
            val local = LocalSwarm.start(delayPerBlockMillis = 20).also { swarm = it }
            val file = root.resolve("fixture.torrent")
            Files.write(file, local.torrent)
            // Its own settings file, in the test's own directory. Without this the window reads
            // the machine's real one — which is how this test first failed: it downloaded into the
            // developer's `~/Downloads` and joined the DHT, because that is what their file said.
            setContent { KachokTheme { Client(file, root, settingsFile = root.resolve("settings.properties")) } }
            waitUntil(timeoutMillis = WAIT) {
                onAllNodesWithText("payload.bin").fetchSemanticsNodes().isNotEmpty()
            }
            // Twice: the list's row and the details panel's header, which is the panel being
            // wired to the same session rather than to a second one.
            assertEquals(2, onAllNodesWithText("payload.bin").fetchSemanticsNodes().size)
            waitUntil(timeoutMillis = WAIT) {
                onAllNodesWithText("100%").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Info hash").assertIsDisplayed()
            onNodeWithText("Connected peers").assertIsDisplayed()
            assertTrue(local.served > 0, "the bytes came off the wire, not off the disk")
        }

    private companion object {
        val SAMPLE = 50.milliseconds
        const val WAIT = 60_000L
    }
}
