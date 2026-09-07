package io.github.youndie.kachok.ui.session

import io.github.youndie.kachok.engine.io.EngineDispatchers
import io.github.youndie.kachok.engine.runtime.RuntimeOptions
import io.github.youndie.kachok.engine.runtime.TorrentRuntime
import io.github.youndie.kachok.engine.runtime.TorrentSet
import io.github.youndie.kachok.engine.session.SessionConfig
import io.github.youndie.kachok.swarm.LocalSwarm
import io.github.youndie.kachok.ui.deleteQuietly
import io.github.youndie.kachok.ui.list.TorrentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
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

    /**
     * Waits, and says what the session looked like when it gave up.
     *
     * A `TimeoutCancellationException` on its own names the line and nothing else, which is what a
     * failure on another machine looks like from here: the Windows run said only "timed out" and
     * the interesting part — whether it was still paused, whether it had a peer, what the last dial
     * said — was not in the report.
     */
    private suspend fun TorrentRuntime.waitUntil(
        what: String,
        timeout: kotlin.time.Duration = 30.seconds,
        condition: () -> Boolean,
    ) {
        val deadline =
            kotlin.time.TimeSource.Monotonic
                .markNow() + timeout
        while (!condition()) {
            if (deadline.hasPassedNow()) {
                val now = state.value
                error(
                    "timed out waiting for $what — paused=${now.paused} peers=${now.connectedPeers} " +
                        "unchoked=${now.unchokedPeers} " +
                        "known=${now.knownPeers} pieces=${now.completedPieces}/${now.pieceCount} " +
                        "outstanding=${now.outstandingRequests} lastPeer=${now.lastPeerError} " +
                        "tracker=${now.trackerError} session=${now.sessionError} " +
                        now.peers.joinToString(prefix = "peers[", postfix = "]") { peer ->
                            "${peer.address} choking=${peer.choking} interested=${peer.interested} " +
                                "has=${peer.pieces} outstanding=${peer.outstanding}"
                        },
                )
            }
            delay(SAMPLE)
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

    /**
     * A re-check reads the disk again and finds what went bad under it.
     *
     * **POSIX only, because the damage is.** The test corrupts the file through a second handle
     * while the client holds its own — and on Windows that write does not survive: the client's
     * next `force()` writes back its own cached view of the page and the file is whole again. Read
     * back immediately the corruption is there; after the flush it is gone, which the failure
     * message on Windows said in as many words.
     *
     * That is the *test's* premise being unportable, not the client's behaviour: a file that goes
     * bad in the real world does so while nothing has it open, or through the handle that has it.
     * The re-check itself is covered on every platform by
     * `SessionTest#aRecheckFindsAPieceThatWentBadOnTheDisk`, which corrupts by lying to the hasher
     * and needs no second handle. What only this test can reach — the reconnect around a re-check —
     * found two real bugs in the engine, and both are fixed everywhere.
     */
    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun aRecheckFindsAPieceThatWentBadOnTheDiskAndFetchesItAgain(): Unit =
        runBlocking {
            downloading(blockMillis = 0) { local, _, runtime, _ ->
                runtime.waitUntil("the download finishes") { runtime.state.value.isComplete }
                val file = runtime.paths.single()

                // Twenty-one bytes into the second piece, so the damage is not at an edge.
                //
                // Paused around the write, and the pause is load-bearing: it forces a reconnect
                // between the corruption and the re-check, which is what found the defect. A peer
                // that connects while this client is complete computes no interest, and a re-check
                // that did not re-derive it left the torrent one piece short with a connected,
                // unchoking peer and nothing outstanding. Without the pause the test passes on a
                // session that would still wedge for a person who pressed the buttons in this
                // order.
                runtime.pause()
                runtime.waitUntil("the pause lands") { runtime.state.value.paused }
                val at = local.metainfo.pieceLength + 21L
                val damage = "CORRUPT".encodeToByteArray()
                Files.newByteChannel(file, StandardOpenOption.WRITE).use { channel ->
                    channel.position(at)
                    channel.write(java.nio.ByteBuffer.wrap(damage))
                }
                // Read back before asking for anything. The engine holds its own handle on this
                // file, and "the re-check found nothing wrong" has two causes — a pass that does
                // not look, and a write that never landed. A timeout cannot tell them apart, and on
                // Windows it was the second.
                assertContentEquals(
                    damage,
                    Files.readAllBytes(file).copyOfRange(at.toInt(), at.toInt() + damage.size),
                    "the corruption never reached the disk, so the re-check has nothing to find",
                )
                // **The re-check goes in immediately behind the resume, and that ordering is the
                // test.** Waiting for the resume to land first — for anything at all, even for a
                // read of the file — lets the dial finish before the pass begins, and a peer that
                // connects *after* a pass computes its interest from the handshake like any other.
                // Measured by deleting the fix in `Session.recheck` and re-running: with a wait in
                // here the broken engine passes.
                runtime.resume()

                // **What is asserted is what survives, not what the client passes through.**
                //
                // The obvious assertion is that `completedPieces` dips below what it was. It does —
                // for as long as it takes a peer with `blockMillis = 0` to send one piece back —
                // and a `StateFlow` conflates, so whether a poll sees that dip is a race between
                // two machines' timings and says nothing about the client. Green on the build
                // machine and red on this mac, from identical behaviour on both.
                //
                // `downloaded` is not the substitute it looks like: after a re-check the session
                // republishes it from the resume snapshot, so a piece fetched a second time does
                // not move it, and `hashFailures` counts what arrives over the wire rather than
                // what a pass disbelieves. What is unambiguous is the file. It was wrong a line
                // ago; if it is right again, the pass read the disk, threw the piece away and
                // pulled it back off the wire, because nothing else in this client can put those
                // bytes there.
                runtime.recheck()
                try {
                    runtime.waitUntil("the pass notices and the piece comes back") {
                        Files.readAllBytes(file).contentEquals(local.content)
                    }
                } catch (stuck: IllegalStateException) {
                    // Two very different faults look the same from a timeout: a pass that did not
                    // read the disk, and a disk that no longer holds what was written to it. The
                    // bytes at the offset say which.
                    val now = Files.readAllBytes(file).copyOfRange(at.toInt(), at.toInt() + damage.size)
                    error("${stuck.message} — at $at the file holds ${now.toList()}")
                }

                runtime.waitUntil("it counts as complete again") { runtime.state.value.isComplete }
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
                // The frontier, not the prefix. The picker works on several pieces at once — that
                // is what `maxStartedPieces` is — so blocks of the next few land while the piece
                // before them is still being hashed, and bytes past the verified mark are correct
                // rather than out of order. What sequential promises is that nothing is fetched
                // *far* ahead, and this is where "far" is. Asserting a clean prefix passed on
                // macOS and failed on Windows, which schedules the hashing differently.
                val frontier = (done + SessionConfig().maxStartedPieces) * piece
                if (frontier < onDisk.size) {
                    assertTrue(
                        onDisk.copyOfRange(frontier, onDisk.size).all { it == 0.toByte() },
                        "a piece was fetched more than ${SessionConfig().maxStartedPieces} ahead of the front",
                    )
                }
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
