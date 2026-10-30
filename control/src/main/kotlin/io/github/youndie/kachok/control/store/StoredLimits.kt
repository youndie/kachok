package io.github.youndie.kachok.control.store

import io.github.youndie.kachok.engine.runtime.SetLimits
import io.github.youndie.kachok.engine.runtime.TorrentSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * The client's own limits, kept between runs
 * ([B-151](../../../../../../../../../../docs/backlog/B-151-one-limit-for-the-whole-client.md)).
 *
 * **One file for both owners of the engine.** The window and the CLI's MCP engine take turns at
 * being the client (B-81), and a limit set through one and gone when the other starts is not a
 * limit anybody can rely on. So it lives beside the remembered torrents, which both already share,
 * and whichever process owns the engine reads it at the start and writes it on every change.
 */
public fun limitsFile(torrents: Path): Path = torrents.resolveSibling("limits.properties")

/**
 * What a client a person runs starts with before anybody has chosen anything: no rate limits and no
 * queue, but the caps on connections and dials (B-152), which a home router needs whether or not
 * anybody thought to set them.
 */
public val firstRunLimits: SetLimits =
    SetLimits(
        maxConnections = SetLimits.RECOMMENDED_MAX_CONNECTIONS,
        maxDialsInFlight = SetLimits.RECOMMENDED_MAX_DIALS,
    )

/** What was saved, or null when nothing was — or what was is unreadable, which is said on [report]. */
public fun loadLimits(
    file: Path,
    report: (String) -> Unit = {},
): SetLimits? {
    if (!Files.exists(file)) return null
    return try {
        val properties = Properties().apply { Files.newBufferedReader(file).use { load(it) } }
        SetLimits(
            downloadBytesPerSecond = properties.getProperty(DOWNLOAD)?.toLongOrNull() ?: 0,
            uploadBytesPerSecond = properties.getProperty(UPLOAD)?.toLongOrNull() ?: 0,
            maxActiveDownloads = properties.getProperty(MAX_ACTIVE)?.toIntOrNull() ?: 0,
            // A file written before B-152 has neither key, and is a client that never chose: it
            // gets the caps a first run would, not "no cap".
            maxConnections = properties.getProperty(MAX_CONNECTIONS)?.toIntOrNull() ?: firstRunLimits.maxConnections,
            maxDialsInFlight = properties.getProperty(MAX_DIALS)?.toIntOrNull() ?: firstRunLimits.maxDialsInFlight,
        )
    } catch (unreadable: IOException) {
        report("cannot read $file: ${unreadable.message}; running without limits")
        null
    } catch (nonsense: IllegalArgumentException) {
        report("ignoring $file: ${nonsense.message}")
        null
    }
}

/** Writes [limits] to a neighbour and moves it into place, as every other file of the client. */
public fun saveLimits(
    file: Path,
    limits: SetLimits,
) {
    val properties =
        Properties().apply {
            setProperty(DOWNLOAD, limits.downloadBytesPerSecond.toString())
            setProperty(UPLOAD, limits.uploadBytesPerSecond.toString())
            setProperty(MAX_ACTIVE, limits.maxActiveDownloads.toString())
            setProperty(MAX_CONNECTIONS, limits.maxConnections.toString())
            setProperty(MAX_DIALS, limits.maxDialsInFlight.toString())
        }
    Files.createDirectories(file.parent)
    val temporary = file.resolveSibling("${file.fileName}.new")
    Files.newBufferedWriter(temporary).use { properties.store(it, "kachok: limits for the whole client") }
    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

/**
 * Saves the set's limits each time they change, whoever changed them — the window's settings, an
 * agent's `set_limits`, a browser over the wire. The value the set started with is not written
 * back: it came from the file.
 */
public fun CoroutineScope.keepLimits(
    set: TorrentSet,
    file: Path,
    report: (String) -> Unit = {},
): Job =
    launch {
        set.limits.drop(1).collect { limits ->
            try {
                saveLimits(file, limits)
            } catch (failed: IOException) {
                report("cannot save $file: ${failed.message}")
            }
        }
    }

private const val DOWNLOAD = "downloadBytesPerSecond"
private const val UPLOAD = "uploadBytesPerSecond"
private const val MAX_ACTIVE = "maxActiveDownloads"
private const val MAX_CONNECTIONS = "maxConnections"
private const val MAX_DIALS = "maxDialsInFlight"
