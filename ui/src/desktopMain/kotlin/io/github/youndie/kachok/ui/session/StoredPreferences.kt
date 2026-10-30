package io.github.youndie.kachok.ui.session

import io.github.youndie.kachok.control.configDirectory
import io.github.youndie.kachok.ui.theme.InterfaceKit
import io.github.youndie.kachok.ui.theme.material.MaterialColours
import io.github.youndie.kachok.ui.theme.material.SchemeStyle
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
        maxActiveDownloads = number(MAX_ACTIVE)?.toInt() ?: fallback.maxActiveDownloads,
        maxConnections = number(MAX_CONNECTIONS)?.toInt() ?: fallback.maxConnections,
        maxDials = number(MAX_DIALS)?.toInt() ?: fallback.maxDials,
        dht = text(DHT)?.toBooleanStrictOrNull() ?: fallback.dht,
        announceToAllTrackers =
            text(ALL_TRACKERS)?.toBooleanStrictOrNull() ?: fallback.announceToAllTrackers,
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
        // A kit this build does not know — a newer build's, read by an older one — is the Material
        // kit and not a refusal to start, and not the fallback's either: an unknown name says
        // nothing about what somebody wanted, and Material is what every build can draw (B-160).
        interfaceKit = text(INTERFACE)?.let { InterfaceKit.of(it) } ?: fallback.interfaceKit,
        // The same rule for the Material kit's colours (B-161), one key at a time: a seed that is
        // not `#RRGGBB` is the design's teal, a style this build does not know is the design's, and
        // a damaged seed does not cost the person their style or the other way round.
        materialColours =
            MaterialColours(
                seed =
                    text(COLOUR_SEED)?.let { MaterialColours.seedOf(it) ?: MaterialColours.DESIGN_SEED }
                        ?: fallback.materialColours.seed,
                style = text(COLOUR_STYLE)?.let { SchemeStyle.of(it) } ?: fallback.materialColours.style,
            ),
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
            setProperty(ALL_TRACKERS, preferences.announceToAllTrackers.toString())
            setProperty(AUTOSTART, preferences.autostart.toString())
            preferences.lastDirectory?.let { setProperty(LAST_DIRECTORY, it) }
            setProperty(CLOSE_TO_TRAY, preferences.closeToTray.toString())
            setProperty(TRAY_EXPLAINED, preferences.trayExplained.toString())
            setProperty(DETAILS_WIDTH, preferences.detailsWidth.toString())
            setProperty(INTERFACE, preferences.interfaceKit.stored)
            setProperty(COLOUR_SEED, preferences.materialColours.storedSeed)
            setProperty(COLOUR_STYLE, preferences.materialColours.style.stored)
            preferences.maxPeers?.let { setProperty(MAX_PEERS, it.toString()) }
            preferences.pipelineDepth?.let { setProperty(PIPELINE_DEPTH, it.toString()) }
            preferences.uploadLimitKibPerSecond?.let { setProperty(UPLOAD_LIMIT, it.toString()) }
            preferences.downloadLimitKibPerSecond?.let { setProperty(DOWNLOAD_LIMIT, it.toString()) }
            preferences.maxActiveDownloads?.let { setProperty(MAX_ACTIVE, it.toString()) }
            preferences.maxConnections?.let { setProperty(MAX_CONNECTIONS, it.toString()) }
            preferences.maxDials?.let { setProperty(MAX_DIALS, it.toString()) }
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
private const val MAX_ACTIVE = "maxActiveDownloads"
private const val MAX_CONNECTIONS = "maxConnections"
private const val MAX_DIALS = "maxDials"
private const val DHT = "dht"
private const val ALL_TRACKERS = "announceToAllTrackers"
private const val AUTOSTART = "autostart"
private const val LAST_DIRECTORY = "lastDirectory"
private const val CLOSE_TO_TRAY = "closeToTray"
private const val TRAY_EXPLAINED = "trayExplained"
private const val DETAILS_WIDTH = "detailsWidth"
private const val INTERFACE = "interface"
private const val COLOUR_SEED = "colourSeed"
private const val COLOUR_STYLE = "colourStyle"
