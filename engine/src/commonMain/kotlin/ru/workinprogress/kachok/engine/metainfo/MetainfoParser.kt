package ru.workinprogress.kachok.engine.metainfo

import ru.workinprogress.kachok.engine.InfoHash
import ru.workinprogress.kachok.engine.bencode.BDictionary
import ru.workinprogress.kachok.engine.bencode.BInteger
import ru.workinprogress.kachok.engine.bencode.BList
import ru.workinprogress.kachok.engine.bencode.BString
import ru.workinprogress.kachok.engine.bencode.Bencode
import ru.workinprogress.kachok.engine.platform.sha1

/**
 * `.torrent` bytes to a [Metainfo] (BEP 3).
 *
 * The info hash is taken over the **source** bytes of the `info` dictionary, sliced with the range
 * the decoder recorded. BEP 3 is explicit that a decode-encode roundtrip is wrong here, and a file
 * whose keys are not in canonical order proves it: such files circulate, and hashing a
 * re-encoding of one produces a torrent nobody in the swarm has heard of.
 */
public object MetainfoParser {
    public fun parse(bytes: ByteArray): Metainfo {
        val root =
            Bencode.decode(bytes) as? BDictionary
                ?: throw MetainfoException("the top-level value is not a dictionary")
        val infoRange =
            root.rangeOf("info")
                ?: throw MetainfoException("no `info` dictionary")
        val info =
            root["info"] as? BDictionary
                ?: throw MetainfoException("`info` is not a dictionary")

        val infoBytes = bytes.copyOfRange(infoRange.first, infoRange.last + 1)
        val infoHash = InfoHash(sha1(infoBytes, 0, infoBytes.size))
        val name = info.text("name", "info")
        val pieceLength = info.integer("piece length", "info")
        if (pieceLength <= 0 || pieceLength > Int.MAX_VALUE) {
            throw MetainfoException("`piece length` is $pieceLength, which is not a usable piece size")
        }
        val pieceHashes =
            (info["pieces"] as? BString)?.bytes
                ?: throw MetainfoException("`info` has no `pieces` byte string")
        if (pieceHashes.size % Metainfo.HASH_SIZE != 0) {
            throw MetainfoException(
                "`pieces` is ${pieceHashes.size} bytes, which is not a multiple of ${Metainfo.HASH_SIZE}",
            )
        }

        val files = readFiles(info, name)
        val totalLength = files.sumOf { it.length }
        if (totalLength <= 0) throw MetainfoException("the torrent is empty: total length $totalLength")

        val expectedPieces = ((totalLength + pieceLength - 1) / pieceLength).toInt()
        val actualPieces = pieceHashes.size / Metainfo.HASH_SIZE
        if (expectedPieces != actualPieces) {
            throw MetainfoException(
                "`pieces` holds $actualPieces hashes but $totalLength bytes at a piece length of " +
                    "$pieceLength need $expectedPieces",
            )
        }

        return Metainfo(
            infoHash = infoHash,
            name = name,
            pieceLength = pieceLength.toInt(),
            totalLength = totalLength,
            files = files,
            pieceHashes = pieceHashes,
            trackers = readTrackers(root),
            isPrivate = (info["private"] as? BInteger)?.value == 1L,
            isSingleFile = info["length"] != null,
            infoBytes = infoBytes,
        )
    }

    /**
     * Single-file and multi-file torrents, into one list with cumulative offsets.
     *
     * `length` and `files` are mutually exclusive: a file carrying both is malformed and, more to
     * the point, ambiguous about its own size.
     */
    private fun readFiles(
        info: BDictionary,
        name: String,
    ): List<TorrentFile> {
        val single = info["length"]
        val multiple = info["files"]
        if (single != null && multiple != null) {
            throw MetainfoException("`info` has both `length` and `files`; it must have exactly one")
        }

        if (single != null) {
            val length =
                (single as? BInteger)?.value
                    ?: throw MetainfoException("`length` is not an integer")
            if (length < 0) throw MetainfoException("`length` is negative: $length")
            return listOf(TorrentFile(listOf(safeComponent(name)), length, 0L))
        }

        val entries =
            (multiple as? BList)?.items
                ?: throw MetainfoException("`info` has neither `length` nor a `files` list")
        var offset = 0L
        return entries.mapIndexed { index, entry ->
            val file =
                entry as? BDictionary
                    ?: throw MetainfoException("`files[$index]` is not a dictionary")
            val length =
                (file["length"] as? BInteger)?.value
                    ?: throw MetainfoException("`files[$index]` has no integer `length`")
            if (length < 0) throw MetainfoException("`files[$index].length` is negative: $length")
            val path =
                (file["path"] as? BList)?.items
                    ?: throw MetainfoException("`files[$index]` has no `path` list")
            if (path.isEmpty()) throw MetainfoException("`files[$index].path` is empty")
            val components =
                path.mapIndexed { at, component ->
                    val text =
                        (component as? BString)?.asString()
                            ?: throw MetainfoException("`files[$index].path[$at]` is not a byte string")
                    safeComponent(text)
                }
            TorrentFile(components, length, offset).also { offset += length }
        }
    }

    /**
     * A path component that cannot escape the download directory.
     *
     * A `.torrent` is a file from a stranger, and its `path` list is the only thing standing
     * between that stranger and an arbitrary write. Refusing the traversal here means no code
     * below has to remember to.
     */
    private fun safeComponent(text: String): String {
        if (text.isEmpty() || text == "." || text == "..") {
            throw MetainfoException("path component '$text' would escape the download directory")
        }
        if (text.any { it in SEPARATORS }) {
            throw MetainfoException("path component '$text' contains a path separator")
        }
        return text
    }

    /**
     * `announce` plus the tiers of `announce-list` (BEP 12), flattened in order and deduplicated.
     *
     * Flattened because phase 1's rule is "try each announce URL in order"; the tier semantics —
     * shuffle within a tier, fall through between them — arrive with the tracker layer if they
     * ever earn their keep. Malformed entries are skipped rather than fatal: a torrent with one
     * unusable tracker out of ten must still load.
     */
    private fun readTrackers(root: BDictionary): List<String> {
        val trackers = LinkedHashSet<String>()
        (root["announce"] as? BString)?.asString()?.takeIf { it.isNotBlank() }?.let { trackers += it }
        (root["announce-list"] as? BList)?.items?.forEach { tier ->
            (tier as? BList)?.items?.forEach { entry ->
                (entry as? BString)?.asString()?.takeIf { it.isNotBlank() }?.let { trackers += it }
            }
        }
        return trackers.toList()
    }

    private fun BDictionary.text(
        key: String,
        where: String,
    ): String =
        (this[key] as? BString)?.asString()
            ?: throw MetainfoException("`$where` has no `$key` byte string")

    private fun BDictionary.integer(
        key: String,
        where: String,
    ): Long =
        (this[key] as? BInteger)?.value
            ?: throw MetainfoException("`$where` has no `$key` integer")

    private val SEPARATORS = charArrayOf('/', '\\')
}
