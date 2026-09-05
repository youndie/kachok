package ru.workinprogress.kachok.engine.storage

import ru.workinprogress.kachok.engine.PieceIndex
import ru.workinprogress.kachok.engine.peer.Block

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

    /** Asks the platform to make what has been written durable. Called on a timer, not per piece. */
    public suspend fun flush()
}
