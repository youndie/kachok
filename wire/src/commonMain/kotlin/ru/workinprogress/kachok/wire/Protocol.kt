package ru.workinprogress.kachok.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a client and the engine say to each other, as plain data.
 *
 * The browser cannot run the engine — it has no TCP or UDP sockets, which is research Risk 4 — so
 * the headless client is the backend and the browser build of the UI is its client
 * ([B-40](../../../../../../../../docs/backlog/B-40-wasmjs-ui-is-a-client-of-the-headless-engine.md)).
 * The desktop build runs the engine in-process and never uses any of this; what makes that possible
 * is that the engine's own API was always a `StateFlow` of plain state plus a channel of commands,
 * so putting it behind a socket is serialisation rather than redesign.
 *
 * **Its own module, and not part of `:engine`.** The engine's classes reach for sockets, files and
 * `MessageDigest`; a browser target that depended on it would be a target with nothing behind it.
 * This module has one dependency, and it is the serialisation format.
 *
 * **Not a copy of `SessionState`.** Two of that class's fields cannot cross a wire at all —
 * `Command.AcceptPeer` carries a live connection — and several of the rest are of no use to a
 * surface. What travels is what a client draws and what a person can ask for.
 */
@Serializable
public class Snapshot(
    /** Every torrent the backend holds, in no particular order; the client sorts. */
    public val torrents: List<TorrentState> = emptyList(),
    /** The port the peer listener actually bound, which is not always the one that was asked for. */
    public val listenPort: Int = 0,
    /** Nodes in the routing table, or null when the DHT was never asked for — a different fact. */
    public val dhtNodes: Int? = null,
    /**
     * Which snapshot this is, counting from one. Not a timestamp.
     *
     * A client wants to know whether what it is holding is older than what just arrived, and a wall
     * clock answers that only when both ends share one — which a protocol whose whole point is that
     * the client may be somewhere else cannot assume. A counter needs no clock, no timezone and no
     * agreement, and it is monotonic by construction.
     */
    public val sequence: Long = 0,
)

/**
 * One torrent, as much of it as a surface draws.
 *
 * Every figure a row or a details panel needs, and nothing else: this is the payload that goes over
 * the socket once a second per torrent, and a field nobody reads is bytes on the wire for ever.
 */
@Serializable
public class TorrentState(
    /** Forty lowercase hex characters. The identity everywhere: rows, commands, resume records. */
    public val infoHash: String,
    public val name: String,
    public val totalLength: Long,
    public val pieceCount: Int,
    public val completedPieces: Int = 0,
    public val downloaded: Long = 0,
    public val uploaded: Long = 0,
    public val left: Long = 0,
    public val connectedPeers: Int = 0,
    public val unchokedPeers: Int = 0,
    public val outstandingRequests: Int = 0,
    public val knownPeers: Int = 0,
    public val hashFailures: Int = 0,
    public val verifiedPieces: Int = 0,
    public val verifyingOf: Int = 0,
    public val downBytesPerSecond: Long = 0,
    public val upBytesPerSecond: Long = 0,
    public val paused: Boolean = false,
    public val isComplete: Boolean = false,
    /** The tracker's own words, not this client's summary of them. */
    public val trackerError: String? = null,
    public val lastPeerError: String? = null,
    /** Non-null means a loop of the session failed and somebody has to look. */
    public val sessionError: String? = null,
    public val files: List<FileState> = emptyList(),
    public val peers: List<PeerState> = emptyList(),
    public val trackers: List<TrackerState> = emptyList(),
)

@Serializable
public class FileState(
    public val path: String,
    public val length: Long,
    /** Verified bytes of *this file*, which is not the pieces that touch it. */
    public val verifiedBytes: Long = 0,
    public val wanted: Boolean = true,
)

@Serializable
public class PeerState(
    public val address: String,
    public val client: String = "",
    public val choking: Boolean = true,
    public val interested: Boolean = false,
    public val downBytesPerSecond: Long = 0,
)

@Serializable
public class TrackerState(
    public val url: String,
    public val status: String,
    public val message: String? = null,
)

/**
 * What a surface can ask of the backend.
 *
 * Deliberately *not* the engine's `Command`: that one carries a live `PeerConnection` in
 * `AcceptPeer`, which is a thing no wire can hold, and it is per session rather than per client.
 * This is the set the desktop toolbar already sends, which is the honest measure of what a surface
 * needs — a second client asking for more than the first one does would be a client that is not
 * this application.
 *
 * `Remove` and `RemoveWithData` are two commands and not one with a flag, because the flag is the
 * difference between "take it off the list" and "delete the files", and a default is how that goes
 * wrong.
 */
@Serializable
public sealed interface Request {
    @Serializable
    @SerialName("pause")
    public class Pause(
        public val infoHash: String,
    ) : Request

    @Serializable
    @SerialName("resume")
    public class Resume(
        public val infoHash: String,
    ) : Request

    @Serializable
    @SerialName("recheck")
    public class Recheck(
        public val infoHash: String,
    ) : Request

    @Serializable
    @SerialName("announce")
    public class Announce(
        public val infoHash: String,
    ) : Request

    @Serializable
    @SerialName("remove")
    public class Remove(
        public val infoHash: String,
    ) : Request

    @Serializable
    @SerialName("removeWithData")
    public class RemoveWithData(
        public val infoHash: String,
    ) : Request

    /**
     * A `.torrent`, as its own bytes.
     *
     * Base64 rather than a path: the client may not be on the machine the backend runs on — that
     * is the whole point of there being a wire — and a path it can see means nothing there.
     */
    @Serializable
    @SerialName("addTorrent")
    public class AddTorrent(
        public val base64: String,
        /** Where to save it, in the backend's own filesystem, or null for its default. */
        public val directory: String? = null,
    ) : Request

    @Serializable
    @SerialName("addMagnet")
    public class AddMagnet(
        public val link: String,
        public val directory: String? = null,
    ) : Request
}

/**
 * What the backend says back.
 *
 * A snapshot is the normal traffic; a `Refused` is the answer to a request the backend would not
 * carry out, and it exists because a client that is told nothing draws a button that did nothing.
 */
@Serializable
public sealed interface Reply {
    @Serializable
    @SerialName("snapshot")
    public class State(
        public val snapshot: Snapshot,
    ) : Reply

    @Serializable
    @SerialName("refused")
    public class Refused(
        public val request: String,
        public val why: String,
    ) : Reply
}
