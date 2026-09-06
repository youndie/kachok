package ru.workinprogress.kachok.ui.session

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * The settings, on the disk, so that changing one is a decision and not a chore.
 *
 * **A properties file, not bencode.** The resume records are bencode because a resume record is a
 * BitTorrent thing; a settings file is not, and `java.util.Properties` needs no parser, no
 * dependency and no explaining to anybody who opens it in an editor.
 *
 * **In the platform's own place, not beside the downloads.** The download directory is one of the
 * settings — a file that moved when you changed a setting is a file you lose.
 *
 * **The bound port is deliberately absent.** [Preferences.port] is what the listener actually got,
 * a fact of the run rather than a wish; writing it down would mean a client that failed to bind
 * 6881 once would ask for 6882 for the rest of its life.
 */
internal fun preferencesFile(): Path = configDirectory().resolve("settings.properties")

/**
 * The platform's own place for what this client owns: the settings file and the torrent list.
 *
 * Not beside the downloads. The download directory is one of the settings, and a file that moves
 * when you change a setting is a file you lose.
 */
internal fun configDirectory(): Path {
    val home = System.getProperty("user.home").orEmpty()
    val os = System.getProperty("os.name").orEmpty()
    return when {
        os.startsWith("Mac") -> {
            Path.of(home, "Library", "Application Support", "kachok")
        }

        os.startsWith("Windows") -> {
            System.getenv("APPDATA")?.let { Path.of(it, "kachok") } ?: Path.of(home, "kachok")
        }

        else -> {
            System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it, "kachok") }
                ?: Path.of(home, ".config", "kachok")
        }
    }
}

/**
 * What was saved, or [fallback] for every field that was not.
 *
 * A file that cannot be read, cannot be parsed or holds nonsense where a number should be gives the
 * default and says so on stderr — the same rule a corrupt resume record gets, and for the same
 * reason: refusing to start because a preferences file is damaged is worse than starting with the
 * preferences somebody would have had anyway.
 */
internal fun loadPreferences(
    file: Path,
    fallback: Preferences,
): Preferences {
    val properties =
        try {
            if (!Files.exists(file)) return fallback
            Properties().apply { Files.newInputStream(file).use { load(it) } }
        } catch (unreadable: IOException) {
            System.err.println("kachok: ignoring $file: ${unreadable.message}")
            return fallback
        } catch (malformed: IllegalArgumentException) {
            System.err.println("kachok: $file is not a settings file: ${malformed.message}")
            return fallback
        }

    fun text(key: String): String? = properties.getProperty(key)?.takeIf { it.isNotBlank() }

    fun number(key: String): Long? = text(key)?.toLongOrNull()

    // Per field, not all-or-nothing: a file written by an older build is missing keys rather than
    // wrong, and losing every setting because one is absent would be the strictest possible answer
    // to the mildest possible problem.
    return fallback.copy(
        directory = text(DIRECTORY) ?: fallback.directory,
        startWhenAdded = text(START_WHEN_ADDED)?.toBooleanStrictOrNull() ?: fallback.startWhenAdded,
        maxPeers = number(MAX_PEERS)?.toInt() ?: fallback.maxPeers,
        pipelineDepth = number(PIPELINE_DEPTH)?.toInt() ?: fallback.pipelineDepth,
        uploadLimitKibPerSecond = number(UPLOAD_LIMIT) ?: fallback.uploadLimitKibPerSecond,
        downloadLimitKibPerSecond = number(DOWNLOAD_LIMIT) ?: fallback.downloadLimitKibPerSecond,
        dht = text(DHT)?.toBooleanStrictOrNull() ?: fallback.dht,
        // The file is what the settings screen shows; whether the entry is *really* there is asked
        // of the system when the screen opens, because somebody can remove it without this client.
        autostart = text(AUTOSTART)?.toBooleanStrictOrNull() ?: fallback.autostart,
        lastDirectory = text(LAST_DIRECTORY) ?: fallback.lastDirectory,
        closeToTray = text(CLOSE_TO_TRAY)?.toBooleanStrictOrNull() ?: fallback.closeToTray,
        trayExplained = text(TRAY_EXPLAINED)?.toBooleanStrictOrNull() ?: fallback.trayExplained,
        // Through `withDetailsWidth` so a hand-edited file cannot ask for a panel the window
        // cannot draw.
        detailsWidth =
            text(DETAILS_WIDTH)
                ?.toFloatOrNull()
                ?.let { fallback.withDetailsWidth(it).detailsWidth }
                ?: fallback.detailsWidth,
    )
}

/**
 * Written to a neighbour and moved into place, which is the engine's rule for the resume record.
 *
 * A settings file half-written by a process that was killed is a settings file that reads as
 * nonsense on the next start; the move is the one operation the filesystem will not do halfway.
 */
internal fun savePreferences(
    file: Path,
    preferences: Preferences,
) {
    val properties =
        Properties().apply {
            setProperty(DIRECTORY, preferences.directory)
            setProperty(START_WHEN_ADDED, preferences.startWhenAdded.toString())
            setProperty(DHT, preferences.dht.toString())
            setProperty(AUTOSTART, preferences.autostart.toString())
            preferences.lastDirectory?.let { setProperty(LAST_DIRECTORY, it) }
            setProperty(CLOSE_TO_TRAY, preferences.closeToTray.toString())
            setProperty(TRAY_EXPLAINED, preferences.trayExplained.toString())
            setProperty(DETAILS_WIDTH, preferences.detailsWidth.toString())
            preferences.maxPeers?.let { setProperty(MAX_PEERS, it.toString()) }
            preferences.pipelineDepth?.let { setProperty(PIPELINE_DEPTH, it.toString()) }
            preferences.uploadLimitKibPerSecond?.let { setProperty(UPLOAD_LIMIT, it.toString()) }
            preferences.downloadLimitKibPerSecond?.let { setProperty(DOWNLOAD_LIMIT, it.toString()) }
        }
    try {
        Files.createDirectories(file.parent)
        val temporary = file.resolveSibling("${file.fileName}.new")
        Files.newOutputStream(temporary).use { properties.store(it, "kachok") }
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (unwritable: IOException) {
        // Not fatal and not silent: the window goes on working with settings that will not outlive
        // it, and the reason is on stderr rather than in a dialog nobody can act on.
        System.err.println("kachok: cannot save settings to $file: ${unwritable.message}")
    }
}

private const val DIRECTORY = "directory"
private const val START_WHEN_ADDED = "startWhenAdded"
private const val MAX_PEERS = "maxPeers"
private const val PIPELINE_DEPTH = "pipelineDepth"
private const val UPLOAD_LIMIT = "uploadLimitKibPerSecond"
private const val DOWNLOAD_LIMIT = "downloadLimitKibPerSecond"
private const val DHT = "dht"
private const val AUTOSTART = "autostart"
private const val LAST_DIRECTORY = "lastDirectory"
private const val CLOSE_TO_TRAY = "closeToTray"
private const val TRAY_EXPLAINED = "trayExplained"
private const val DETAILS_WIDTH = "detailsWidth"
