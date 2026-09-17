package io.github.youndie.kachok.engine.io

import io.github.youndie.kachok.engine.PieceIndex
import java.io.IOException
import java.nio.channels.WritableByteChannel

/**
 * Where a connection gets the bytes it serves.
 *
 * **Every connection has one, and none has a default.** For six milestones `SocketPeerConnection`
 * took a nullable storage that nothing ever passed, and a request it could not serve was dropped
 * without a word — so this client uploaded nothing to anybody, on any surface, while every upload
 * test passed against a fake ([B-110](../../../../../../../../docs/backlog/B-110-this-client-never-uploads-a-block.md)).
 * A connection that cannot serve is not a configuration; the one honest case of it is named below.
 */
public fun interface BlockSource {
    /** Serves one block to [target] and returns the bytes moved. Throws rather than sending less. */
    public fun transferBlock(
        piece: PieceIndex,
        begin: Int,
        length: Int,
        target: WritableByteChannel,
    ): Long
}

/**
 * The connection of a metadata fetch, which holds no pieces by construction.
 *
 * A magnet has not yet become a torrent, so there is no file to serve from and no peer that could
 * legitimately ask. One that asks anyway meets an exception that ends the writer and the
 * connection — the opposite of the silence this replaces, and the only right answer to a request
 * for data that does not exist.
 */
public object NoBlocks : BlockSource {
    override fun transferBlock(
        piece: PieceIndex,
        begin: Int,
        length: Int,
        target: WritableByteChannel,
    ): Long = throw IOException("this connection holds no data to serve: it is fetching metadata")
}
