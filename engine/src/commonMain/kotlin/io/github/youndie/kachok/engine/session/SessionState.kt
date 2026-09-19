package io.github.youndie.kachok.engine.session

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.wire.Handshake
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
    /** Seconds since this client last announced itself to the DHT, or null if it never has. */
    public val dhtAnnouncedSecondsAgo: Long? = null,
    /** Seconds until the next DHT pass, or null when there is not going to be one. */
    public val dhtNextInSeconds: Long? = null,
    public val hashFailures: Int = 0,
    /** Pieces checked so far by the start-up pass, and of how many. Equal when it is finished. */
    public val verifiedPieces: Int = 0,
    public val verifyingOf: Int = 0,
    /** The last tracker complaint, in the tracker's own words, or null. */
    public val trackerError: String? = null,
    /** Why the last dial failed. "No peers, no reason" is a state nobody can act on. */
    public val lastPeerError: String? = null,
    /**
     * Dials this session has started, and how many of them reached a handshake.
     *
     * **Counters and not a rate**, because the question they exist for is asked over a whole run:
     * how much of a swarm this client can actually reach
     * ([B-98](../backlog/B-98-how-many-peers-does-this-client-meet.md)). `connectedPeers` answers
     * "how many now" and says nothing about how many were tried to get there — a client holding
     * five peers after fifty dials and one holding five after six are different clients, and until
     * these existed they looked identical from outside.
     */
    public val dialsAttempted: Long = 0,
    public val dialsHandshaked: Long = 0,
    /**
     * Why dials failed, counted by a short stable label.
     *
     * **Not by message.** A dial failure's message carries the address it failed to reach, so
     * counting messages would produce one bucket per peer and answer nothing. The labels are a
     * closed set chosen to separate the cases that mean different things: a peer that never
     * answered, one that refused, one that answered and then said nothing, and one that answered
     * for a different torrent are four different swarms to be in.
     */
    public val dialFailures: Map<String, Int> = emptyMap(),
    /**
     * Connections that ended, and why — the mirror of [dialFailures], which counts dials that never
     * became connections and is silent about the ones that did.
     *
     * B-105: three hundred handshakes succeeded in a twenty-minute run and twenty-two peers were
     * held at the end of it, with the cap nowhere near. Roughly two hundred and eighty connections
     * ended and the client could not say whether it or the peer had hung up, let alone why.
     */
    public val disconnects: Long = 0,
    public val disconnectReasons: Map<String, Int> = emptyMap(),
    /**
     * What came of each peer that said `interested`, counted when it left: `served`, `unchoked,
     * never asked`, `left choked inside one pass`, `left choked after a pass`. The last two are the
     * ones B-112 asks about — a peer the choke pass never reached, and one it reached and passed
     * over. A peer never interested is not counted; it is most of a swarm.
     */
    public val interestOutcomes: Map<String, Int> = emptyMap(),
    /**
     * Pieces the picker has open, and the mean milliseconds a piece stays open.
     *
     * **These two are the download window**: no more than [startedPieces] pieces are ever in
     * flight, each holds its slot until the writer has hashed it, so the ceiling on throughput is
     * one piece's bytes times the slots divided by that latency. Measured and not derived, because
     * the arithmetic that first suggested it — 61 % of a file in one run and 6 % in the next, with
     * three times the peers — is an inference from two runs and this is the reading that settles it.
     */
    public val startedPieces: Int = 0,
    public val meanPieceMillis: Long = 0,
    /**
     * A loop of the session itself failed. Non-null means the session is degraded and somebody
     * has to look; it exists so that such a failure is a visible state rather than a log line in
     * whatever the platform does with uncaught coroutine exceptions.
     */
    public val sessionError: String? = null,
    public val isComplete: Boolean = false,
    /**
     * Not transferring, on purpose, and still here.
     *
     * The difference from stopped is what survives: the bitfield, the resume record and the
     * torrent's place in the set. A stopped torrent has left the session; a paused one has given up
     * its peers and kept everything it had verified.
     */
    public val paused: Boolean = false,
    /**
     * Pieces are asked for in order rather than rarest first.
     *
     * On the state and not only in the options because it can be changed while the torrent runs
     * ([B-89](../../../../../../../../docs/backlog/B-89-sequential-on-a-running-torrent.md)), and a
     * control that reported what it was *asked* for rather than what the session is doing would
     * disagree with the session the moment a command was refused or lost.
     */
    public val sequential: Boolean = false,
    /**
     * Who is on the other end, one entry per connected peer.
     *
     * Rebuilt on the session's timer and never on the hot path: this is the one field whose cost is
     * proportional to the number of peers, and republishing fifty of them every time a block
     * arrives would be an allocation per block. It is a second behind, which is what a table
     * redrawn once a second wants anyway.
     */
    public val peers: List<PeerView> = emptyList(),
    /**
     * One entry per file in the torrent, with how much of it is verified.
     *
     * Rebuilt on the timer beside [peers], and for the same reason: it is derived from the piece
     * bitfield, so recomputing it whenever a piece landed would be work proportional to the torrent
     * on the hot path. Empty until the first tick, and empty for a magnet with no metainfo yet.
     */
    public val files: List<FileView> = emptyList(),
    /**
     * One entry per announce URL, in the metainfo's order.
     *
     * A tracker that has never been reached is [TrackerView.Status.NotTried] rather than absent:
     * BEP 12 says a client uses the first tracker that answers, so the second and third are
     * normally untouched — and a list that hid them would look like a torrent with one tracker.
     */
    public val trackers: List<TrackerView> = emptyList(),
) {
    override fun toString(): String =
        "$name $completedPieces/$pieceCount pieces, $connectedPeers peers" +
            (trackerError?.let { ", tracker: $it" } ?: "")
}

