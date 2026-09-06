package ru.workinprogress.kachok.ui.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.workinprogress.kachok.engine.io.EngineDispatchers
import ru.workinprogress.kachok.engine.runtime.RuntimeOptions
import ru.workinprogress.kachok.engine.runtime.TorrentRuntime
import ru.workinprogress.kachok.engine.runtime.TorrentSet
import ru.workinprogress.kachok.swarm.LocalSwarm
import ru.workinprogress.kachok.ui.deleteQuietly
import ru.workinprogress.kachok.ui.list.TorrentState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The five commands the toolbar gained, against a real swarm.
 *
 * Each of them is asserted in the engine's own tests with fakes, and each of *those* passed while
 * the running window was visibly broken at least once — the re-check's did, on a session that had
 * already failed. This is the other end: a real tracker, a real peer over a socket, real files on a
 * real disk, and the same `TorrentRuntime` the window builds.
 *
 * The seed is slowed to a block every few milliseconds throughout, because every one of these is
 * about what happens *during* a download and a local seed at full speed finishes before the first
 * sample.
 */
class CommandsEndToEndTest {
    private val root: Path = Files.createTempDirectory("kachok-commands")
    private var swarm: LocalSwarm? = null

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() {
        swarm?.close()
        root.deleteRecursively()
    }

