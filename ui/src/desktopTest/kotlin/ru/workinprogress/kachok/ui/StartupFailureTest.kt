package ru.workinprogress.kachok.ui

import ru.workinprogress.kachok.ui.session.configDirectory
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A failure before the window has to leave a name behind.
 *
 * A `jpackage` launcher answers an uncaught exception with a message box saying the Java machine
 * failed to start, and nothing else — no class, no line, no cause. That is what a person opening a
 * `.torrent` on Windows saw, and there was no way to answer them: the launcher had swallowed the
 * only sentence that would have said which of the twenty things `main` does was the one that threw.
 *
 * **This test writes into the real configuration directory**, because that is the path the failing
 * launcher would use and there is no seam in `main` to point somewhere else — so it moves any file
 * that is already there out of the way and puts it back.
 */
class StartupFailureTest {
    private val report = configDirectory().resolve("startup-error.txt")
    private val kept = report.resolveSibling("startup-error.txt.kept-by-a-test")

    @BeforeTest
    fun moveAsideWhateverIsThere() {
        if (Files.exists(report)) Files.move(report, kept)
    }

    @AfterTest
    fun putItBack() {
        Files.deleteIfExists(report)
        if (Files.exists(kept)) Files.move(kept, report)
    }

    @Test
    fun aFailureBeforeTheWindowIsWrittenDownWhereSomebodyCanFindIt() {
        val thrown =
            assertFailsWith<IllegalStateException> { startupFailuresAreReadable { error("the pool is empty") } }
        assertContains(thrown.message.orEmpty(), "the pool is empty")

        assertTrue(Files.exists(report), "nothing was written to $report")
        val text = Files.readString(report)
        assertContains(text, "the pool is empty")
        assertTrue("StartupFailureTest" in text, "the stack trace is not in the report")
        assertTrue(System.getProperty("os.name") in text, "the report does not say which machine")
    }

    /** The exception still leaves: an exit code that says it worked would be worse than the box. */
    @Test
    fun theFailureIsRethrownSoTheProcessStillFails() {
        assertFailsWith<UnsupportedOperationException> {
            startupFailuresAreReadable { throw UnsupportedOperationException("no") }
        }
    }

    /** Nothing is written when nothing goes wrong; a report on the disk means something happened. */
    @Test
    fun aStartThatWorksLeavesNoReport() {
        startupFailuresAreReadable { }
        assertTrue(!Files.exists(report), "a successful start left a failure report behind")
    }
}