/**
 * One announce URL, and what it last said.
 *
 * The session announces to the *first* tracker that answers (BEP 12), so at most one of these is
 * [Status.Working] at a time and the rest are usually [Status.NotTried]. That is the engine's
 * behaviour reported honestly rather than a list of three trackers all pretending to be in use.
 */
public class TrackerView(
    public val url: String,
    public val status: Status,
    /** The tracker's complaint, in its own words. Null unless [status] is [Status.Failed]. */
    public val message: String? = null,
    /** Peers the last successful announce returned. */
    public val peers: Int = 0,
    public val lastAnnounceSecondsAgo: Long? = null,
    public val nextAnnounceInSeconds: Long? = null,
) {
    public enum class Status { Working, Failed, NotTried }
}

/**
 * One file in the torrent, and how much of it is on the disk.
 *
 * [verifiedBytes] is derived from the pieces, never counted separately: a second counter per file
 * would be a second thing to get wrong every time a piece lands. A piece that straddles two files
 * credits each with the bytes it actually holds, so a 700-byte file inside a 256 KiB piece is not
 * complete because its neighbour's piece arrived.
 */
public class FileView(
    /** `dists/stable/Release` — the torrent's own path, joined, and never a filesystem path. */
    public val path: String,
    public val length: Long,
    public val verifiedBytes: Long,
    /**
     * Whether this client is fetching it — `priority != SKIP`, kept as its own field because it
     * is the question every reader of this asked before there were three answers
     * ([B-67](../../../../../../../../docs/backlog/B-67-per-file-selection.md)).
     */
    public val wanted: Boolean = true,
    /** Which pool the picker draws this file's pieces from; see [FilePriority]. */
    public val priority: FilePriority = FilePriority.NORMAL,
)

/**
 * How much a file matters, in three steps, which is one more than "wanted".
 *
 * **A tier reorders which pool the picker draws from; rarest-first still decides inside each.**
 * That is the whole design ([B-106](../../../../../../../../docs/backlog/B-106-per-file-priority.md)):
 * the picker's cost was measured rarest-first (research §1.2c) and a strict per-file order is
 * strict sequential with a smaller scope — every peer asks for the same pieces and the client that
 * does it finishes last, which is the argument B-65 already paid for. [HIGH] pieces are offered
 * before the rest and the rarest of them first; [SKIP] is `unwantedFiles` under its real name.
 *
 * A piece that straddles two files takes the higher tier: one shared with a high file is high, one
 * shared with a wanted file is fetched. The swarm serves pieces, not files.
 */
