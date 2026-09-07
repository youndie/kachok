package ru.workinprogress.kachok.ui.session

import ru.workinprogress.kachok.ui.details.FileRow
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a double-click on a file does, and what it refuses to do
 * ([B-85](../../../../../../../../docs/backlog/B-85-open-a-file-from-the-files-tab.md)).
 *
 * Every branch here ends in a sentence the person who double-clicked can act on, and that is the
 * assertion: a gesture that silently does nothing is indistinguishable from a broken window, and a
 * gesture that silently succeeds on a half-fetched file hands a player a truncated one.
 *
 * `open` and the desktop probe are parameters, so this runs on a build machine with no display —
 * which is where it runs.
 */
class OpenFileTest {
    private val root: Path = Files.createTempDirectory("kachok-open")

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() = root.deleteRecursively()

    private fun file(
        name: String = "payload.bin",
        progress: String = "100%",
        wanted: Boolean = true,
        complete: Boolean = true,
        path: String? = null,
    ) = FileRow(name, "1.2 MiB", progress, wanted, complete, path)

    private fun onDisk(name: String = "payload.bin"): String =
        root.resolve(name).also { Files.write(it, byteArrayOf(1, 2, 3)) }.toString()

    @Test
    fun aFinishedFileIsHandedToTheSystem() {
        var opened: Path? = null
        val refusal = openFile(file(path = onDisk()), open = { opened = it }, desktopAvailable = { true })
        assertNull(refusal, "a finished file was refused: $refusal")
        assertEquals(root.resolve("payload.bin"), opened)
    }

    /** The item's open decision, and the one branch a silent success would be worst on. */
    @Test
    fun anUnfinishedFileIsRefusedAndTheReasonNamesHowFarItGot() {
        var opened = false
        val refusal =
            openFile(
                file(progress = "41%", complete = false, path = onDisk()),
                open = { opened = true },
                desktopAvailable = { true },
            )
        assertEquals(false, opened, "a truncated file was handed over")
        assertContains(refusal.orEmpty(), "41%")
        assertContains(refusal.orEmpty(), "truncated")
    }

    /**
     * 99.6% prints as `100%`, and a check on the printed number would open it.
     *
     * The row's percentage is for reading; the decision is made on bytes.
     */
    @Test
    fun aFileThatOnlyRoundsToWholeIsStillUnfinished() {
        var opened = false
        openFile(file(progress = "100%", complete = false, path = onDisk()), open = {
            opened = true
        }, desktopAvailable = { true })
        assertEquals(false, opened, "a file that merely rounds to 100% was opened")
    }

    @Test
    fun aSkippedFileSaysThereIsNothingToOpen() {
        val refusal = openFile(file(progress = "skip", wanted = false, complete = false), desktopAvailable = { true })
        assertContains(refusal.orEmpty(), "skipped")
    }

    /** A Linux session with no desktop environment: `Desktop.isDesktopSupported()` is false there. */
    @Test
    fun noDesktopEnvironmentSaysSoRatherThanDoingNothing() {
        var opened = false
        val refusal = openFile(file(path = onDisk()), open = { opened = true }, desktopAvailable = { false })
        assertEquals(false, opened)
        assertContains(refusal.orEmpty(), "no desktop environment")
    }

    @Test
    fun aFileThatIsNotThereSaysWhereItWasLookedFor() {
        val missing = root.resolve("gone.bin").toString()
        val refusal = openFile(file(path = missing), desktopAvailable = { true })
        assertContains(refusal.orEmpty(), missing)
    }

    /**
     * A refusal from the system keeps the system's own words, and names the folder.
     *
     * The first version of this threw the exception's message away and said only "nothing is
     * registered for that type" — which turned a report of "it cannot find the program" into six
     * possible causes and no way to tell them apart. The folder is what the *person* can act on;
     * the message is what somebody reading their screenshot can.
     */
    @Test
    fun aRefusalFromTheSystemKeepsWhatTheSystemSaid() {
        val refusal =
            openFile(
                file(path = onDisk()),
                open = { throw IOException("Failed to open file: no application is associated") },
                desktopAvailable = { true },
            )
        assertContains(refusal.orEmpty(), "payload.bin")
        assertContains(refusal.orEmpty(), "no application is associated")
        assertContains(refusal.orEmpty(), root.toString())
    }

    /** An exception with nothing in it still produces a sentence rather than the word `null`. */
    @Test
    fun aRefusalWithNoMessageStillReadsAsASentence() {
        val refusal = openFile(file(path = onDisk()), open = { throw IOException() }, desktopAvailable = { true })
        assertContains(refusal.orEmpty(), "said nothing")
        assertEquals(false, refusal.orEmpty().contains("null"), "the message reads `null`: $refusal")
    }

    /** Before the metainfo arrives there is no path, and that is not the same as a missing file. */
    @Test
    fun aFileWithNoPathYetSaysTheMetainfoHasNotArrived() {
        assertContains(openFile(file(path = null), desktopAvailable = { true }).orEmpty(), "metainfo")
    }

    /** The message names the file, not the whole path, which nobody needs read back to them. */
    @Test
    fun theRefusalNamesTheFileRatherThanItsDirectories() {
        val refusal =
            openFile(file(name = "season/ep01.mkv", progress = "5%", complete = false), desktopAvailable = { true })
        assertContains(refusal.orEmpty(), "ep01.mkv")
        assertEquals(false, refusal.orEmpty().contains("season/"), "the message read out the directory: $refusal")
    }
}
