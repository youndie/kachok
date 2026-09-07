package io.github.youndie.kachok.engine.storage

import io.github.youndie.kachok.engine.PieceIndex
import io.github.youndie.kachok.engine.peer.Block

/**
 * Where verified pieces go.
 *
 * Only whole pieces, and only verified ones: a piece is hashed from the blocks it arrived in
 * before anything touches the disk (research D4), so this interface never sees a piece it should
 * refuse. Blocks arrive in the buffers they were read into and are not released here — the writer
 * that called this owns them.
 */
public interface Storage {
    /** Writes one whole piece, given its blocks in ascending `begin` order. */
    public suspend fun write(
        piece: PieceIndex,
        blocks: List<Block>,
    )

    /**
     * Reads a whole piece back, in blocks, for hashing.
     *
     * Null when the data is not there to read — a file shorter than the piece, or a store that
     * cannot read. The caller owns the blocks and must release them, exactly as it does for blocks
     * that arrived from a peer: the same pool, the same discipline, so a verification pass costs
     * no more memory than a download does.
     */
    public suspend fun readPiece(piece: PieceIndex): List<Block>?

    /** Asks the platform to make what has been written durable. Called on a timer, not per piece. */
    public suspend fun flush()
}
