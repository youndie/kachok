package ru.workinprogress.kachok.engine.session

import ru.workinprogress.kachok.engine.InfoHash
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Everything outside the engine is allowed to know about a session.
 *
 * Plain data on purpose. This is the payload of the `StateFlow` a UI reads, and phase 2 puts that
 * flow behind a socket for the browser build ([B-40]); a field that cannot be serialised — a
 * connection, a channel, a callback — would make that a redesign instead of a serialisation task.
 */
public class SessionState(
    public val infoHash: InfoHash,
    public val name: String,
    public val totalLength: Long,
    public val pieceCount: Int,
    public val completedPieces: Int = 0,
    /** Verified bytes on disk. Not "bytes received": a failed piece is neither. */
    public val downloaded: Long = 0,
    public val uploaded: Long = 0,
    /** Bytes still wanted. BEP 3 is explicit that this is not `total - downloaded` after a resume. */
    public val left: Long = totalLength,
    public val connectedPeers: Int = 0,
    /**
     * Connected peers that are not choking us — the ones that can actually serve a block.
     *
     * Separate from [connectedPeers] because the difference is the commonest reason a download
     * makes no progress, and "50 peers" with no second number tells a user nothing about it.
     */
    public val unchokedPeers: Int = 0,
    /** Requests sent and not yet answered. Zero while peers are connected means a stall. */
    public val outstandingRequests: Int = 0,
    public val knownPeers: Int = 0,
    public val hashFailures: Int = 0,
    /** The last tracker complaint, in the tracker's own words, or null. */
    public val trackerError: String? = null,
    /** Why the last dial failed. "No peers, no reason" is a state nobody can act on. */
    public val lastPeerError: String? = null,
    /**
     * A loop of the session itself failed. Non-null means the session is degraded and somebody
     * has to look; it exists so that such a failure is a visible state rather than a log line in
     * whatever the platform does with uncaught coroutine exceptions.
     */
    public val sessionError: String? = null,
    public val isComplete: Boolean = false,
) {
    override fun toString(): String =
        "$name $completedPieces/$pieceCount pieces, $connectedPeers peers" +
            (trackerError?.let { ", tracker: $it" } ?: "")
}

/** What a caller can ask a running session to do. */
public sealed interface Command {
    /** Peers from somewhere other than the tracker: a magnet's list, an incoming connection, PEX. */
    public class AddPeers(
        public val peers: List<ru.workinprogress.kachok.engine.peer.PeerAddress>,
    ) : Command

    /**
     * A peer that dialled *us*, already through its handshake.
     *
     * The listener belongs to the caller — it decides whether to accept at all and on which port —
     * so a connection arrives here the same way a tracker's peers do: through the one door.
     */
    public class AcceptPeer(
        public val connection: ru.workinprogress.kachok.engine.peer.PeerConnection,
    ) : Command

    /** Announce `stopped`, close the peers, flush, and finish. */
    public data object Stop : Command
}

/**
 * The knobs, with the defaults phase 1 starts from.
 *
 * Every number here is a placeholder until [B-26] measures it, and each says what it trades. They
 * live in one class so that a measurement changes one file and the CLI can expose them as flags
 * without the engine knowing what a flag is.
 */
public class SessionConfig(
    /**
     * Pieces begun at once. Multiplied by the blocks in a piece, this **is** the buffer pool's
     * working set — a pool smaller than that deadlocks the writer, so the two numbers are chosen
     * together.
     */
    public val maxStartedPieces: Int = 8,
    /** Requests kept outstanding per peer. Too few idles the link; too many hold pool buffers. */
    public val pipelineDepth: Int = 16,
    /** Connections to keep up. */
    public val maxPeers: Int = 50,
    /**
     * Peers served at once. BEP 3's reference algorithm unchokes four plus one optimistic; until
     * [B-21] implements the choice, this is the cap on a first-come policy.
     */
    public val maxUnchoked: Int = 5,
    /** BEP 3: "Keepalives are generally sent once every two minutes". */
    public val keepAliveInterval: Duration = 2.minutes,
    /** How often the timer wakes. Everything periodic is a multiple of this. */
    public val tick: Duration = 1.seconds,
    /** `force()` runs on this schedule rather than per piece (research D4). */
    public val flushInterval: Duration = 30.seconds,
    /** Wait before dialling a peer that just failed. */
    public val reconnectDelay: Duration = 30.seconds,
    /**
     * How long a request may go unanswered before the block is offered to somebody else.
     *
     * Not an optimisation. A peer that takes a request and answers nothing holds that block for
     * ever, and a download whose every started piece is held that way stops dead — measured
     * against a real swarm in B-19. Longer than a slow peer's round trip, shorter than a user's
     * patience.
     */
    public val requestTimeout: Duration = 30.seconds,
)
