package io.github.youndie.kachok.engine.storage

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * What a torrent wrote, taken off the disk — the one implementation of it (B-191).
 *
 * The window and the MCP server each had their own: the window deleted the files and their parent
 * directory if it was empty, the MCP server the files alone, so a multi-file torrent removed by an
 * agent left its empty folder behind in the save directory. And the window's version was wrong the
 * other way: the parent of a single-file torrent is the save directory itself, which it deleted when
 * the torrent had been the only thing in it.
 */
public object DownloadedFiles {
    /**
     * Deletes [files], then every folder between them and [root] that is empty afterwards.
     *
     * One file at a time: a file the person moved, renamed or already deleted is not a reason to
     * leave the rest. Returns the files that could not be deleted and why, for the caller to say;
     * a missing file is not one of them.
     */
    public fun delete(
        files: List<Path>,
        root: Path,
    ): List<Pair<Path, IOException>> {
        val refused = mutableListOf<Pair<Path, IOException>>()
        files.forEach { file ->
            try {
                Files.deleteIfExists(file)
            } catch (undeletable: IOException) {
                refused += file to undeletable
            }
        }
        pruneEmptyFolders(files, root)
        return refused
    }

    /**
     * The folders [files] sat in, deepest first, up to and **not including** [root], each deleted
     * if it is empty.
     *
     * A multi-file torrent's folder, and any folder inside it, would otherwise stay behind as an
     * empty tree. Only empty ones: a folder that holds something else is somebody's. [root] never:
     * it is where the person chose to save, and a torrent that was the only thing in it does not
     * make it the torrent's. A file outside [root] prunes nothing.
     */
    public fun pruneEmptyFolders(
        files: List<Path>,
        root: Path,
    ) {
        val top = root.toAbsolutePath().normalize()
        files
            .flatMap { file ->
                generateSequence(file.toAbsolutePath().normalize().parent) { it.parent }
                    .takeWhile { it != top && it.startsWith(top) }
            }.distinct()
            .sortedByDescending { it.nameCount }
            .forEach { folder ->
                try {
                    Files.deleteIfExists(folder)
                } catch (kept: IOException) {
                    // Not empty — somebody else's files are in it, and they stay — or not ours to
                    // delete. Either way the files themselves are gone.
                }
            }
    }
}
