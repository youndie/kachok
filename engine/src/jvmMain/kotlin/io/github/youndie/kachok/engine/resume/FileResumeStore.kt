package io.github.youndie.kachok.engine.resume

import io.github.youndie.kachok.engine.InfoHash
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * A resume record in a file, replaced atomically.
 *
 * **Temporary file, then `ATOMIC_MOVE`.** Writing in place would leave a window in which the file
 * on disk is neither the old record nor the new one, and a crash inside that window loses a
 * download's progress in exchange for nothing. A rename is the one file operation a filesystem
 * will do all-or-nothing, so the reader sees the old record or the new one and never a half.
 *
 * The temporary file is a sibling of the target rather than in a temporary directory: `ATOMIC_MOVE`
 * across filesystems is not atomic, and on some it is not possible at all.
 *
 * Failing to save is reported, not thrown. A client that stops downloading because it cannot record
 * its progress is worse than one that re-hashes on the next start.
 *
 * **[legacy] is where the record used to be kept**, beside the torrent's files, before B-150 moved
 * it out of the download directory. A record found only there is read, and deleted once the first
 * save at [path] has succeeded — never before, so a failing save leaves the old record where it
 * was rather than leaving no record at all. Only one that decoded as this torrent's is deleted.
 */
public class FileResumeStore(
    private val path: Path,
    private val infoHash: InfoHash,
    private val pieceCount: Int,
    private val dispatcher: CoroutineDispatcher,
    private val legacy: Path? = null,
    private val onFailure: (String) -> Unit = {},
) : ResumeStore {
    /** Set when the record came from [legacy]; the next good save removes it. */
    @Volatile
    private var adopted: Boolean = false

    override suspend fun load(): ResumeRecord? =
        withContext(dispatcher) {
            read(path)
                ?: legacy
                    ?.takeIf { !Files.exists(path) }
                    ?.let(::read)
                    ?.also { adopted = true }
        }

    private fun read(from: Path): ResumeRecord? {
        if (!Files.exists(from)) return null
        return try {
            ResumeRecord.decode(Files.readAllBytes(from), infoHash, pieceCount)
        } catch (unreadable: IOException) {
            onFailure("cannot read $from: ${unreadable.message}")
            null
        } catch (refused: ResumeException) {
            // Kept rather than deleted: a record for another torrent in this directory is the
            // user's business, and deleting somebody else's file is not this client's.
            onFailure("ignoring $from: ${refused.message}")
            null
        }
    }

    override suspend fun save(record: ResumeRecord) {
        withContext(dispatcher) {
            val temporary = path.resolveSibling("${path.fileName}.tmp")
            try {
                Files.createDirectories(path.parent)
                Files.write(temporary, record.encode())
                try {
                    Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (unsupported: AtomicMoveNotSupportedException) {
                    // Some filesystems cannot promise it. Say so rather than pretending: the record
                    // is still written, but a crash mid-move can now leave neither version.
                    onFailure("$path cannot be replaced atomically: ${unsupported.message}")
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
                }
                if (adopted) {
                    legacy?.let(Files::deleteIfExists)
                    adopted = false
                }
            } catch (failed: IOException) {
                onFailure("cannot save $path: ${failed.message}")
                @Suppress(
                    "ktlint:kapkan:cancellation-swallowed",
                    "deleting a temporary file is synchronous: no suspension point, no cancellation",
                )
                runCatching { Files.deleteIfExists(temporary) }.getOrNull()
            }
        }
    }
}
