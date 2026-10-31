package io.github.youndie.kachok.engine.picker

/**
 * The memory a torrent may hold for the pieces it has begun, counted in blocks (B-174).
 *
 * A started piece keeps every block it has received in a pooled buffer until the writer hashes it,
 * so the pieces begun across the whole client are the client's working set of direct memory. The
 * picker asks for a piece's blocks *before* it begins one and gives them back when the piece
 * closes, which is what makes the working set a bound rather than a hope: a piece that was let in
 * always has room for the rest of its blocks.
 */
public interface PieceBudget {
    /** Reserves [blocks] for one piece about to begin, or says there is no room for it now. */
    public fun tryReserve(blocks: Int): Boolean

    /** A piece closed — verified, failed, or forgotten — and its [blocks] are free again. */
    public fun release(blocks: Int)
}
