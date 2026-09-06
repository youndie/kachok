package ru.workinprogress.kachok.ui.session

import ru.workinprogress.kachok.ui.settings.SettingKey
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Settings that outlive the process, and the ways a file on a disk goes wrong.
 *
 * The round trip is the boring half. The interesting cases are all failures: a file that is not
 * there, one that cannot be parsed, one written by an older build that is missing keys, and the
 * field that must *not* be written down at all.
 */
class StoredPreferencesTest {
    private val root: Path = Files.createTempDirectory("kachok-settings")
    private val file: Path = root.resolve("settings.properties")
    private val defaults = Preferences(directory = "/tmp/default")

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun clean() {
        root.deleteRecursively()
    }

    @Test
    fun whatWasChangedComesBack() {
        val changed =
            defaults
                .withDirectory("/srv/torrents")
                .toggled(SettingKey.Dht, true)
                .toggled(SettingKey.StartWhenAdded, false)
                .typed(SettingKey.MaxPeers, "80")
                .typed(SettingKey.DownloadLimit, "12000")
        savePreferences(file, changed)
        val read = loadPreferences(file, defaults)

        assertEquals("/srv/torrents", read.directory)
        assertEquals(true, read.dht)
        assertEquals(false, read.startWhenAdded)
        assertEquals(80, read.maxPeers)
        assertEquals(12_000L, read.downloadLimitKibPerSecond)
        assertEquals(null, read.uploadLimitKibPerSecond, "a limit nobody set is still no limit")
    }

    /**
     * The bound port is not written down.
     *
     * It is what the listener actually got, not what anybody asked for. A client that lost 6881 to
     * something else once would otherwise ask for 6882 for the rest of its life.
     */
    @Test
    fun theBoundPortIsNotSaved() {
        savePreferences(file, defaults.boundTo(6885))
        assertEquals(null, loadPreferences(file, defaults).port)
        assertTrue("6885" !in Files.readString(file), "the port reached the file")
    }

    @Test
    fun noFileIsTheDefaults() {
        assertEquals(defaults, loadPreferences(root.resolve("absent.properties"), defaults))
    }

    /** A damaged file is ignored, not a refusal to start. */
    @Test
    fun aFileFullOfNonsenseIsIgnoredFieldByField() {
        file.writeText("directory=/srv/kept\nmaxPeers=lots\ndht=perhaps\n")
        val read = loadPreferences(file, defaults.copy(maxPeers = 50, dht = true))
        assertEquals("/srv/kept", read.directory, "the one readable line was thrown away with the rest")
        assertEquals(50, read.maxPeers, "an unparseable number lost the default")
        assertEquals(true, read.dht)
    }

    /** A file from an older build is missing keys rather than wrong about them. */
    @Test
    fun aFileMissingKeysKeepsTheDefaultsForThem() {
        file.writeText("directory=/srv/old\n")
        val read = loadPreferences(file, defaults.copy(pipelineDepth = 24))
        assertEquals("/srv/old", read.directory)
        assertEquals(24, read.pipelineDepth)
    }

    /** And the write leaves no half-file behind when it succeeds. */
    @Test
    fun theTemporaryFileDoesNotSurviveTheWrite() {
        savePreferences(file, defaults)
        assertTrue(file.exists())
        assertTrue(!root.resolve("settings.properties.new").exists(), "the neighbour was left on the disk")
    }

    /** A directory that does not exist yet is made, because the first run is every run once. */
    @Test
    fun theConfigDirectoryIsCreatedOnTheFirstSave() {
        val nested = root.resolve("a/b/c/settings.properties")
        savePreferences(nested, defaults)
        assertEquals(defaults.directory, loadPreferences(nested, Preferences(directory = "/other")).directory)
    }

    /** The path is the platform's own, and never inside the download directory. */
    @Test
    fun theFileIsNotBesideTheDownloads() {
        val path = preferencesFile().toString()
        assertTrue(path.endsWith("settings.properties"), path)
        assertTrue("kachok" in path, path)
        assertTrue("Downloads" !in path, "a settings file that moves when you change a setting is one you lose")
    }
}