public enum class FilePriority {
    SKIP,
    NORMAL,
    HIGH,
}

/**
 * One connected peer, as far as anything outside the engine is allowed to see it.
 *
 * Plain data for the same reason [SessionState] is: this goes over a socket for the browser build
 * ([B-40]), so a peer is a row and never a handle. A UI holding a `PeerConnection` is a UI that can
 * keep a dead peer alive.
 */
public class PeerView(
    /** `10.0.0.1:6881`. For an accepted connection this is an ephemeral port nobody can dial back. */
    public val address: String,
    /** Whatever the peer id admits to, per BEP 20's convention. A peer is free to lie here. */
    public val client: String,
    /** This client dialled them, rather than the other way round (BEP 11 cares, and so does a reader). */
    public val dialled: Boolean,
    /** They are choking us: nothing can be asked of them except BEP 6's allowed-fast pieces. */
    public val choking: Boolean,
    /** We are choking them. */
    public val choked: Boolean,
    /** We want something they have. */
    public val interested: Boolean,
    /** They want something we have. */
    public val peerInterested: Boolean,
    /** BEP 6 agreed by both sides. */
    public val fast: Boolean,
    /** Whether this connection's bytes are encrypted, rather than merely obfuscated (B-100). */
    public val encrypted: Boolean = false,
    /** BEP 10's handshake arrived, so this peer's extension ids are known. */
    public val extended: Boolean,
    /** Requests sent to this peer and not yet answered. */
    public val outstanding: Int,
    /** Pieces of this torrent they have said they hold. */
    public val pieces: Int,
    public val downBytesPerSecond: Long,
    public val upBytesPerSecond: Long,
)

/** What a caller can ask a running session to do. */
public sealed interface Command {
    /** Peers from somewhere other than the tracker: a magnet's list, an incoming connection, PEX. */
    public class AddPeers(
        public val peers: List<io.github.youndie.kachok.engine.peer.PeerAddress>,
    ) : Command

    /**
     * A peer that dialled *us*, already through its handshake.
     *
     * The listener belongs to the caller — it decides whether to accept at all and on which port —
     * so a connection arrives here the same way a tracker's peers do: through the one door.
     */
    public class AcceptPeer(
        public val connection: io.github.youndie.kachok.engine.peer.PeerConnection,
    ) : Command

    /**
     * Announce `stopped`, close the peers, flush the disk, record — and stay.
     *
     * Everything [Stop] does except the last step. The distinction is the point: the session keeps
     * its bitfield, its picker and its scope, so resuming re-verifies nothing.
     */
    public data object Pause : Command

    /** Announce `started` and start dialling again. A no-op on a session that is not paused. */
    public data object Resume : Command

    /**
     * Read every piece off the disk and hash it, trusting the resume record for nothing.
     *
     * Not a stop and a start: the tracker is never told, because the swarm has no interest in a
     * client checking its own disk. Transfers do stop for the duration — the pass reads the same
     * files the writer appends to — and the torrent goes back to whatever it was doing when the
     * pass finishes.
     */
    public data object Recheck : Command

    /**
     * Ask the trackers again, now.
     *
     * Out of turn: the announce loop's interval is what the tracker asked for, and this is a person
     * overriding it once. It does not reset that interval.
     */
    public data object Announce : Command

    /**
     * New values for the settings a running session can be told to change.
     *
     * The three that can: both rate limits, which are token buckets and take a rate between ticks,
     * and how many peers to keep up, which is read where the next dial is decided. Null leaves a
     * field alone.
     *
     * The listening port is deliberately absent: changing it means re-announcing every torrent
     * under a new address, and the settings screen says so on the row rather than pretending.
     * `pipelineDepth` is absent too — it is the buffer pool's working set, sized when the pool was
     * built, and a session cannot grow the pool it was handed.
     */
    public class Reconfigure(
        public val maxPeers: Int? = null,
        public val uploadLimitBytesPerSecond: Long? = null,
        public val downloadLimitBytesPerSecond: Long? = null,
        /**
         * Ask for pieces in order, or stop asking for them in order.
         *
         * Unlike the three above, this one is **per torrent and not a setting**: it is a decision
         * about the film somebody has started watching, not about how this client behaves. It
         * changes what is chosen *next* and leaves what is already in flight alone — see
         * [B-89](../../../../../../../../docs/backlog/B-89-sequential-on-a-running-torrent.md).
         */
        public val sequential: Boolean? = null,
    ) : Command

