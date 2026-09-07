package io.github.youndie.kachok.ui.session

import io.github.youndie.kachok.engine.hex
import io.github.youndie.kachok.engine.metainfo.Metainfo
import io.github.youndie.kachok.engine.metainfo.MetainfoParser
import io.github.youndie.kachok.engine.metainfo.MetainfoWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import kotlin.io.path.name

/**
 * The list of torrents, on the disk, so that closing the window is not the same as abandoning them.
 *
 * Before this, closing the window lost every torrent: `main` reopened the one path in `argv[0]` and
 * nothing else, while the resume records were still on the disk, still good, and unreachable
 * ([B-81](../../../../../../../../docs/backlog/B-81-the-torrent-list-survives-a-restart.md)).
 *
 * **A copy of each `.torrent`, not a pointer to the original.** The file somebody added may be in a
 * downloads folder they empty, on a volume that is not mounted, or nowhere at all — a magnet
 * carries no file, and after BEP 9 the metainfo exists only in memory. A pointer would turn "I
 * tidied my Downloads folder" into "my client forgot what it was doing". `MetainfoWriter` splices
 * the info dictionary in as bytes, so the copy has the same info hash as the original and claims
 * the same resume record.
 *
 * **One pair of files per torrent, not one list file.** A growing list the client owns is not the
 * settings file — those are a handful of scalars a person edits — and one damaged entry must not
 * cost the other nine. A directory listing is the list, and a torrent that will not parse is one
 * row that says so.
 *
 * **The `.properties` file is what makes an entry exist.** It holds the decisions that are about
 * *this* torrent and are in no resume record and no metainfo: where it saves, whether it was
 * paused, which files were unticked, whether it asks for pieces in order.
 */
internal fun torrentsDirectory(): Path = configDirectory().resolve("torrents")

/**
 * One remembered torrent.
 *
 * [metainfo] is null when the copy could not be read, and then [problem] says why. Such an entry is
 * kept rather than dropped: a torrent that vanishes from the list without a word is
 * indistinguishable from one the client never had, and the person who has been seeding it for a
 * month deserves better than that.
 */
internal class StoredTorrent(
    val infoHash: String,
    val metainfo: Metainfo?,
    /**
     * What the torrent was called when it was remembered.
     *
     * Written down rather than read from the metainfo, because the case this exists for is the one
     * where there is no metainfo: `b9f1cc…0625 could not be opened` is a row nobody can act on,
     * and `ubuntu-24.04.iso could not be opened` is one they can.
     */
    val name: String,
    val directory: String,
    val paused: Boolean,
    val unwanted: Set<Int>,
    val sequential: Boolean,
    val problem: String? = null,
)

/**
 * Everything remembered, worst case included.
 *
 * A directory that does not exist is an empty list and not an error: it is what a first run looks
 * like.
 */
internal fun loadStoredTorrents(directory: Path): List<StoredTorrent> {
    val entries =
        try {
            if (!Files.isDirectory(directory)) return emptyList()
            Files.list(directory).use { paths ->
                paths.filter { it.name.endsWith(PROPERTIES_SUFFIX) }.toList()
            }
        } catch (unreadable: IOException) {
            System.err.println("kachok: cannot read $directory: ${unreadable.message}")
            return emptyList()
        }
    // By info hash, so two runs of the same client produce the list in the same order. Which order
    // a person wants is a separate question the item leaves open; an arbitrary one that changes
    // between restarts is not an answer to it.
    return entries.map { readEntry(it) }.sortedBy { it.infoHash }
}

private fun readEntry(file: Path): StoredTorrent {
    val infoHash = file.name.removeSuffix(PROPERTIES_SUFFIX)
    val properties =
        try {
            Properties().apply { Files.newInputStream(file).use { load(it) } }
        } catch (unreadable: IOException) {
            return broken(infoHash, shortHash(infoHash), "cannot be read: ${unreadable.message}")
        } catch (malformed: IllegalArgumentException) {
            return broken(infoHash, shortHash(infoHash), "is not a settings file: ${malformed.message}")
        }
    val directory = properties.getProperty(DIRECTORY).orEmpty()
    val name = properties.getProperty(NAME)?.takeIf { it.isNotBlank() } ?: shortHash(infoHash)
    val paused = properties.getProperty(PAUSED)?.toBooleanStrictOrNull() ?: false
    val sequential = properties.getProperty(SEQUENTIAL)?.toBooleanStrictOrNull() ?: false
    val unwanted =
        properties
            .getProperty(UNWANTED)
            .orEmpty()
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()

    val copy = file.resolveSibling("$infoHash$TORRENT_SUFFIX")
    val metainfo =
        try {
            MetainfoParser.parse(Files.readAllBytes(copy))
        } catch (missing: IOException) {
            return broken(infoHash, name, "its torrent file is gone: ${missing.message}", directory, paused)
        } catch (malformed: IllegalArgumentException) {
            return broken(infoHash, name, "its torrent file is damaged: ${malformed.message}", directory, paused)
        }
    // The name of the file is a claim about what is inside it, and a claim is checkable. A copy
    // under the wrong name would be opened as a torrent whose resume record belongs to another.
    if (metainfo.infoHash.hex() != infoHash) {
        return broken(
            infoHash,
            name,
            "its torrent file is a different torrent (${metainfo.infoHash.hex()})",
            directory,
            paused,
        )
    }
    return StoredTorrent(infoHash, metainfo, name, directory, paused, unwanted, sequential)
}

