package io.github.youndie.kachok.control.store

import io.github.youndie.kachok.engine.runtime.SetLimits
import java.nio.file.Files
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** B-151: the client's limits outlive the process, in the file the window and the CLI share. */
class StoredLimitsTest {
    private val root = Files.createTempDirectory("kachok-limits")
    private val file = limitsFile(root.resolve("torrents"))

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun whatIsSavedIsWhatIsLoaded() {
        assertNull(loadLimits(file), "a client nobody limited came back limited")
        val limits = SetLimits(downloadBytesPerSecond = 1_250_000, uploadBytesPerSecond = 0, maxActiveDownloads = 3)
        saveLimits(file, limits)
        assertEquals(root.resolve("limits.properties"), file, "beside the remembered torrents")
        assertEquals(limits, loadLimits(file))
    }

    @Test
    fun aFileThatMakesNoSenseIsSaidAndIgnored() {
        Files.createDirectories(file.parent)
        file.writeText("maxActiveDownloads=-4\n")
        val said = mutableListOf<String>()
        assertNull(loadLimits(file) { said += it })
        assertTrue(said.single().startsWith("ignoring"), said.toString())
    }
}
