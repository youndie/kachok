package io.github.youndie.kachok.engine.storage

import io.github.youndie.kachok.engine.metainfo.Metainfo
import io.github.youndie.kachok.engine.picker.Bitfield

/**
 * The pieces that can be skipped when some files are not wanted.
 *
 * **A piece is skipped only when every byte of it belongs to an unwanted file.** The swarm serves
 * pieces, not files: a piece straddling a wanted and an unwanted file has to be fetched to get the
 * wanted half, and pretending otherwise is how a download that says 100% is missing its last block.
 * This is the item's own rejected alternative — deleting the unwanted files afterwards — arriving
 * at the same place from the other side: some of those bytes are paid for either way.
 *
 * Returns an empty set of skipped pieces when everything is wanted, so the caller can tell "nothing
 * to skip" from "skip nothing in particular".
 */
public fun unwantedPieces(
    metainfo: Metainfo,
    unwantedFiles: Set<Int>,
): Bitfield {
    val skipped = Bitfield(metainfo.pieceCount)
    if (unwantedFiles.isEmpty()) return skipped
    val pieceLength = metainfo.pieceLength
    // Start from "every piece is skipped" and clear each one a wanted file touches, which is the
    // straddling rule stated the only way that cannot be got wrong by one byte.
    (0 until metainfo.pieceCount).forEach { skipped.set(it) }
    metainfo.files.forEachIndexed { index, file ->
        if (index in unwantedFiles || file.length == 0L) return@forEachIndexed
        val first = (file.offset / pieceLength).toInt()
        val last = ((file.offset + file.length - 1) / pieceLength).toInt()
        (first..last).forEach { if (it < skipped.size) skipped.clear(it) }
    }
    return skipped
}

/**
 * Every piece that holds at least one byte of one of these files.
 *
 * The straddling rule from the other side. [unwantedPieces] skips a piece only when *no* wanted
 * file touches it; this marks a piece the moment *any* named file does — so a piece shared between
 * a high-priority file and an ordinary one is high, and the higher tier wins on the boundary, which
 * is what [B-106](../../../../../../../../docs/backlog/B-106-per-file-priority.md) asks for. The
 * two are not each other's complement: the first is "nobody wants it", the second "somebody wants
 * it first".
 */
public fun piecesOf(
    metainfo: Metainfo,
    files: Set<Int>,
): Bitfield {
    val touched = Bitfield(metainfo.pieceCount)
    if (files.isEmpty()) return touched
    val pieceLength = metainfo.pieceLength
    metainfo.files.forEachIndexed { index, file ->
        if (index !in files || file.length == 0L) return@forEachIndexed
        val first = (file.offset / pieceLength).toInt()
        val last = ((file.offset + file.length - 1) / pieceLength).toInt()
        (first..last).forEach { if (it < touched.size) touched.set(it) }
    }
    return touched
}

/** The bytes of the files this client actually wants, which is what `left` counts down. */
public fun wantedBytes(
    metainfo: Metainfo,
    unwantedFiles: Set<Int>,
): Long =
    metainfo.files
        .filterIndexed { index, _ -> index !in unwantedFiles }
        .sumOf { it.length }
