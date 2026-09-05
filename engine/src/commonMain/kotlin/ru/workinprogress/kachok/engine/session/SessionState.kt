package ru.workinprogress.kachok.engine.session

import ru.workinprogress.kachok.engine.InfoHash
import ru.workinprogress.kachok.engine.wire.Handshake
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
    /** Connected peers that completed BEP 10's handshake, so their extension ids are known. */
    public val extendedPeers: Int = 0,
    /** Nodes in the DHT routing table. Zero means the DHT is off or has not bootstrapped. */
    public val dhtNodes: Int = 0,
    public val hashFailures: Int = 0,
    /** Pieces checked so far by the start-up pass, and of how many. Equal when it is finished. */
    public val verifiedPieces: Int = 0,
    public val verifyingOf: Int = 0,
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
    /** BEP 3's four regular slots; the optimistic peer takes one of them when it is interested. */
    public val maxUnchoked: Int = 4,
    /** BEP 3: "only changing who's choked once every ten seconds". */
    public val chokeInterval: Duration = 10.seconds,
    /** BEP 3: "which peer is optimistically unchoked rotates every 30 seconds". */
    public val optimisticInterval: Duration = 30.seconds,
    /** BEP 3: "Keepalives are generally sent once every two minutes". */
    public val keepAliveInterval: Duration = 2.minutes,
    /** How often the timer wakes. Everything periodic is a multiple of this. */
    public val tick: Duration = 1.seconds,
    /**
     * `force()` runs on this schedule rather than per piece (research D4).
     *
     * Thirty seconds because the profile shows writes are already rare — twenty sampled
     * `FileWrite` events against 449 pieces, since a piece is one gathering write — so the flush
     * is not what costs anything here. What it bounds is how much of the page cache a crash can
     * take, and the resume record makes that a re-hash rather than a loss (research §1.2c).
     */
    public val flushInterval: Duration = 30.seconds,
    /**
     * How often progress is recorded. Rarely, because the cost of losing the last N seconds of it
     * is a re-hash of those pieces and nothing worse.
     */
    public val resumeInterval: Duration = 60.seconds,
    /**
     * BEP 11: `ut_pex` no more often than this, per peer.
     *
     * A minute because that is the specification's floor and because the message is a delta —
     * sending it faster mostly sends empty dictionaries, and sending it slower makes a new peer
     * wait a minute longer to hear about a swarm this client already knows.
     */
    public val pexInterval: Duration = 60.seconds,
    /**
     * BEP 5: how often to look the torrent up in the DHT and announce this client to it again.
     *
     * Fifteen minutes because a node forgets an announce after a day and because a lookup is
     * dozens of datagrams to strangers — often enough that a client restarted an hour ago is still
     * findable, rare enough that it is not a load on the network.
     */
    public val dhtInterval: Duration = 15.minutes,
    /** Where to start from when the routing table is empty. Empty means the DHT is off. */
    public val dhtBootstrap: List<ru.workinprogress.kachok.engine.peer.PeerAddress> = emptyList(),
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
    /**
     * Bytes a second this client will serve, across every peer. Zero means no limit.
     *
     * One budget for the session and not one per peer: a limit exists because an uplink is shared,
     * and a per-peer limit multiplied by however many peers happen to be unchoked is not a limit.
     */
    public val uploadLimitBytesPerSecond: Long = 0,
    /** Bytes a second this client will ask for, across every peer. Zero means no limit. */
    public val downloadLimitBytesPerSecond: Long = 0,
    /**
     * BEP 10's `m`: the extensions this client offers, and the message id it wants each sent under.
     *
     * Empty in phase 1, and that is not the same as not speaking BEP 10. A peer that gets an empty
     * `m` knows the handshake happened, knows this client's version and port, and knows to send
     * nothing extended — which is exactly right until [B-34](../backlog/B-34-peer-exchange.md) and
     * [B-36](../backlog/B-36-ut-metadata-and-magnets.md) put names in here.
     */
    public val extensions: Map<String, Int> = emptyMap(),
    /** BEP 10's `v`, which is what a peer shows a user about who it is talking to. */
    public val clientVersion: String = "kachok 0.1",
    /**
     * The reserved bytes this client's own handshake carries.
     *
     * The *same array* the dialer and the listener send, not a copy of the decision to send it.
     * BEP 6 and BEP 10 are both two-sided — their messages are legal only when both ends
     * advertised — so the session needs to know what it advertised, and a second boolean saying so
     * is a second place for that fact to be wrong.
     */
    public val reserved: ByteArray = Handshake.reservedBits(),
)
