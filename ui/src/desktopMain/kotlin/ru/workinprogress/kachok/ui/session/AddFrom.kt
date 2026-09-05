package ru.workinprogress.kachok.ui.session

import ru.workinprogress.kachok.engine.InfoHash
import ru.workinprogress.kachok.engine.metainfo.MagnetLink
import ru.workinprogress.kachok.engine.metainfo.Metainfo
import ru.workinprogress.kachok.ui.add.AddFile
import ru.workinprogress.kachok.ui.add.AddTorrentState

/**
 * What the add dialog can say about a `.torrent`.
 *
 * Everything it shows comes out of the parsed metainfo — the size, the piece count, the file list —
 * so the dialog cannot claim a detail the file did not carry. The files are all wanted because the
 * engine downloads all of them; the design badges that list `planned` for the *selection*, which
 * is what does not exist, and not for the list.
 */
internal fun addFrom(
    metainfo: Metainfo,
    fileName: String,
    saveTo: String,
    defaultDirectory: String,
): AddTorrentState =
    AddTorrentState(
        source = fileName,
        summary =
            listOf(
                Figures.bytes(metainfo.totalLength),
                "${grouped(metainfo.pieceCount)} pieces of ${Figures.bytes(metainfo.pieceLength.toLong())}",
                "${metainfo.files.size} ${if (metainfo.files.size == 1) "file" else "files"}",
            ).joinToString(SEPARATOR),
        hash = spacedHash(metainfo.infoHash),
        magnet = false,
        saveTo = saveTo,
        defaultNote = "Default: the folder from Settings — $defaultDirectory",
        files =
            metainfo.files.map { file ->
                AddFile(file.path.joinToString("/"), Figures.bytes(file.length), wanted = true)
            },
        wantedSummary =
            "${metainfo.files.size} of ${metainfo.files.size} wanted" +
                "$SEPARATOR${Figures.bytes(metainfo.totalLength)}",
    )

/**
 * And about a magnet, which is less.
 *
 * A magnet names a torrent and carries none of it (BEP 9): no length, no piece count, no files.
 * The dialog shows the hash where the size would be and says where the rest is coming from,
 * because three blank rows look like a broken dialog and one sentence does not.
 */
internal fun addFrom(
    link: MagnetLink,
    saveTo: String,
    defaultDirectory: String,
): AddTorrentState =
    AddTorrentState(
        // A magnet's `dn` is optional, and without it the hash is the only name the torrent has.
        source = link.displayName ?: shortHash(link.infoHash),
        summary = "No size and no file list yet — the metainfo is fetched from the swarm first.",
        hash = spacedHash(link.infoHash),
        magnet = true,
        saveTo = saveTo,
        defaultNote = "Default: the folder from Settings — $defaultDirectory",
        // Deliberately empty rather than a placeholder row: the file list appears in the details
        // panel once `MetadataFetcher` returns, which is what the design says too.
        files = emptyList(),
        wantedSummary = "",
    )

/**
 * Forty hex characters, in groups of eight.
 *
 * The design breaks its own hash at 20, 16 and 4, which is a mockup's line-wrap rather than a rule;
 * five even groups is what a person can compare against another client's without counting.
 */
private fun spacedHash(infoHash: InfoHash): String =
    infoHash.bytes
        .joinToString("") { (it.toInt() and BYTE).toString(HEX).padStart(2, '0') }
        .chunked(GROUP)
        .joinToString(" ")

/** `e4e5…f6f7`, which is what a row shows too while the metadata is still coming. */
private fun shortHash(infoHash: InfoHash): String =
    spacedHash(infoHash).filterNot { it == ' ' }.let { "${it.take(ENDS)}…${it.takeLast(ENDS)}" }

private fun grouped(value: Int): String =
    value
        .toString()
        .reversed()
        .chunked(THOUSANDS)
        .joinToString(" ")
        .reversed()

private const val SEPARATOR = " · "
private const val BYTE = 0xFF
private const val HEX = 16
private const val GROUP = 8
private const val THOUSANDS = 3
private const val ENDS = 4