private fun broken(
    infoHash: String,
    name: String,
    problem: String,
    directory: String = "",
    paused: Boolean = false,
) = StoredTorrent(infoHash, null, name, directory, paused, emptySet(), false, problem)

/** Enough of the hash to tell two rows apart, for an entry that never got as far as a name. */
private fun shortHash(infoHash: String): String =
    if (infoHash.length <= ENDS * 2) infoHash else "${infoHash.take(ENDS)}…${infoHash.takeLast(ENDS)}"

/**
 * Writes the copy and the choices, both atomically.
 *
 * The `.torrent` goes down first: an entry whose properties exist and whose copy does not is a row
 * that says something is wrong, and an entry with a copy and no properties is invisible. Of the two
 * halves of an interrupted write, the invisible one is the one to be left holding.
 */
internal fun rememberTorrent(
    directory: Path,
    metainfo: Metainfo,
    saveTo: String,
    paused: Boolean = false,
    unwanted: Set<Int> = emptySet(),
    sequential: Boolean = false,
) {
    val infoHash = metainfo.infoHash.hex()
    try {
        Files.createDirectories(directory)
        writeAtomically(directory.resolve("$infoHash$TORRENT_SUFFIX")) {
            it.write(MetainfoWriter.toTorrentFile(metainfo))
        }
        val properties =
            Properties().apply {
                setProperty(NAME, metainfo.name)
                setProperty(DIRECTORY, saveTo)
                setProperty(PAUSED, paused.toString())
                setProperty(SEQUENTIAL, sequential.toString())
                if (unwanted.isNotEmpty()) setProperty(UNWANTED, unwanted.sorted().joinToString(","))
            }
        writeAtomically(directory.resolve("$infoHash$PROPERTIES_SUFFIX")) {
            properties.store(it, "kachok: ${metainfo.name}")
        }
    } catch (unwritable: IOException) {
        // The window goes on working with a list that will not outlive it, and says why on stderr.
        // The same rule the settings file gets: a disk that cannot be written is not a reason to
        // refuse to run.
        System.err.println("kachok: cannot remember ${metainfo.name}: ${unwritable.message}")
    }
}

/** Only the paused flag, for the one thing that changes without anything else changing. */
internal fun rememberPaused(
    directory: Path,
    infoHash: String,
    paused: Boolean,
): Unit = rememberOne(directory, infoHash, PAUSED, paused)

/** And the order, which a person can change while the torrent runs (B-89). */
internal fun rememberSequential(
    directory: Path,
    infoHash: String,
    sequential: Boolean,
): Unit = rememberOne(directory, infoHash, SEQUENTIAL, sequential)

/**
 * One flag of an entry that already exists, rewritten in place.
 *
 * It never creates the file: an entry appears when a torrent is added, and a pause or an order
 * change for a torrent this client does not remember is a race with a removal rather than a torrent
 * to invent.
 */
private fun rememberOne(
    directory: Path,
    infoHash: String,
    key: String,
    value: Boolean,
) {
    val file = directory.resolve("$infoHash$PROPERTIES_SUFFIX")
    try {
        if (!Files.exists(file)) return
        val properties = Properties().apply { Files.newInputStream(file).use { load(it) } }
        if (properties.getProperty(key) == value.toString()) return
        properties.setProperty(key, value.toString())
        writeAtomically(file) { properties.store(it, "kachok") }
    } catch (unwritable: IOException) {
        System.err.println("kachok: cannot record $key for $infoHash: ${unwritable.message}")
    } catch (malformed: IllegalArgumentException) {
        System.err.println("kachok: $file is not a settings file: ${malformed.message}")
    }
}

/** Both files, gone. A torrent removed from the list must not come back on the next start. */
internal fun forgetTorrent(
    directory: Path,
    infoHash: String,
) {
    try {
        Files.deleteIfExists(directory.resolve("$infoHash$PROPERTIES_SUFFIX"))
        Files.deleteIfExists(directory.resolve("$infoHash$TORRENT_SUFFIX"))
    } catch (undeletable: IOException) {
        System.err.println("kachok: cannot forget $infoHash: ${undeletable.message}")
    }
}

/**
 * To a neighbour and moved into place, which is the engine's rule for the resume record.
 *
 * A file half-written by a process that was killed reads as nonsense on the next start; the move is
 * the one operation the filesystem will not do halfway.
 */
private fun writeAtomically(
    file: Path,
    write: (java.io.OutputStream) -> Unit,
) {
    val temporary = file.resolveSibling("${file.fileName}.new")
    Files.newOutputStream(temporary).use(write)
    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

private const val PROPERTIES_SUFFIX = ".properties"
private const val TORRENT_SUFFIX = ".torrent"
private const val NAME = "name"
private const val DIRECTORY = "directory"
private const val PAUSED = "paused"
private const val UNWANTED = "unwanted"
private const val SEQUENTIAL = "sequential"
private const val ENDS = 4
