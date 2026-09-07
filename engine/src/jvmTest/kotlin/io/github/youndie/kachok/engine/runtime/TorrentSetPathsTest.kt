package io.github.youndie.kachok.engine.runtime

import io.github.youndie.kachok.engine.io.EngineDispatchers
import io.github.youndie.kachok.engine.metainfo.Metainfo
import io.github.youndie.kachok.engine.metainfo.MetainfoParser
import io.github.youndie.kachok.engine.storage.FileSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two different torrents writing to one file, which nothing noticed for a release.
 *
 * Found by driving the window: two fixtures called `payload.bin`, added to one folder. Both opened
 * a `FileChannel` on the same path and both wrote `payload.bin.resume`. The run did no damage only
 * because the two happened to share a byte pattern — a different pair would have interleaved two
 * downloads into one file and neither would have hashed.
 */
class TorrentSetPathsTest {
    private val root: Path = Files.createTempDirectory("kachok-set")
    private val dispatchers = EngineDispatchers()
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private var set: TorrentSet? = null

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun clean() {
        set?.close()
        scope.cancel()
        dispatchers.close()
        root.deleteRecursively()
    }

    /** Two torrents, both naming one file `payload.bin`, differing only in length. */
    private fun payload(length: Int): Metainfo {
        val hashes = "A".repeat(20)
        val source =
            "d4:infod6:lengthi${length}e4:name11:payload.bin12:piece lengthi${length}e" +
                "6:pieces20:$hashes" + "ee"
        return MetainfoParser.parse(source.encodeToByteArray())
    }

    private fun options() = RuntimeOptions(directory = root, port = 0)

    @Test
    fun aSecondTorrentClaimingTheSameFileIsRefusedAndSaysWhose() {
        val open = TorrentSet(dispatchers = dispatchers, scope = scope).also { set = it }
        open.add(payload(1024), options())

        val refused =
            assertFailsWith<IllegalArgumentException> { open.add(payload(2048), options()) }
        val message = refused.message.orEmpty()
        assertTrue("payload.bin" in message, message)
        assertTrue("already belongs to" in message, message)
    }

    /** And the same question can be asked before offering to add, which is what a dialog needs. */
    @Test
    fun theCollisionCanBeAskedAboutWithoutAddingAnything() {
        val open = TorrentSet(dispatchers = dispatchers, scope = scope).also { set = it }
        val second = payload(2048)
        assertNull(open.collisionWith(second, root), "nothing is running yet")

        open.add(payload(1024), options())
        val collision = open.collisionWith(second, root)
        assertEquals(root.resolve("payload.bin"), collision?.first)
        assertEquals("payload.bin", collision?.second)
    }

    /** A different directory is not a collision, which is the whole point of asking. */
    @Test
    fun theSameNameInAnotherDirectoryIsFine() {
        val open = TorrentSet(dispatchers = dispatchers, scope = scope).also { set = it }
        open.add(payload(1024), options())
        val elsewhere = Files.createDirectory(root.resolve("elsewhere"))
        assertNull(open.collisionWith(payload(2048), elsewhere))
    }

    /**
     * The resume record carries the info hash, so one torrent's can never be read as another's.
     *
     * This was the sharper half of the same defect: one `payload.bin.resume` for two torrents,
     * whichever saved last winning.
     */
    @Test
    fun twoTorrentsOfOneNameGetTwoResumeRecords() {
        val first = TorrentRuntime.resumeName(payload(1024))
        val second = TorrentRuntime.resumeName(payload(2048))
        assertNotEquals(first, second, "two torrents share a resume record")
        assertTrue(first.startsWith("payload.bin."), first)
        assertTrue(first.endsWith(".resume"), first)
    }

    /** The paths a collision is judged on are the ones `FileSet` would actually open. */
    @Test
    fun theQuestionIsAskedAboutThePathsThatWouldBeOpened() {
        val metainfo = payload(1024)
        assertEquals(FileSet.pathsIn(root, metainfo), listOf(root.resolve("payload.bin")))
    }
}
