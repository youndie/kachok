package ru.workinprogress.kachok.engine.metainfo

import ru.workinprogress.kachok.engine.bencode.BList
import ru.workinprogress.kachok.engine.bencode.BString
import ru.workinprogress.kachok.engine.bencode.Bencode

/**
 * A `.torrent` file, written back out of a [Metainfo].
 *
 * A client that is to reopen its torrents after a restart needs each one's metainfo, and the file
 * it came from is not a place to keep it: it may be on a volume that is not mounted, in a downloads
 * folder somebody empties, or nowhere at all — a magnet carries no file, and after BEP 9 the
 * metainfo exists only in memory. So the client keeps its own copy, and this is what writes it.
 *
 * **The info dictionary is spliced in as bytes and never re-encoded.** [Metainfo.infoBytes] is the
 * exact range the info hash was taken over, and the info hash is the torrent's identity — it names
 * the resume record beside the data and it is what a peer asks for in its handshake. Re-encoding
 * would canonicalise the key order, and torrents whose keys are not sorted circulate: for those the
 * file this wrote would parse back as a *different torrent*, orphaning its resume record and its
 * swarm. Splicing costs the surrounding keys having to be in byte order by hand, which they are —
 * `announce` < `announce-list` < `info`.
 */
public object MetainfoWriter {
    /**
     * The bytes of a `.torrent` naming this metainfo and its trackers.
     *
     * Announce and announce-list both, per BEP 12, and neither when there are no trackers: a
     * DHT-only torrent is a torrent, and an empty `announce` would be a tracker URL of `""` that
     * every reader would then try.
     */
    public fun toTorrentFile(metainfo: Metainfo): ByteArray {
        val parts = mutableListOf<ByteArray>()
        parts += OPEN
        metainfo.trackers.firstOrNull()?.let {
            parts += Bencode.encode(BString("announce"))
            parts += Bencode.encode(BString(it))
        }
        if (metainfo.trackers.size > 1) {
            parts += Bencode.encode(BString("announce-list"))
            // One tier holding all of them, which is what "the first that answers" means in BEP 12.
            // Splitting them one per tier would say something the client does not do.
            parts += Bencode.encode(BList(listOf(BList(metainfo.trackers.map { BString(it) }))))
        }
        parts += Bencode.encode(BString("info"))
        parts += metainfo.infoBytes
        parts += CLOSE

        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        parts.forEach { part ->
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    private val OPEN = "d".encodeToByteArray()
    private val CLOSE = "e".encodeToByteArray()
}
