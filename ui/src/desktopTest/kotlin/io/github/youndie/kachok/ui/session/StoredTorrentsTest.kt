package io.github.youndie.kachok.ui.session

import io.github.youndie.kachok.engine.hex
import io.github.youndie.kachok.engine.metainfo.MetainfoParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The list the client keeps of its own torrents
 * ([B-81](../../../../../../../../docs/backlog/B-81-the-torrent-list-survives-a-restart.md)).
 *
 * What is asserted here is what a restart depends on: that a torrent written down comes back with
 * every decision that was made about it, and that a torrent that cannot be read comes back as
 * *something* rather than as nothing. The second half is the one that would rot quietly — an entry
 * that silently disappears looks exactly like an entry that was never there, and the person seeding
 * it has no reason to go and look.
 */
class StoredTorrentsTest {
    private val root: Path = Files.createTempDirectory("kachok-list")

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() = root.deleteRecursively()

    /** Built with the engine's own encoder, which is this repository's rule for bencode. */
    private fun torrent(name: String) = MetainfoParser.parse(TestTorrents.bytes(name))

    @Test
    fun aTorrentWrittenDownComesBackWithEveryDecisionMadeAboutIt() {
        val metainfo = torrent("alpha.bin")
        rememberTorrent(root, metainfo, saveTo = "/srv/one", paused = true, unwanted = setOf(2, 0), sequential = true)

        val stored = loadStoredTorrents(root).single()
        assertEquals(metainfo.infoHash.hex(), stored.infoHash)
        assertEquals(metainfo.infoHash.hex(), stored.metainfo?.infoHash?.hex())
        assertEquals("alpha.bin", stored.name)
        assertEquals("/srv/one", stored.directory)
        assertTrue(stored.paused)
        assertTrue(stored.sequential)
        assertEquals(setOf(0, 2), stored.unwanted)
        assertNull(stored.problem)
    }

    /** The order is the same on every run, so two starts do not shuffle the list under somebody. */
    @Test
    fun theListComesBackInTheSameOrderEveryTime() {
        listOf("alpha.bin", "beta.bin", "gamma.bin").forEach {
            rememberTorrent(root, torrent(it), saveTo = "/srv")
        }
        assertEquals(loadStoredTorrents(root).map { it.infoHash }, loadStoredTorrents(root).map { it.infoHash })
        assertEquals(3, loadStoredTorrents(root).size)
    }

    /**
     * The copy is a copy: the original file can go and the torrent stays.
     *
     * This is the whole reason the client writes one instead of remembering a path. A `.torrent`
     * lives in a downloads folder people empty.
     */
    @Test
    fun theRememberedTorrentIsACopyAndNotAPointer() {
        val metainfo = torrent("alpha.bin")
        rememberTorrent(root, metainfo, saveTo = "/srv")
        val copy = root.resolve("${metainfo.infoHash.hex()}.torrent")
        assertTrue(Files.exists(copy), "no copy was written")
        assertEquals(metainfo.infoHash.hex(), MetainfoParser.parse(Files.readAllBytes(copy)).infoHash.hex())
    }

    @Test
    fun aDamagedCopyIsAnEntryThatSaysSoRatherThanOneThatVanishes() {
        val metainfo = torrent("alpha.bin")
        rememberTorrent(root, metainfo, saveTo = "/srv")
        Files.write(root.resolve("${metainfo.infoHash.hex()}.torrent"), "not a torrent".encodeToByteArray())

        val stored = loadStoredTorrents(root).single()
        assertNull(stored.metainfo)
        assertEquals("alpha.bin", stored.name, "the row would have nothing but a hash to show")
        assertContains(stored.problem.orEmpty(), "damaged")
    }

    @Test
    fun aMissingCopyIsAnEntryThatSaysSo() {
        val metainfo = torrent("alpha.bin")
        rememberTorrent(root, metainfo, saveTo = "/srv")
        Files.delete(root.resolve("${metainfo.infoHash.hex()}.torrent"))

        val stored = loadStoredTorrents(root).single()
        assertNull(stored.metainfo)
        assertContains(stored.problem.orEmpty(), "gone")
    }

    /**
     * A copy filed under somebody else's hash is refused.
     *
     * The file name is a claim, and an unchecked one would open a torrent whose resume record
     * belongs to a different torrent — which is a re-check of the wrong data, silently.
     */
    @Test
    fun aCopyFiledUnderTheWrongHashIsNotOpened() {
        val mine = torrent("alpha.bin")
        rememberTorrent(root, mine, saveTo = "/srv")
        Files.write(root.resolve("${mine.infoHash.hex()}.torrent"), TestTorrents.bytes("beta.bin"))

        val stored = loadStoredTorrents(root).single()
        assertNull(stored.metainfo)
        assertContains(stored.problem.orEmpty(), "a different torrent")
    }

    /**
     * The order changes while the torrent runs, so it has to be written down while it runs.
     *
     * A decision that does not survive a restart is one somebody takes again every time
     * ([B-89](../../../../../../../../docs/backlog/B-89-sequential-on-a-running-torrent.md)).
     */
    @Test
    fun theOrderIsRecordedWithoutDisturbingAnythingElse() {
        val metainfo = torrent("alpha.bin")
        rememberTorrent(root, metainfo, saveTo = "/srv/one", paused = true, unwanted = setOf(1))
        rememberSequential(root, metainfo.infoHash.hex(), sequential = true)

        val stored = loadStoredTorrents(root).single()
        assertTrue(stored.sequential)
        assertTrue(stored.paused, "recording the order lost the pause")
        assertEquals(setOf(1), stored.unwanted)
        assertEquals("/srv/one", stored.directory)

        rememberSequential(root, metainfo.infoHash.hex(), sequential = false)
        assertTrue(!loadStoredTorrents(root).single().sequential)
    }

    /** A torrent this client does not remember is a race with a removal, not one to invent. */
    @Test
    fun recordingSomethingForATorrentThatIsNotThereWritesNothing() {
        rememberSequential(root, "0".repeat(40), sequential = true)
        rememberPaused(root, "0".repeat(40), paused = true)
        assertEquals(emptyList(), loadStoredTorrents(root))
    }

    @Test
    fun pausingRecordsOnlyThePauseAndLeavesEverythingElse() {
        val metainfo = torrent("alpha.bin")
        rememberTorrent(root, metainfo, saveTo = "/srv/one", unwanted = setOf(1), sequential = true)
        rememberPaused(root, metainfo.infoHash.hex(), paused = true)

        val stored = loadStoredTorrents(root).single()
        assertTrue(stored.paused)
        assertEquals("/srv/one", stored.directory)
        assertEquals(setOf(1), stored.unwanted)
        assertTrue(stored.sequential)

        rememberPaused(root, metainfo.infoHash.hex(), paused = false)
        assertTrue(!loadStoredTorrents(root).single().paused)
    }

    /** A torrent nobody asked to keep must not come back on the next start. */
    @Test
    fun forgettingRemovesBothHalvesOfTheEntry() {
        val metainfo = torrent("alpha.bin")
        rememberTorrent(root, metainfo, saveTo = "/srv")
        forgetTorrent(root, metainfo.infoHash.hex())

        assertEquals(emptyList(), loadStoredTorrents(root))
        assertTrue(!Files.exists(root.resolve("${metainfo.infoHash.hex()}.torrent")), "the copy was left behind")
    }

    /** A first run, which is a directory that is not there. Not an error and not a warning. */
    @Test
    fun aDirectoryThatDoesNotExistIsAnEmptyList() {
        assertEquals(emptyList(), loadStoredTorrents(root.resolve("never-written")))
    }
}
