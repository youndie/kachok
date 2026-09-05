package ru.workinprogress.kachok.engine.storage

import ru.workinprogress.kachok.engine.peer.Block

/**
 * The SHA-1 of a whole piece, computed from the blocks it arrived in.
 *
 * A piece is hashed **before** it is written (research D4), so a corrupt piece never reaches the
 * disk and is never read back to be checked. The blocks are passed as they came off the wire —
 * still in their pooled buffers — because copying them into one array to hash would undo the
 * reason they are pooled.
 *
 * An interface rather than a function so that the platform decides where the work runs: on the JVM
 * that is a dispatcher bounded to the number of processors, sharing carriers with everything else
 * (research D1).
 */
public interface PieceHasher {
    /** Blocks in piece order, contiguous and covering the whole piece. */
    public suspend fun hash(blocks: List<Block>): ByteArray
}
