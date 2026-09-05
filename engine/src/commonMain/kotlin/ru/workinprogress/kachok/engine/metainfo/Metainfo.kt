package ru.workinprogress.kachok.engine.metainfo

import ru.workinprogress.kachok.engine.InfoHash
import ru.workinprogress.kachok.engine.PieceIndex

/** One file of a torrent, at its [offset] in the concatenation of all of them. */
public class TorrentFile(
    public val path: List<String>,
    public val length: Long,
    public val offset: Long,
) {
    override fun toString(): String = "${path.joinToString("/")} ($length bytes at $offset)"
}

/**
 * What a `.torrent` says, and the identity everything else is keyed on.
 *
 * A single-file and a multi-file torrent are one representation: [files] has one entry in the
 * first case. Nothing above this class needs to know which the source was.
 *
 * [pieceHashes] is one array of `20 × pieceCount` bytes rather than a list of arrays. A torrent
 * with a hundred thousand pieces is two megabytes either way, plus a hundred thousand object
 * headers in the list version — the brief's primitive-arrays rule, and the reason the accessors
 * below are shaped to avoid copying on the hot path.
 */
public class Metainfo(
    public val infoHash: InfoHash,
    public val name: String,
    public val pieceLength: Int,
    public val totalLength: Long,
    public val files: List<TorrentFile>,
    public val pieceHashes: ByteArray,
    public val trackers: List<String>,
    public val isPrivate: Boolean,
) {
    public val pieceCount: Int get() = pieceHashes.size / HASH_SIZE

    /** The last piece is short; its length is computed, never read from the file. */
    public fun pieceLengthAt(index: PieceIndex): Int {
        require(index.value in 0 until pieceCount) { "piece ${index.value} is outside 0..${pieceCount - 1}" }
        if (index.value < pieceCount - 1) return pieceLength
        return (totalLength - (pieceCount - 1).toLong() * pieceLength).toInt()
    }

    /** A copy of one piece's expected digest. Not for the hot path — see [pieceHashMatches]. */
    public fun pieceHash(index: PieceIndex): ByteArray {
        require(index.value in 0 until pieceCount) { "piece ${index.value} is outside 0..${pieceCount - 1}" }
        val at = index.value * HASH_SIZE
        return pieceHashes.copyOfRange(at, at + HASH_SIZE)
    }

    /** Compares a freshly computed digest against the expected one without allocating. */
    public fun pieceHashMatches(
        index: PieceIndex,
        digest: ByteArray,
    ): Boolean {
        if (digest.size != HASH_SIZE) return false
        if (index.value !in 0 until pieceCount) return false
        val at = index.value * HASH_SIZE
        for (i in 0 until HASH_SIZE) {
            if (pieceHashes[at + i] != digest[i]) return false
        }
        return true
    }

    public companion object {
        public const val HASH_SIZE: Int = 20
    }
}

/** A `.torrent` this client refuses, with the reason. */
public class MetainfoException(
    message: String,
) : IllegalArgumentException(message)
