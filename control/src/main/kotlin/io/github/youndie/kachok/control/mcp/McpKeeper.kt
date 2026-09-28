package io.github.youndie.kachok.control.mcp

import io.github.youndie.kachok.engine.metainfo.Metainfo
import java.nio.file.Path

/**
 * What an agent changed, told to whoever keeps the torrent list between runs.
 *
 * **The window remembers its torrents and the MCP server did not tell it anything**
 * ([B-133](../../../../../../../../docs/backlog/B-133-sequential-over-mcp-and-the-wire.md)). Attached
 * to a running window (B-117), an agent's `add_torrent` put a torrent into the engine and nowhere
 * else, so the window's next start did not have it; the same went for a pause, a tier, the order and
 * a removal. The server cannot write the window's list itself — that list is the window's, in the
 * window's format — so it says what happened and the host writes it down.
 *
 * Every method is called *after* the engine has been asked, with the decision as it now stands.
 * The defaults do nothing, which is right for a host with no list to keep: `kachok mcp --standalone`
 * holds its torrents for as long as its pipe is open, and not after.
 */
public interface McpKeeper {
    public fun added(
        metainfo: Metainfo,
        directory: Path,
        high: Set<Int>,
        sequential: Boolean,
    ) {}

    public fun removed(infoHash: String) {}

    public fun paused(
        infoHash: String,
        paused: Boolean,
    ) {}

    /** Both sets at once, because one change can move a file between them. */
    public fun priorities(
        infoHash: String,
        unwanted: Set<Int>,
        high: Set<Int>,
    ) {}

    public fun sequential(
        infoHash: String,
        on: Boolean,
    ) {}

    /** Where the torrent's files are now, after a move that succeeded (B-134). */
    public fun moved(
        infoHash: String,
        directory: Path,
    ) {}

    public companion object {
        /** Keeps nothing. */
        public val NOTHING: McpKeeper = object : McpKeeper {}
    }
}
