package ru.workinprogress.kachok.engine.resume

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import ru.workinprogress.kachok.engine.InfoHash
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
 */
public class FileResumeStore(
    private val path: Path,
    private val infoHash: InfoHash,
    private val pieceCount: Int,
    private val dispatcher: CoroutineDispatcher,
    private val onFailure: (String) -> Unit = {},
) : ResumeStore {
    override suspend fun load(): ResumeRecord? =
        withContext(dispatcher) {
            if (!Files.exists(path)) return@withContext null
            try {
                ResumeRecord.decode(Files.readAllBytes(path), infoHash, pieceCount)
            } catch (unreadable: IOException) {
                onFailure("cannot read $path: ${unreadable.message}")
                null
            } catch (refused: ResumeException) {
                // Kept rather than deleted: a record for another torrent in this directory is the
                // user's business, and deleting somebody else's file is not this client's.
                onFailure("ignoring $path: ${refused.message}")
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
            } catch (failed: IOException) {
                onFailure("cannot save $path: ${failed.message}")
                runCatching { Files.deleteIfExists(temporary) }.getOrNull()
            }
        }
    }
}
