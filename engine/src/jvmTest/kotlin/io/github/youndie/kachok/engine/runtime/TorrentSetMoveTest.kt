package io.github.youndie.kachok.engine.runtime

import io.github.youndie.kachok.engine.bencode.BDictionary
import io.github.youndie.kachok.engine.bencode.BInteger
import io.github.youndie.kachok.engine.bencode.BList
import io.github.youndie.kachok.engine.bencode.BString
import io.github.youndie.kachok.engine.bencode.Bencode
import io.github.youndie.kachok.engine.io.EngineDispatchers
import io.github.youndie.kachok.engine.metainfo.Metainfo
import io.github.youndie.kachok.engine.metainfo.MetainfoParser
import io.github.youndie.kachok.engine.session.FilePriority
import io.github.youndie.kachok.engine.wire.PeerWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A torrent's data moved to another directory, keeping its progress
 * ([B-134](../../../../../../../../docs/backlog/B-134-move-a-torrent-s-data.md)).
 *
 * The torrent is complete on the disk, checked once, and then moved: what is asserted is that it
 * arrives whole, that the old folder is gone, and that the start-up check at the new place trusted
 * the resume record that travelled with it rather than hashing everything again.
 */
class TorrentSetMoveTest {
    private val root: Path = Files.createTempDirectory("kachok-move")

    /** Two files, the boundary inside the second piece, so the folder and the layout both matter. */
    private val one = ByteArray(20_000) { (it * 7 and 0xFF).toByte() }
    private val two = ByteArray(30_000) { (it * 11 and 0xFF).toByte() }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun metainfo(): Metainfo {
        val content = one + two
        val digest = MessageDigest.getInstance("SHA-1")
        val pieces = (content.size + PeerWire.BLOCK_SIZE - 1) / PeerWire.BLOCK_SIZE
        val hashes = ByteArray(pieces * Metainfo.HASH_SIZE)
        (0 until pieces).forEach { index ->
            val from = index * PeerWire.BLOCK_SIZE
            val to = minOf(from + PeerWire.BLOCK_SIZE, content.size)
            digest.reset()
            digest.update(content, from, to - from)
            digest.digest().copyInto(hashes, index * Metainfo.HASH_SIZE)
        }

        fun file(
            name: String,
            length: Int,
        ) = BDictionary(
            mapOf(
                BString("length") to BInteger(length.toLong()),
                BString("path") to BList(listOf(BString(name))),
            ),
        )
        val info =
            BDictionary(
                mapOf(
                    BString("files") to BList(listOf(file("one.bin", one.size), file("two.bin", two.size))),
                    BString("name") to BString("season"),
                    BString("piece length") to BInteger(PeerWire.BLOCK_SIZE.toLong()),
                    BString("pieces") to BString(hashes),
                ),
            )
        return MetainfoParser.parse(
            Bencode.encode(
                BDictionary(
                    mapOf(
                        BString("announce") to BString("http://127.0.0.1:1/annc"),
                        BString("info") to info,
                    ),
                ),
            ),
        )
    }

    private fun <T> withSet(body: suspend (TorrentSet, CoroutineScope) -> T): T =
        runBlocking {
            val dispatchers = EngineDispatchers()
            val job = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + dispatchers.io + job)
            val set = TorrentSet(dispatchers, scope)
            try {
                body(set, scope)
            } finally {
                set.close()
                job.cancelAndJoin()
                dispatchers.close()
            }
        }

    /** A complete torrent, checked and running paused at `from`. */
    private suspend fun completeAt(
        set: TorrentSet,
        scope: CoroutineScope,
        from: Path,
    ): TorrentRuntime {
        Files.createDirectories(from.resolve("season"))
        Files.write(from.resolve("season/one.bin"), one)
        Files.write(from.resolve("season/two.bin"), two)
        val runtime = set.add(metainfo(), RuntimeOptions(directory = from, port = 0))
        runtime.restore()
        assertTrue(runtime.state.value.isComplete, "the fixture did not check as complete")
        runtime.start(scope, paused = true)
        withTimeout(10_000) { runtime.state.first { it.paused } }
        return runtime
    }

    @Test
    fun theFilesAndTheRecordMoveAndTheProgressComesWithThem(): Unit =
        withSet { set, scope ->
            val from = root.resolve("from")
            val to = root.resolve("to")
            val runtime = completeAt(set, scope, from)
            runtime.prioritise(0, FilePriority.HIGH)
            runtime.sequential(true)
            withTimeout(10_000) { runtime.state.first { it.sequential && it.files[0].priority == FilePriority.HIGH } }

            val moved = set.move(runtime, to)

            assertNull(moved.failure, moved.failure)
            assertTrue(moved.paused, "the move forgot the torrent was paused")
            assertEquals(
                to,
                moved.runtime.directory
                    .toAbsolutePath()
                    .normalize(),
            )
            assertSame(moved.runtime, set.torrents.single(), "the set does not hold the moved torrent")
            assertContentEquals(one, Files.readAllBytes(to.resolve("season/one.bin")))
            assertContentEquals(two, Files.readAllBytes(to.resolve("season/two.bin")))
            assertTrue(
                Files.exists(to.resolve(TorrentRuntime.resumeName(runtime.metainfo))),
                "the resume record stayed behind",
            )
            assertFalse(Files.exists(from.resolve("season")), "the emptied torrent folder was left behind")

            // A byte the record vouches for is changed on the disk. A check that trusted the record
            // does not read it and calls the torrent complete; one that hashed everything again
            // would find the piece bad. This is what "no recheck from zero" looks like from outside.
            Files.write(to.resolve("season/one.bin"), one.copyOf().also { it[0] = (it[0] + 1).toByte() })
            moved.runtime.restore()
            assertTrue(moved.runtime.state.value.isComplete, "the record did not travel, or was not trusted")
            assertEquals(setOf(0), moved.runtime.options.highFiles, "the tier was lost")
            assertTrue(moved.runtime.state.value.sequential, "the order was lost")
        }

    @Test
    fun aTargetThatAlreadyHoldsTheFilesIsRefusedAndNothingIsTouched(): Unit =
        withSet { set, scope ->
            val from = root.resolve("from")
            val to = root.resolve("to")
            val runtime = completeAt(set, scope, from)
            Files.createDirectories(to.resolve("season"))
            Files.write(to.resolve("season/two.bin"), byteArrayOf(1, 2, 3))

            val refused = assertFailsWith<IllegalArgumentException> { set.move(runtime, to) }

            assertContains(refused.message.orEmpty(), "already exists")
            assertContains(refused.message.orEmpty(), "nothing was moved")
            assertSame(runtime, set.torrents.single(), "a refused move replaced the torrent")
            assertTrue(runtime.state.value.paused, "a refused move stopped the torrent")
            assertContentEquals(one, Files.readAllBytes(from.resolve("season/one.bin")))
            assertFalse(Files.exists(to.resolve("season/one.bin")), "a refused move moved a file")
        }

    @Test
    fun aMoveToWhereItAlreadyIsIsRefused(): Unit =
        withSet { set, scope ->
            val from = root.resolve("from")
            val runtime = completeAt(set, scope, from)
            assertContains(
                assertFailsWith<IllegalArgumentException> { set.move(runtime, from) }.message.orEmpty(),
                "already saved in",
            )
        }
}