    /** One torrent on a real swarm, opened the way the window opens one. */
    private suspend fun <T> downloading(
        content: ByteArray = LocalSwarm.content(),
        files: List<Pair<String, Int>>? = null,
        options: (Path) -> RuntimeOptions = { RuntimeOptions(directory = it) },
        blockMillis: Long = SLOW,
        body: suspend (LocalSwarm, TorrentSet, TorrentRuntime, CoroutineScope) -> T,
    ): T =
        runBlocking {
            val local = LocalSwarm.start(content = content, delayPerBlockMillis = blockMillis, files = files)
            swarm = local
            val dispatchers = EngineDispatchers()
            val job = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + dispatchers.io + job)
            val set = TorrentSet(dispatchers, scope)
            val runtime = set.add(local.metainfo, options(root))
            try {
                runtime.restore()
                runtime.start(scope)
                body(local, set, runtime, scope)
            } finally {
                set.close()
                job.cancelAndJoin()
                dispatchers.close()
            }
        }

    private suspend fun TorrentRuntime.waitUntil(
        what: String,
        timeout: kotlin.time.Duration = 30.seconds,
        condition: () -> Boolean,
    ) {
        withTimeout(timeout) {
            while (!condition()) delay(SAMPLE)
        }
    }

    /**
     * Pausing stops the bytes and resuming finishes the download.
     *
     * The interesting half is the middle: a paused torrent that goes on downloading is a pause in
     * name only, so this waits, samples twice, and asserts the counter did not move.
     */
    @Test
    fun aPausedTorrentStopsAndAResumedOneFinishes(): Unit =
        runBlocking {
            // Forty pieces at eight milliseconds a block: a third of a second of download, which is
            // long enough for a pause to land in the middle of it and short enough to be a test.
            downloading(content = LocalSwarm.content(size = 16 * 1024 * 40)) { _, _, runtime, _ ->
                runtime.waitUntil("something arrives") { runtime.state.value.downloaded > 0 }
                runtime.pause()
                runtime.waitUntil("the pause lands") { runtime.state.value.paused }

                assertEquals(0, runtime.state.value.connectedPeers, "a paused torrent kept its peers")
                assertEquals(
                    TorrentState.Paused,
                    rowOf(runtime.state.value, ratesOf(runtime.state.value)).state,
                    "the row does not say so",
                )
                // Settled first, and then measured. `pause` publishes the flag *before* it closes
                // the peers — deliberately, so the row flips the moment the button is pressed
                // rather than after an announce — so one block already in flight can still land and
                // be verified after `paused` is true. The pause is a promise about what happens
                // next, not about what is already on the wire.
                delay(STILL)
                val settled = runtime.state.value.downloaded
                delay(STILL)
                assertEquals(settled, runtime.state.value.downloaded, "a paused torrent went on downloading")
                assertTrue(!runtime.state.value.isComplete, "it finished before it could be paused; slow the seed")

                runtime.resume()
                runtime.waitUntil("it finishes") { runtime.state.value.isComplete }
                assertEquals(false, runtime.state.value.paused)
            }
        }

    /** A re-check reads the disk again and finds what went bad under it. */
    @Test
    fun aRecheckFindsAPieceThatWentBadOnTheDiskAndFetchesItAgain(): Unit =
        runBlocking {
            downloading(blockMillis = 0) { local, _, runtime, _ ->
                runtime.waitUntil("the download finishes") { runtime.state.value.isComplete }
                val file = runtime.paths.single()
                val before = runtime.state.value.completedPieces

                // Twenty-one bytes into the second piece, so the damage is not at an edge.
                runtime.pause()
                runtime.waitUntil("the writer stops") { runtime.state.value.paused }
                Files.newByteChannel(file, StandardOpenOption.WRITE).use { channel ->
                    channel.position(local.metainfo.pieceLength + 21L)
                    channel.write(java.nio.ByteBuffer.wrap("CORRUPT".encodeToByteArray()))
                }
                runtime.resume()

                runtime.recheck()
                runtime.waitUntil("the pass notices") { runtime.state.value.completedPieces < before }
                assertTrue(
                    runtime.state.value.completedPieces < before,
                    "the re-check believed a piece that is not on the disk any more",
                )

                runtime.waitUntil("it is fetched again") { runtime.state.value.isComplete }
                assertContentEquals(
                    local.content,
                    Files.readAllBytes(file),
                    "the file was reported complete and is not what the torrent says",
                )
            }
        }

    /** Removing takes the torrent out of the set and leaves the bytes where they are. */
    @Test
    fun removingATorrentLeavesTheDataUnlessAskedOtherwise(): Unit =
        runBlocking {
            downloading(blockMillis = 0) { _, set, runtime, _ ->
                runtime.waitUntil("the download finishes") { runtime.state.value.isComplete }
                val file = runtime.paths.single()

                set.remove(runtime)

                assertEquals(emptyList(), set.torrents, "the set kept a torrent it was told to forget")
                assertTrue(file.exists(), "removing a torrent deleted data nobody asked to delete")
            }
        }

    @Test
    fun removingWithTheBoxTickedTakesTheFilesToo(): Unit =
        runBlocking {
            downloading(blockMillis = 0) { _, set, runtime, _ ->
                runtime.waitUntil("the download finishes") { runtime.state.value.isComplete }
                // Read before the remove: `remove` closes the files, and a closed `FileSet` is not
                // somewhere to ask what it was writing.
                val paths = runtime.paths

                set.remove(runtime)
                deleteQuietly(paths)

                paths.forEach { assertTrue(!it.exists(), "$it survived a remove that was told to delete it") }
            }
        }

    /**
     * A file nobody wants is never written to, and the one beside it is whole.
     *
     * Two files of two pieces each, so the skipped one has pieces entirely its own and there is no
     * straddle to argue about. What is asserted is the disk: the wanted file's bytes are the
     * torrent's, and the skipped file is still the zeros `FileSet` preallocated.
     */
    @Test
    fun anUnwantedFileIsNeverWrittenTo(): Unit =
        runBlocking {
            val piece = 16 * 1024
            val content = LocalSwarm.content(size = piece * 4)
            downloading(
                content = content,
                files = listOf("wanted.bin" to piece * 2, "skipped.bin" to piece * 2),
                options = { RuntimeOptions(directory = it, unwantedFiles = setOf(1)) },
                blockMillis = 0,
            ) { _, _, runtime, _ ->
                runtime.waitUntil("the wanted half arrives") { runtime.state.value.isComplete }

                val (wanted, skipped) = runtime.paths
                assertEquals("wanted.bin", wanted.fileName.toString())
                assertContentEquals(
                    content.copyOfRange(0, piece * 2),
                    Files.readAllBytes(wanted),
                    "the wanted file is not what the torrent says",
                )
                assertTrue(
                    Files.readAllBytes(skipped).all { it == 0.toByte() },
                    "a file nobody asked for was written to",
                )
                assertEquals(
                    (piece * 2).toLong(),
                    runtime.state.value.downloaded,
                    "the counter includes bytes this client never asked for",
                )
                assertEquals(0, runtime.state.value.left, "a complete torrent still has something left")
            }
        }

    /**
     * Sequential order, seen from the disk.
     *
     * The picker's own tests assert which piece it hands out; this asserts what that does to the
     * file. Mid-download the file is a correct prefix followed by the zeros nobody has filled in —
     * which is the whole point of the setting and the only part of it a person can see.
     */
    @Test
    fun sequentialFillsTheFileFromTheFront(): Unit =
        runBlocking {
            val piece = 16 * 1024
            val content = LocalSwarm.content(size = piece * 8)
            downloading(
                content = content,
                options = { RuntimeOptions(directory = it, sequential = true) },
            ) { _, _, runtime, _ ->
                runtime.waitUntil("a few pieces land") {
                    val done = runtime.state.value.completedPieces
                    done in 2..5 || runtime.state.value.isComplete
                }
                val done = runtime.state.value.completedPieces
                assertTrue(!runtime.state.value.isComplete, "it finished before a sample landed; slow the seed")

                val onDisk = Files.readAllBytes(runtime.paths.single())
                val verified = done * piece
                assertContentEquals(
                    content.copyOfRange(0, verified),
                    onDisk.copyOfRange(0, verified),
                    "the first $done pieces are not the torrent's first $done pieces",
                )
                assertTrue(
                    onDisk.copyOfRange(verified, onDisk.size).any { it != 0.toByte() }.not(),
                    "something was fetched out of order behind the prefix",
                )
            }
        }

    private companion object {
        /** Slow enough that a command lands mid-download rather than after it. */
        const val SLOW = 8L
        val SAMPLE = 20.milliseconds

        /** Long enough that a torrent that had not really stopped would have moved. */
        val STILL = 400.milliseconds
    }
}
