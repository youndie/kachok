package ru.workinprogress.kachok.engine.storage

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import ru.workinprogress.kachok.engine.PieceIndex
import ru.workinprogress.kachok.engine.metainfo.Metainfo
import ru.workinprogress.kachok.engine.peer.Block

/** What became of a piece once all of its blocks had arrived. */
public sealed interface PieceOutcome {
    public val piece: PieceIndex

    /** Hashed, matched, and on the disk. */
    public class Verified(
        override val piece: PieceIndex,
    ) : PieceOutcome {
        override fun toString(): String = "Verified(${piece.value})"
    }

    /**
     * Hashed and did not match. Nothing was written. Which peers contributed is the session's to
     * know — it routed the blocks — and its to act on.
     */
    public class HashMismatch(
        override val piece: PieceIndex,
    ) : PieceOutcome {
        override fun toString(): String = "HashMismatch(${piece.value})"
    }
}

/**
 * The single writer: blocks in, whole verified pieces out.
 *
 * **One coroutine, and that is the design** (research D4). File I/O does not unmount a virtual
 * thread — the JDK adds a carrier instead — so writing from every peer's coroutine would convert
 * "thousands of peers" into "as many blocked platform threads as the scheduler will make". One
 * writer is one blocked carrier at a time.
 *
 * Being alone buys a second thing the design leans on: a gathering write has to be aimed by moving
 * the channel's position, because the JDK has no `write(ByteBuffer[], long)`. With one writer that
 * is safe; with two it would be a race with no symptom but wrong bytes.
 *
 * Blocks are held in their pooled buffers until their piece completes, so a piece in flight is
 * `pieceLength / 16 KiB` buffers out of the pool. That is why the picker finishes started pieces
 * before beginning new ones — BEP 3's strict priority, here for a memory reason as much as a
 * protocol one.
 */
public class BlockWriter(
    private val metainfo: Metainfo,
    private val hasher: PieceHasher,
    private val storage: Storage,
    queueSize: Int = DEFAULT_QUEUE,
) {
    private val incoming = Channel<Block>(queueSize)
    private val results = Channel<PieceOutcome>(Channel.BUFFERED)

    /** Where peers hand their blocks. Closing it ends [run] once the queue drains. */
    public val blocks: SendChannel<Block> get() = incoming

    /** One entry per completed piece, verified or not. */
    public val outcomes: ReceiveChannel<PieceOutcome> get() = results

    /**
     * Runs until [blocks] is closed. Launch exactly one of these per session; two would be two
     * writers, which is the thing this class is.
     */
    public suspend fun run() {
        val pending = HashMap<Int, MutableList<Block>>()
        try {
            for (block in incoming) {
                val index = block.piece.value
                val gathered = pending.getOrPut(index) { mutableListOf() }
                if (gathered.any { it.begin == block.begin }) {
                    // A duplicate is ordinary in endgame, where the same block is asked of several
                    // peers on purpose. The second copy is a buffer, not information.
                    block.release()
                    continue
                }
                gathered += block
                if (gathered.sumOf { it.length } < metainfo.pieceLengthAt(block.piece)) continue

                pending.remove(index)
                complete(block.piece, gathered.sortedBy { it.begin })
            }
        } finally {
            // Whatever never completed is still holding pool buffers; a session that ends mid-piece
            // must not leave them out on loan.
            pending.values.flatten().forEach { it.release() }
            results.close()
        }
    }

    private suspend fun complete(
        piece: PieceIndex,
        blocks: List<Block>,
    ) {
        try {
            val digest = hasher.hash(blocks)
            if (metainfo.pieceHashMatches(piece, digest)) {
                storage.write(piece, blocks)
                results.send(PieceOutcome.Verified(piece))
            } else {
                // Verify, then write: a corrupt piece never reaches the disk and is never read
                // back to be checked.
                results.send(PieceOutcome.HashMismatch(piece))
            }
        } finally {
            blocks.forEach { it.release() }
        }
    }

    private companion object {
        const val DEFAULT_QUEUE = 256
    }
}
