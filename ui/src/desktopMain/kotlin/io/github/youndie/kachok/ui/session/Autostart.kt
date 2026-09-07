package io.github.youndie.kachok.ui.session

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Starting with the operating system, which is three different files on three platforms and no
 * feature of the installer at all
 * ([B-83](../../../../../../../../docs/backlog/B-83-autostart-and-its-setting.md)).
 *
 * **Written by the client and not by the installer.** An installer can drop a shortcut in a startup
 * folder, and then turning the setting off would disagree with what is on the disk — a settings
 * screen describing something it does not control. Everything here is written when the checkbox is
 * pressed and removed when it is unpressed, so the two never diverge.
 *
 * **What "started" means: the window, minimised.** Starting it in front of whatever somebody was
 * doing is the intrusive answer; starting only the engine needs somewhere for the window to come
 * back from, and this client has no tray — the item's own not-covered half. Minimised is the one of
 * the three that is both what a person wants and something this client can already do: the entry
 * passes `--autostart`, and `main` opens the window minimised when it sees it.
 */
internal interface Autostart {
    /** Whether the entry is there *now*, read from the system rather than from the settings file. */
    fun isEnabled(): Boolean

    /** Null when it worked, a sentence when it did not. */
    fun enable(): String?

    fun disable(): String?

    /** Why this build cannot be started by the system, or null when it can. */
    val refusal: String?
}

/**
 * The one for this machine, or a [NoAutostart] that says why not.
 *
 * [launcher] is `jpackage.app-path`, which the packaged launcher sets and nothing else does — so it
 * is also the answer to "is this an installed build". A `:ui:run` would otherwise write an entry
 * naming a `java` in a Gradle cache, which stops meaning anything after the next build, and the
 * person would find out at their next login.
 */
internal fun autostartFor(
    launcher: String? = System.getProperty("jpackage.app-path"),
    os: String = System.getProperty("os.name").orEmpty(),
    home: Path = Path.of(System.getProperty("user.home").orEmpty()),
    config: Path = configDirectory().parent,
    reg: (List<String>) -> Int = ::runQuietly,
): Autostart {
    val path =
        launcher?.takeIf { it.isNotBlank() && Files.exists(Path.of(it)) }
            ?: return NoAutostart(
                "kachok can only start with the computer when it has been installed — this one is " +
                    "running from a build directory, and an entry naming it would break on the next build.",
            )
    return when {
        os.startsWith("Mac") -> PlistAutostart(home.resolve("Library/LaunchAgents/$LABEL.plist"), path)
        os.startsWith("Windows") -> RegistryAutostart(path, reg)
        else -> DesktopEntryAutostart(config.resolve("autostart/kachok.desktop"), path)
    }
}

private class NoAutostart(
    override val refusal: String,
) : Autostart {
    override fun isEnabled() = false

    override fun enable() = refusal

    override fun disable() = null
}

/** macOS: a launch agent in the user's own `LaunchAgents`, loaded by `launchd` at login. */
private class PlistAutostart(
    private val plist: Path,
    private val launcher: String,
) : Autostart {
    override val refusal: String? = null

    override fun isEnabled(): Boolean = Files.exists(plist)

    override fun enable(): String? =
        write(plist) {
            // Written by hand rather than through a plist library, because this is nine lines of
            // XML with one variable in it and the alternative is a dependency for nine lines.
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>Label</key><string>$LABEL</string>
                <key>ProgramArguments</key>
                <array>
                    <string>${launcher.escaped()}</string>
                    <string>$AUTOSTART_FLAG</string>
                </array>
                <key>RunAtLoad</key><true/>
            </dict>
            </plist>
            """.trimIndent()
        }

    override fun disable(): String? = remove(plist)
}

/** Linux: the freedesktop autostart directory, which every desktop environment reads. */
private class DesktopEntryAutostart(
    private val entry: Path,
    private val launcher: String,
) : Autostart {
    override val refusal: String? = null

    override fun isEnabled(): Boolean = Files.exists(entry)

    override fun enable(): String? =
        write(entry) {
            """
            [Desktop Entry]
            Type=Application
            Name=kachok
            Comment=A BitTorrent client
            Exec="$launcher" $AUTOSTART_FLAG
            Terminal=false
            X-GNOME-Autostart-enabled=true
            """.trimIndent()
        }

    override fun disable(): String? = remove(entry)
}

/**
 * Windows: `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`, through `reg.exe`.
 *
 * The user's own hive and not the machine's: this is one person asking for their client to start,
 * and `HKLM` would need the administrator and would start it for everybody who logs in.
 */
private class RegistryAutostart(
    private val launcher: String,
    private val reg: (List<String>) -> Int,
) : Autostart {
    override val refusal: String? = null

    override fun isEnabled(): Boolean = reg(listOf("reg", "query", RUN_KEY, "/v", "kachok")) == 0

    override fun enable(): String? =
        failed(
            reg(
                listOf(
                    "reg",
                    "add",
                    RUN_KEY,
                    "/v",
                    "kachok",
                    "/t",
                    "REG_SZ",
                    "/d",
                    "\"$launcher\" $AUTOSTART_FLAG",
                    "/f",
                ),
            ),
            "write the Run key",
        )

    // `/f` so that removing an entry that is not there is not an error: the setting being turned
    // off twice, or off on a machine it was never on, is not something to complain about.
    override fun disable(): String? =
        failed(reg(listOf("reg", "delete", RUN_KEY, "/v", "kachok", "/f")), "remove the Run key")

    private fun failed(
        code: Int,
        what: String,
    ) = if (code == 0) null else "kachok could not $what: reg.exe exited with $code."

    private companion object {
        const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    }
}

private fun write(
    file: Path,
    content: () -> String,
): String? =
    try {
        Files.createDirectories(file.parent)
        Files.writeString(file, content().trimEnd() + "\n")
        null
    } catch (unwritable: IOException) {
        "kachok could not write $file: ${unwritable.message}"
    }

private fun remove(file: Path): String? =
    try {
        Files.deleteIfExists(file)
        null
    } catch (undeletable: IOException) {
        "kachok could not remove $file: ${undeletable.message}"
    }

private fun runQuietly(command: List<String>): Int =
    try {
        ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor()
    } catch (unavailable: IOException) {
        // No `reg.exe` on the PATH is not a crash; it is an answer of "no".
        UNAVAILABLE
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        UNAVAILABLE
    }

/** A path inside XML: only `&` and `<` can end the string element early. */
private fun String.escaped(): String = replace("&", "&amp;").replace("<", "&lt;")

/**
 * What the entry passes, and what `main` reads to know it was started by the system rather than by
 * a person.
 */
internal const val AUTOSTART_FLAG: String = "--autostart"

private const val LABEL = "io.github.youndie.kachok"
private const val UNAVAILABLE = -1
