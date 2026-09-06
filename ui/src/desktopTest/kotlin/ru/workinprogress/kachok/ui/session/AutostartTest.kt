package ru.workinprogress.kachok.ui.session

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
 * Starting with the operating system, in the three places the three platforms keep it
 * ([B-83](../../../../../../../../docs/backlog/B-83-autostart-and-its-setting.md)).
 *
 * **Nothing here touches the machine it runs on.** The launcher, the home directory, the
 * configuration directory and `reg.exe` are all parameters, so this writes launch agents and `.desktop`
 * files into a temporary directory and counts the arguments a registry command *would* have been
 * given. A test that wrote a real `~/Library/LaunchAgents/ru.workinprogress.kachok.plist` would
 * start this client on the next login of whoever ran the suite.
 */
class AutostartTest {
    private val root: Path = Files.createTempDirectory("kachok-autostart")
    private val launcher: Path get() =
        root
            .resolve(
                "kachok",
            ).also { if (!Files.exists(it)) Files.write(it, byteArrayOf()) }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() = root.deleteRecursively()

    private fun mac() = autostartFor(launcher.toString(), os = "Mac OS X", home = root, config = root)

    private fun linux() = autostartFor(launcher.toString(), os = "Linux", home = root, config = root)

    private fun windows(reg: (List<String>) -> Int) =
        autostartFor(launcher.toString(), os = "Windows 11", home = root, config = root, reg = reg)

    // ---- macOS

    @Test
    fun macOsWritesALaunchAgentThatNamesTheLauncherAndTheFlag() {
        val plist = root.resolve("Library/LaunchAgents/ru.workinprogress.kachok.plist")
        assertTrue(!mac().isEnabled(), "it claimed to be on before anything was written")
        assertNull(mac().enable())

        val text = Files.readString(plist)
        assertContains(text, launcher.toString())
        assertContains(text, "--autostart")
        assertContains(text, "<key>RunAtLoad</key><true/>")
        assertTrue(mac().isEnabled())
    }

    /** Turning it off leaves nothing behind, which is half of the acceptance criterion. */
    @Test
    fun macOsRemovesTheLaunchAgentAgain() {
        mac().enable()
        assertNull(mac().disable())
        assertTrue(!Files.exists(root.resolve("Library/LaunchAgents/ru.workinprogress.kachok.plist")))
        assertTrue(!mac().isEnabled())
    }

    /** Off twice is not an error: the entry is gone either way, which is what was asked for. */
    @Test
    fun turningItOffWhenItWasNeverOnIsNotAFailure() {
        assertNull(mac().disable())
        assertNull(linux().disable())
    }

    // ---- Linux

    @Test
    fun linuxWritesAFreedesktopAutostartEntry() {
        assertNull(linux().enable())
        val text = Files.readString(root.resolve("autostart/kachok.desktop"))
        assertContains(text, "[Desktop Entry]")
        assertContains(text, "Exec=\"$launcher\" --autostart")
        assertTrue(linux().isEnabled())
    }

    @Test
    fun linuxRemovesTheEntryAgain() {
        linux().enable()
        assertNull(linux().disable())
        assertTrue(!Files.exists(root.resolve("autostart/kachok.desktop")))
    }

    // ---- Windows

    @Test
    fun windowsWritesTheRunKeyUnderTheUsersOwnHive() {
        val commands = mutableListOf<List<String>>()
        assertNull(
            windows {
                commands += it
                0
            }.enable(),
        )

        val command = commands.single()
        assertEquals(listOf("reg", "add"), command.take(2))
        assertContains(command[2], "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run")
        assertContains(command, "\"$launcher\" --autostart")
        assertTrue("/f" in command, "without /f the second time somebody turns it on is a prompt")
    }

    /** `HKLM` would need the administrator and would start it for everybody who logs in. */
    @Test
    fun windowsNeverTouchesTheMachineWideHive() {
        val commands = mutableListOf<List<String>>()
        windows {
            commands += it
            0
        }.enable()
        windows {
            commands += it
            0
        }.disable()
        windows {
            commands += it
            0
        }.isEnabled()
        assertTrue(commands.flatten().none { it.contains("HKLM") }, "a machine-wide key was written: $commands")
    }

    @Test
    fun aRegistryCommandThatFailsIsReportedRatherThanSwallowed() {
        val failure = windows { 1 }.enable()
        assertContains(failure.orEmpty(), "exited with 1")
    }

    @Test
    fun windowsReadsTheKeyBackRatherThanRememberingWhatItWrote() {
        assertTrue(windows { 0 }.isEnabled())
        assertTrue(!windows { 1 }.isEnabled(), "a missing key was read as present")
    }

    // ---- not an installed build

    /**
     * A `:ui:run` has no stable path to point an entry at, and says so instead of writing one.
     *
     * `jpackage.app-path` is set by the packaged launcher and by nothing else. Without this check
     * the entry would name a `java` in a Gradle cache, and the person would find out at their next
     * login — the failure that is hardest to connect back to the checkbox that caused it.
     */
    @Test
    fun aBuildThatIsNotInstalledRefusesAndSaysWhy() {
        val notInstalled = autostartFor(launcher = null, os = "Mac OS X", home = root, config = root)
        assertContains(notInstalled.refusal.orEmpty(), "installed")
        assertContains(notInstalled.enable().orEmpty(), "installed")
        assertTrue(!notInstalled.isEnabled())
        assertTrue(!Files.exists(root.resolve("Library/LaunchAgents/ru.workinprogress.kachok.plist")))
    }

    /** A launcher path that names nothing is the same case: a stale one is not a build. */
    @Test
    fun aLauncherPathThatNamesNothingIsNotAnInstalledBuild() {
        val gone = autostartFor(root.resolve("not-here").toString(), os = "Linux", home = root, config = root)
        assertContains(gone.refusal.orEmpty(), "installed")
    }
}
