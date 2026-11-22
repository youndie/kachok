package io.github.youndie.kachok.engine.storage

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one irreversible thing the client does, checked against real files (B-191, before it the
 * window's `DeleteQuietlyTest`).
 *
 * A remove that deletes the data cannot be undone by pressing anything, so the interesting cases
 * are the ones where it must *not* delete: a directory that still holds somebody else's file, the
 * folder the person saved into, and a file that is already gone.
 */
class DownloadedFilesTest {
    private val base: Path = Files.createTempDirectory("kachok-delete")

    /** Where the torrents were saved — the folder the person chose, inside the scratch one. */
    private val root: Path = base.resolve("Downloads").also { it.createDirectories() }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun clean() {
        base.deleteRecursively()
    }

    private fun file(name: String): Path =
        root.resolve(name).also {
            it.parent.createDirectories()
            it.writeText("x")
        }

    @Test
    fun everyFileGoesAndSoDoesTheDirectoryItLeavesEmpty() {
        val one = file("Sintel/video.mkv")
        val two = file("Sintel/subs.srt")
        assertEquals(emptyList(), DownloadedFiles.delete(listOf(one, two), root))
        assertFalse(one.exists())
        assertFalse(two.exists())
        assertFalse(root.resolve("Sintel").exists(), "the torrent's own directory was left behind")
    }

    /** The one an agent's remove left behind: every folder of the torrent's, however deep. */
    @Test
    fun foldersInsideTheTorrentsFolderGoToo() {
        val episode = file("Show S01/Season 1/Extras/e01.mkv")
        DownloadedFiles.delete(listOf(episode), root)
        assertFalse(root.resolve("Show S01").exists(), "an empty tree of the torrent's folders stayed")
        assertTrue(root.exists())
    }

    /**
     * A single-file torrent sits straight in the folder it was saved into, and that folder is the
     * person's even when the torrent was the only thing in it. The window's own version deleted it.
     */
    @Test
    fun theFolderItWasSavedIntoStaysEvenEmpty() {
        val movie = file("movie.mkv")
        DownloadedFiles.delete(listOf(movie), root)
        assertFalse(movie.exists())
        assertTrue(root.exists(), "the save directory was deleted with the torrent")
    }

    /** A directory holding something this torrent did not write stays, with what is in it. */
    @Test
    fun aDirectoryWithSomebodyElsesFileInItIsLeftAlone() {
        val ours = file("shared/ours.bin")
        val theirs = file("shared/notes.txt")
        DownloadedFiles.delete(listOf(ours), root)
        assertFalse(ours.exists())
        assertTrue(theirs.exists(), "a file nobody asked to delete was deleted")
        assertTrue(root.resolve("shared").exists())
    }

    /** A file the person already moved or deleted is not a reason to stop before the rest. */
    @Test
    fun aMissingFileDoesNotStopTheOnesAfterIt() {
        val gone = root.resolve("Sintel/moved.mkv")
        val here = file("Sintel/still-here.mkv")
        assertEquals(emptyList(), DownloadedFiles.delete(listOf(gone, here), root), "a missing file is no failure")
        assertFalse(here.exists(), "the second file survived the first one being absent")
    }

    /** A path outside the save directory prunes nothing above it, whatever is empty there. */
    @Test
    fun nothingOutsideTheRootIsPruned() {
        val elsewhere = base.resolve("Elsewhere/stray.bin").also { it.parent.createDirectories() }
        DownloadedFiles.pruneEmptyFolders(listOf(elsewhere), root)
        assertTrue(base.resolve("Elsewhere").exists(), "a folder outside the save directory was deleted")
    }
}
