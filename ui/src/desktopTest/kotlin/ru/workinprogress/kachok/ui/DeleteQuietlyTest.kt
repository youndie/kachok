package ru.workinprogress.kachok.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one irreversible thing this window does, checked against real files.
 *
 * A remove that deletes the data cannot be undone by pressing anything, so the interesting cases
 * are the ones where it must *not* delete: a directory that still holds somebody else's file, and
 * a file that is already gone.
 */
class DeleteQuietlyTest {
    private val root: Path = Files.createTempDirectory("kachok-delete")

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun clean() {
        root.deleteRecursively()
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
        deleteQuietly(listOf(one, two))
        assertFalse(one.exists())
        assertFalse(two.exists())
        assertFalse(root.resolve("Sintel").exists(), "the torrent's own directory was left behind")
    }

    /** A directory holding something this torrent did not write stays, with what is in it. */
    @Test
    fun aDirectoryWithSomebodyElsesFileInItIsLeftAlone() {
        val ours = file("shared/ours.bin")
        val theirs = file("shared/notes.txt")
        deleteQuietly(listOf(ours))
        assertFalse(ours.exists())
        assertTrue(theirs.exists(), "a file nobody asked to delete was deleted")
        assertTrue(root.resolve("shared").exists())
    }

    /** A file the person already moved or deleted is not a reason to stop before the rest. */
    @Test
    fun aMissingFileDoesNotStopTheOnesAfterIt() {
        val gone = root.resolve("Sintel/moved.mkv")
        val here = file("Sintel/still-here.mkv")
        deleteQuietly(listOf(gone, here))
        assertFalse(here.exists(), "the second file survived the first one being absent")
    }
}
