package io.github.youndie.kachok.engine.resume

import io.github.youndie.kachok.engine.PieceIndex
import io.github.youndie.kachok.engine.metainfo.Metainfo
import io.github.youndie.kachok.engine.picker.Bitfield
import io.github.youndie.kachok.engine.storage.PieceHasher
import io.github.youndie.kachok.engine.storage.Storage

/**
 * What is already on the disk, before a single peer is dialled.
 *
 * Two jobs in one pass, and they are the same job: a resume record says which pieces were verified
 * last time, and every piece it does *not* vouch for is read back and hashed. A directory with a
 * finished download and no record at all is therefore recognised as finished — the record is an
 * optimisation, never the source of truth.
 *
 * **File sizes are not evidence.** A sparse file has its full length from the first write
 * (research §1.3a), so "the file is the right size" says nothing about what is in it. The only
 * thing that answers the question is the hash.
 */
public class StartupVerifier(
    private val metainfo: Metainfo,
    private val storage: Storage,
    private val hasher: PieceHasher,
) {
    /**
     * Returns the pieces this client may claim.
     *
     * [onProgress] is called with the pieces checked so far and the total, because a full check of
     * a large torrent takes minutes and a client that appears frozen during it is a client
     * somebody kills.
     */
    public suspend fun verify(
        record: ResumeRecord?,
        onProgress: (checked: Int, total: Int) -> Unit = { _, _ -> },
    ): Bitfield {
        val verified = Bitfield(metainfo.pieceCount)
        var checked = 0
        (0 until metainfo.pieceCount).forEach { index ->
            val piece = PieceIndex(index)
            if (record != null && record.verified[index]) {
                // Trusted, and this is the whole value of the record: not re-hashing a torrent
                // every time it is opened.
                verified.set(index)
            } else if (hashMatches(piece)) {
                verified.set(index)
            }
            checked++
            onProgress(checked, metainfo.pieceCount)
        }
        return verified
    }

    private suspend fun hashMatches(piece: PieceIndex): Boolean {
        val blocks = storage.readPiece(piece) ?: return false
        return try {
            metainfo.pieceHashMatches(piece, hasher.hash(blocks))
        } finally {
            blocks.forEach { it.release() }
        }
    }
}