    /**
     * One file moves to another tier, on a running torrent.
     *
     * Like [Reconfigure.sequential] it changes what is chosen *next* and leaves what is in flight
     * alone. Raising a file is cheap — its pieces become candidates. Lowering one *to* [FilePriority.SKIP]
     * while its pieces are in flight is the half [B-67](../../../../../../../../docs/backlog/B-67-per-file-selection.md)
     * left out and this leaves out too: the pieces already started finish, and the rest are not
     * asked for. `left` and the file list are republished so the caller sees the new shape at once.
     */
    public class PrioritiseFile(
        /** The file's index in the metainfo. */
        public val file: Int,
        public val priority: FilePriority,
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
    /**
     * Connections to keep up.
     *
     * **250, measured rather than guessed (B-98).** It was 50, and 50 was a placeholder that
     * never bound: against a 526-peer swarm this client held 30. What the runs showed is that
     * the ceiling was never the cap — it was the download window, which is now derived from
     * *this* number, so raising it widens the window with it. The reference client held 190 on
     * the same swarm, which is what 250 leaves room for.
     */
    public val maxPeers: Int = 250,
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
    /**
     * How soon to look again while the client knows fewer addresses than it could hold.
     *
     * A lookup is a snapshot of a network that changes, and a first one taken while the bootstrap
     * nodes were throttling this address is a snapshot of nothing: on the public swarm it left the
     * client with the tracker's one peer for the whole of [dhtInterval], twice in a row. So a
     * lookup that leaves `known` below `maxPeers` is followed by another after this long, doubling
     * each time until it reaches [dhtInterval]; one that leaves the client with more addresses
     * than it can use is kept for the full interval, as before.
     */
    public val dhtStarvedInterval: Duration = 30.seconds,
    /** Where to start from when the routing table is empty. Empty means the DHT is off. */
    public val dhtBootstrap: List<io.github.youndie.kachok.engine.peer.PeerAddress> = emptyList(),
    /**
     * Ask every tracker the torrent names, not only the first that answers.
     *
     * Off, because BEP 12's rule is the first that answers and on a public torrent the trackers
     * mostly hold the same peers — so the default costs one announce and the switch costs as many
     * as the metainfo lists. On for a swarm genuinely split across trackers that do not share,
     * which is the only case the default cannot serve.
     */
    public val announceToAllTrackers: Boolean = false,
    /** Wait before dialling a peer that just failed. The first wait; consecutive failures double it. */
    public val reconnectDelay: Duration = 30.seconds,
    /**
     * The ceiling [reconnectDelay] doubles towards for an address that never answers.
     *
     * **A flat delay is a treadmill, and B-118 measured it.** 65 % of dials on a public swarm end
     * in `connect timed out` (research [D14]) and those addresses stay in `known` for the life of
     * the torrent; with one wait for every failure the session spends its whole dial budget
     * redialling them every `reconnectDelay`, for ever. Three seeding torrents on the author's
     * machine held 726 sockets, 342 of them in `SYN_SENT`, and the host had failed 1.59 M of 1.88 M
     * outgoing connections in 14.8 hours — 37 a second, sustained, against peers that have never
     * once answered.
     *
     * Thirty minutes because a dead address must cost about as much as an announce does — the
     * tracker's own interval is 30 minutes and it is the thing that would tell us about a peer
     * that came back. Doubling from 30 s reaches it on the seventh consecutive failure, so a peer
     * behind a NAT that flickers is still found quickly and one that has been dark for hours is
     * asked twice an hour.
     */
    public val maxReconnectDelay: Duration = 30.minutes,
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
