package io.github.youndie.kachok.engine.session

import io.github.youndie.kachok.engine.PeerId
import io.github.youndie.kachok.engine.PieceIndex
import io.github.youndie.kachok.engine.choke.Choker
import io.github.youndie.kachok.engine.choke.PeerRates
import io.github.youndie.kachok.engine.choke.RateMeter
import io.github.youndie.kachok.engine.choke.TokenBucket
import io.github.youndie.kachok.engine.dht.Dht
import io.github.youndie.kachok.engine.metainfo.Metainfo
import io.github.youndie.kachok.engine.peer.CompactPeers
import io.github.youndie.kachok.engine.peer.PeerAddress
import io.github.youndie.kachok.engine.peer.PeerConnection
import io.github.youndie.kachok.engine.peer.PeerDialer
import io.github.youndie.kachok.engine.peer.PeerEvent
import io.github.youndie.kachok.engine.peer.clientOf
import io.github.youndie.kachok.engine.picker.Bitfield
import io.github.youndie.kachok.engine.picker.PiecePicker
import io.github.youndie.kachok.engine.resume.ResumeRecord
import io.github.youndie.kachok.engine.resume.ResumeStore
import io.github.youndie.kachok.engine.resume.StartupVerifier
import io.github.youndie.kachok.engine.storage.BlockWriter
import io.github.youndie.kachok.engine.storage.PieceHasher
import io.github.youndie.kachok.engine.storage.PieceOutcome
import io.github.youndie.kachok.engine.storage.Storage
import io.github.youndie.kachok.engine.storage.unwantedPieces
import io.github.youndie.kachok.engine.storage.verifiedBytesPerFile
import io.github.youndie.kachok.engine.storage.wantedBytes
import io.github.youndie.kachok.engine.tracker.AnnounceEvent
import io.github.youndie.kachok.engine.tracker.AnnounceRequest
import io.github.youndie.kachok.engine.tracker.TrackerClient
import io.github.youndie.kachok.engine.tracker.TrackerException
import io.github.youndie.kachok.engine.wire.ExtensionHandshake
import io.github.youndie.kachok.engine.wire.Handshake
import io.github.youndie.kachok.engine.wire.Message
import io.github.youndie.kachok.engine.wire.MetadataMessage
import io.github.youndie.kachok.engine.wire.PeerWire
import io.github.youndie.kachok.engine.wire.PexMessage
import io.github.youndie.kachok.engine.wire.WireException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * One torrent, running.
 *
 * The session owns everything with a lifetime: the peers, the tracker loop, the writer, the timer.
 * They are all children of one `SupervisorJob`, so cancelling the session cancels every peer, and
 * one peer failing does not take the others with it.
 *
 * **Two doors, and no others.** A `StateFlow` out — conflated, so a reader sees the latest state
 * and never a backlog of updates — and a `Channel<Command>` in. That pair is the whole API a UI
 * talks to, which is what lets phase 2 put a socket between them ([B-40]) instead of redesigning
 * this class.
 *
 * **One timer for the whole session, never one per peer** (research §1.5, D1). Keep-alives at two
 * minutes, `force()` on its own interval, and later the ten-second choke pass — all of them are
 * session-wide periods, so a thousand peers cost one waking coroutine rather than a thousand.
 */
public class Session(
    public val metainfo: Metainfo,
    private val peerId: PeerId,
    private val listenPort: Int,
    private val dialer: PeerDialer,
    private val trackerClient: TrackerClient,
    private val hasher: PieceHasher,
    private val storage: Storage,
    /**
     * Where progress is recorded, or null for a session that keeps none.
     *
     * Optional because a client that refuses to download when it cannot save its progress is
     * worse than one that re-hashes on the next start.
     */
    private val resume: ResumeStore? = null,
    private val config: SessionConfig = SessionConfig(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /**
     * Where a blocking call may go.
     *
     * The session confines its own state to one thread (see [start]); a dial is the one blocking
     * thing it does itself, and doing that under the confinement would stop the whole session for
     * the length of a TCP timeout. Null means "there is nowhere else", which is what the tests
     * want: a single-threaded test dispatcher is the point of them.
     */
    private val blocking: CoroutineDispatcher? = null,
    /**
     * The DHT, or null for a client that does not speak it.
     *
     * Null and not a flag: a session with no DHT has no socket, no routing table and no bootstrap
     * traffic, which is what "off" has to mean for something that talks to strangers.
     */
    private val dht: Dht? = null,
    /**
     * The one source of chance in the session: the optimistic unchoke, and the first piece.
     *
     * Injectable because BEP 3's optimistic slot is chosen at random among *all* peers, interested
     * or not — so with a seeded source a test can say which peer loses its slot, and without one it
     * can only say that somebody did.
     */
    private val random: Random = Random.Default,
    /**
     * Files this client will not ask for, by their index in [metainfo].
     *
     * The picker is told which *pieces* that makes skippable — only those every byte of which
     * belongs to an unwanted file — and everything else follows: what is announced as `left`, what
     * counts as complete, and what the *Files* tab draws a tick against.
     */
    private val unwantedFiles: Set<Int> = emptySet(),
    /** Ask for pieces in order. Decided when the torrent is opened, like [unwantedFiles]. */
    private val sequential: Boolean = false,
) {
    private val picker = PiecePicker(metainfo, config.maxStartedPieces, random, sequential)
    private val choker = Choker(config.maxUnchoked, random = random)
    private val writer = BlockWriter(metainfo, hasher, storage)
    private val commands = Channel<Command>(Channel.BUFFERED)

    private val mutableState =
        MutableStateFlow(
            SessionState(
                infoHash = metainfo.infoHash,
                name = metainfo.name,
                totalLength = metainfo.totalLength,
                pieceCount = metainfo.pieceCount,
                // From the first state and not from the first restore: a control reading this
                // before the disk check finishes would show the wrong order for as long as the
                // check takes, which on a large torrent is minutes.
                sequential = sequential,
            ),
        )

    /** The only thing outside the engine reads. Conflated: the latest state, not every update. */
    public val state: StateFlow<SessionState> = mutableState.asStateFlow()

    private val connected = LinkedHashMap<PeerAddress, PeerLink>()
    private val known = LinkedHashSet<PeerAddress>()
    private val failed = HashMap<PeerAddress, kotlin.time.TimeMark>()

    /**
     * Peers this client hung up on deliberately — for a pause or a re-check.
     *
     * [serve]'s `finally` records every disconnection in [failed] so that a peer which accepts and
     * immediately hangs up is not redialled in a tight loop. A peer *we* closed is not that, and
     * treating it as one is a race with a visible cost: `resume` and `recheck` clear [failed] and
     * dial, and the `finally` blocks of the peers they just closed are still draining and put every
     * address back — after which nothing dials until the reconnect delay expires.
     *
     * `resume` worked around it by clearing after the announce, which is a bet on how long a
     * `finally` takes. This is the same fix without the bet, and it covers `recheck` too, where the
     * bet was losing: a re-check that found a bad piece then sat there with a connected peer and no
     * requests until the delay ran out.
     */
    private val closedByUs = HashSet<PeerAddress>()

    /**
     * How many peers to keep up, which [Command.Reconfigure] may change.
     *
     * A field rather than `config.maxPeers` because a running session can be told a new number and
     * `SessionConfig` is the number it started with.
     */
    private var maxPeers = config.maxPeers

    private var announceInterval = DEFAULT_ANNOUNCE_SECONDS

    /**
     * What each tracker last said, by its URL.
     *
     * Keyed rather than indexed because the announce list is the metainfo's and this is a record of
     * attempts: a tracker never reached has no entry, which is what [TrackerView.Status.NotTried]
     * is drawn from.
     */
    private val trackerReports = LinkedHashMap<String, TrackerReport>()

    /** When the last DHT pass finished announcing, so the panel can count down to the next. */
    private var dhtAnnouncedAt: kotlin.time.TimeMark? = null

    /** One tracker's last answer: when, what, and how long it asked to be left alone for. */
    private class TrackerReport(
        val at: kotlin.time.TimeMark,
        val failure: String?,
        val peers: Int,
        val intervalSeconds: Long,
    )

    /**
     * The session's two rate limits (B-22). Both are unlimited by default, and an unlimited bucket
     * takes no decision — the code paths below are the same ones an unthrottled client runs.
     */
    private val uploadBudget = TokenBucket(config.uploadLimitBytesPerSecond)
    private val downloadBudget = TokenBucket(config.downloadLimitBytesPerSecond)

    /** Rotated every tick so that a limited uplink is shared rather than taken by whoever asked first. */
    private var uploadTurn = 0
    private var uploadedBytes = 0L
    private var stopping = false

    /**
     * Not transferring, and not gone.
     *
     * Read in four places that would otherwise start work a paused session must not do: dialling a
     * peer, requesting a block, answering an incoming handshake, and telling a tracker or the DHT
     * that this client is here. Each of them is also guarded by [stopping], and the two are not the
     * same condition — a stopped session has left, a paused one is waiting.
     */
    private var paused = false
    private val startedAt = timeSource.markNow()

    /** The only way to change a session from outside. */
    public suspend fun send(command: Command) {
        commands.send(command)
    }

    /**
     * Reads the resume record and checks the disk, before any peer is dialled.
     *
     * Separate from [start] and suspending on purpose: a full check of a large torrent takes
     * minutes, and it must finish before the picker can hand out a single request — a client that
     * announced itself and then discovered it already had half the torrent would have asked the
     * swarm for it first.
     */
    public suspend fun restore(hasher: PieceHasher) {
        verify(resume?.load(), hasher)
    }

    /**
     * The pass itself, shared by the start-up check and [Command.Recheck].
     *
     * The record is the only difference between them: at start-up it says which pieces need not be
     * read, and a re-check passes null because "trust nothing" is the entire reason somebody asked
     * for one.
     */
    private suspend fun verify(
        record: ResumeRecord?,
        hasher: PieceHasher,
    ) {
        // Before the restore, and every time: `skip` refuses a picker that has begun a piece, and
        // `forget` has just emptied it, so a re-check re-applies the same set rather than losing it.
        if (unwantedFiles.isNotEmpty()) picker.skip(unwantedPieces(metainfo, unwantedFiles))
        val verified =
            StartupVerifier(metainfo, storage, hasher).verify(record) { checked, total ->
                publish { it.copy(verifiedPieces = checked, verifyingOf = total) }
            }
        picker.restore(verified)
        val bytes =
            (0 until metainfo.pieceCount)
                .filter { verified[it] }
                .sumOf { metainfo.pieceLengthAt(PieceIndex(it)).toLong() }
        // BEP 3's `left` is what this client still needs, and it does not need the files it is
        // skipping. With nothing skipped this is the torrent's own length, as before.
        val wanted = wantedBytes(metainfo, unwantedFiles)
        publish {
            it.copy(
                completedPieces = verified.cardinality,
                downloaded = bytes,
                left = (wanted - bytes).coerceAtLeast(0),
                uploaded = record?.uploaded ?: 0,
                isComplete = verified.isComplete,
                verifiedPieces = metainfo.pieceCount,
                verifyingOf = metainfo.pieceCount,
                sequential = sequential,
            )
        }
    }

    /**
     * Starts every coroutine of this session as a child of [scope] and returns their parent.
     *
     * Joining the returned job waits for a clean stop: the tracker has heard `stopped`, the peers
     * are closed and the storage is flushed.
     *
     * **[startPaused] is not the same as starting and then pausing.** A torrent restored in a
     * paused state has to come up without touching the swarm at all; starting it normally would
     * announce `started`, dial peers, and then the pause would announce `stopped` and hang all of
     * them up — a round of churn on every restart, per paused torrent, over a decision that was
     * taken before the process began. The flag is set before the loops launch, and
     * [announceLoop] already says nothing while it is true.
     */
    public fun start(
        scope: CoroutineScope,
        startPaused: Boolean = false,
    ): Job {
        if (startPaused) {
            paused = true
            publish { it.copy(paused = true) }
        }
        val sessionJob = SupervisorJob(scope.coroutineContext[Job])
        // **One thread for the session's own state, and this is not an optimisation.**
        //
        // The peer table, the picker and every `PeerLink` are ordinary mutable structures with no
        // locks, touched by the timer, the tracker loop, the writer's outcomes and one coroutine
        // per peer. The engine's dispatcher is a virtual-thread-per-task executor, which runs all
        // of those on as many carriers as the machine has — so "ordinary mutable structure" meant
        // "data race". Against a real swarm it surfaced as a `NullPointerException` reading a
        // `LinkedHashMap` another thread was writing; the single-threaded test dispatcher had
        // hidden it completely.
        //
        // `limitedParallelism(1)` makes the session one logical actor again, and costs nothing:
        // its work is bookkeeping, and everything expensive — sockets, hashing, the disk — already
        // runs elsewhere. Nothing that blocks may run here; see [blocking].
        val current = scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher
        val confined = current?.limitedParallelism(1)
        val sessionScope =
            CoroutineScope(
                scope.coroutineContext + sessionJob +
                    (confined ?: kotlin.coroutines.EmptyCoroutineContext),
            )

        sessionScope.launchGuarded("writer") { writer.run() }
        sessionScope.launchGuarded("outcomes") { consumeOutcomes(sessionScope) }
        sessionScope.launchGuarded("tracker") { announceLoop(sessionScope) }
        if (dht != null && !metainfo.isPrivate && config.dhtBootstrap.isNotEmpty()) {
            sessionScope.launchGuarded("dht") { dhtLoop(sessionScope) }
        }
        sessionScope.launchGuarded("timer") { timerLoop() }
        sessionScope.launchGuarded("commands") { commandLoop(sessionScope, sessionJob) }
        return sessionJob
    }

    private suspend fun commandLoop(
        scope: CoroutineScope,
        sessionJob: Job,
    ) {
        for (command in commands) {
            when (command) {
                is Command.AddPeers -> {
                    known += command.peers
                    publish { it.copy(knownPeers = known.size) }
                    connectMore(scope)
                }

                is Command.AcceptPeer -> {
                    val address = command.connection.address
                    if (paused || address in connected) {
                        // Already talking to them, from our side — or paused, in which case the
                        // announce said `stopped` and answering anyway would contradict it.
                        command.connection.close()
                    } else {
                        known += address
                        scope.launch { serve(scope, command.connection, dialled = false) }
                    }
                }

                Command.Pause -> {
                    pause()
                }

                Command.Resume -> {
                    resume(scope)
                }

                Command.Recheck -> {
                    recheck(scope)
                }

                is Command.Reconfigure -> {
                    command.maxPeers?.let { maxPeers = it }
                    // **What is already asked for is left alone**, and that is the decision this
                    // command took. Cancelling the outstanding requests is a `Cancel` per peer per
                    // block and a swarm asked for the same work twice; letting them land costs one
                    // pipeline's worth of pieces in the old order — a second or two of mixture at
                    // the front, and then the order somebody asked for. Nothing already verified is
                    // touched either way.
                    command.sequential?.let {
                        picker.sequential = it
                        publish { state -> state.copy(sequential = it) }
                    }
                    command.uploadLimitBytesPerSecond?.let { uploadBudget.retune(it) }
                    command.downloadLimitBytesPerSecond?.let { downloadBudget.retune(it) }
                    // Both of these are wake-ups, and both are necessary. Raising the peer count
                    // matters only if somebody dials, and the loop that would is the one that runs
                    // when a peer drops — an hour away. Raising a download limit is worse: requests
                    // are normally issued when a block arrives, no block arrives while nothing is
                    // asked for, and `refillRateLimits` — which exists to break exactly that
                    // circle — returns immediately once there is no limit left to refill. A
                    // session throttled to a standstill and then unthrottled stayed at a
                    // standstill, which is what the test for this found.
                    connectMore(scope)
                    connected.snapshot().forEach { requestMore(it) }
                }

                Command.Announce -> {
                    // Straight away and out of turn: the loop's own interval is the tracker's
                    // request, and this is a person overriding it once.
                    val peers = announce(null)
                    if (peers.isNotEmpty()) {
                        known += peers
                        publish { it.copy(knownPeers = known.size) }
                        connectMore(scope)
                    }
                }

                Command.Stop -> {
                    shutDown()
                    sessionJob.cancel()
                    return
                }
            }
        }
    }

    /**
     * Check the disk again, from nothing.
     *
     * **Transfers stop; the session does not.** The pass reads the same files the writer appends
     * to, so the peers go for the duration — but the tracker is never told, because a `stopped`
     * followed by a `started` for a disk check is announce churn about something the swarm cannot
     * act on, and it is why the item rejected doing this as stop-recheck-start.
     *
     * **Off the confined dispatcher.** Hashing a large torrent is minutes of blocking reads, and
     * under the session's `limitedParallelism(1)` that would stop the timer, the tracker and every
     * peer coroutine along with it. The same escape a dial uses.
     *
     * A torrent that was paused before is still paused after: a re-check is a question, not a
     * decision to start.
     */
    private suspend fun recheck(scope: CoroutineScope) {
        if (stopping) return
        val wasPaused = paused
        paused = true
        publish {
            it.copy(paused = true, connectedPeers = 0, unchokedPeers = 0, outstandingRequests = 0)
        }
        closedByUs += connected.keys
        connected.snapshot().forEach { it.connection.close() }
        connected.clear()
        storage.flush()
        // The picker refuses to be restored into while it is in use, and rightly: at start-up that
        // guard catches a check running after the first request went out. A re-check is the one
        // caller that legitimately empties it first — every peer is closed by the lines above, so
        // there is nothing in flight to hand out twice.
        picker.forget()
        if (blocking != null) {
            kotlinx.coroutines.withContext(blocking) { verify(record = null, hasher = hasher) }
        } else {
            verify(record = null, hasher = hasher)
        }
        // Recorded straight away: the pass just spent minutes learning what is on the disk, and
        // losing that to a crash would mean spending them again.
        saveResume()
        if (!wasPaused) {
            paused = false
            publish { it.copy(paused = false) }
            failed.clear()
            connectMore(scope)
            // **Every peer that survived the pass is told again what this client wants.**
            //
            // A re-check changes `have` underneath connections that are already open — a dial in
            // flight when the pass began completes during it, and the peers closed above are not
            // the peers connected now. Interest is otherwise only recomputed when *their* bitfield
            // changes, never when ours shrinks, so a peer that connected while this client was
            // complete stays uninterested and is never asked for the piece the pass just threw
            // away. The symptom is a torrent stuck one piece short with a connected, unchoking peer
            // and no outstanding requests — reproducible on Windows, intermittent everywhere else.
            connected.snapshot().forEach { link ->
                updateInterest(link)
                requestMore(link)
            }
        }
    }

    /**
     * [shutDown] without the leaving.
     *
     * The state is published *first*, before the announce: a `stopped` announce is a request to
     * somebody else's server and can take seconds, and a row that keeps saying *Downloading* for
     * that long after the button was pressed is the defect the button was fixed to stop having.
     *
     * The counters are zeroed with it rather than left to drift down as the peers close, so the
     * row does not spend those seconds claiming peers it has already hung up on.
     */
    private suspend fun pause() {
        if (paused || stopping) return
        paused = true
        publish {
            it.copy(paused = true, connectedPeers = 0, unchokedPeers = 0, outstandingRequests = 0)
        }
        announce(AnnounceEvent.STOPPED)
        closedByUs += connected.keys
        connected.snapshot().forEach { it.connection.close() }
        connected.clear()
        storage.flush()
        // Same order as the stop, and for the same reason: the record vouches for what is on the
        // disk, so it is written after the flush and never before.
        saveResume()
    }

    /**
     * Back on: `started`, and dial.
     *
     * [failed] is cleared because pausing filled it — every peer this session hung up on was
     * recorded as a failure by [serve]'s `finally`, and without this a resume would sit through the
     * reconnect delay before touching a swarm it was talking to a moment ago.
     *
     * It used to matter that this happened *after* the announce: the `finally` blocks were still
     * draining and would undo a clear made at the top. [closedByUs] removes that bet — a peer this
     * client hung up on is never recorded as a failure in the first place — and the ordering here
     * is now only about announcing before dialling.
     */
    private suspend fun resume(scope: CoroutineScope) {
        if (!paused || stopping) return
        paused = false
        publish { it.copy(paused = false) }
        val peers = announce(AnnounceEvent.STARTED)
        if (peers.isNotEmpty()) {
            known += peers
            publish { it.copy(knownPeers = known.size) }
        }
        // Everything this pause closed is exempt through [closedByUs]; this is for peers that had
        // genuinely failed before it, which a person pressing *Resume* is entitled to have retried.
        failed.clear()
        connectMore(scope)
    }

    /**
     * The order matters and is the whole of the graceful stop: the tracker hears `stopped` while
     * the peers are still up, the peers are closed, and only then is the data made durable.
     */
    private suspend fun shutDown() {
        stopping = true
        announce(AnnounceEvent.STOPPED)
        connected.snapshot().forEach { it.connection.close() }
        connected.clear()
        writer.blocks.close()
        storage.flush()
        // Last, and the order is the whole of it: the record vouches for pieces that are hashed
        // *and* on the disk, so it is written after the flush and never before.
        saveResume()
    }

    /**
     * Records what is verified, never what is merely written.
     *
     * `force()` runs on a timer, so a crash can lose what the page cache still held; a record that
     * vouched for a written piece would send this client back to a swarm claiming a piece it does
     * not have. Under-claiming costs a re-hash and nothing else.
     */
    private suspend fun saveResume() {
        val store = resume ?: return
        val snapshot = mutableState.value
        store.save(
            ResumeRecord(
                infoHash = metainfo.infoHash,
                verified = picker.completed,
                uploaded = snapshot.uploaded,
                downloaded = snapshot.downloaded,
            ),
        )
    }

    private suspend fun announceLoop(scope: CoroutineScope) {
        var event: AnnounceEvent? = AnnounceEvent.STARTED
        while (scope.isActive && !stopping) {
            // A paused session has told this tracker `stopped`; the loop keeps its interval and
            // says nothing until it is resumed, which does its own `started` announce.
            if (paused) {
                delay(announceInterval * MILLIS_PER_SECOND)
                continue
            }
            val peers = announce(event)
            event = null
            if (peers.isNotEmpty()) {
                known += peers
                publish { it.copy(knownPeers = known.size) }
                connectMore(scope)
            }
            delay(announceInterval * MILLIS_PER_SECOND)
        }
    }

    /**
     * BEP 5: find the torrent in the DHT, and say this client has it.
     *
     * A loop and not a one-off. A lookup is a snapshot of a network that changes, an announce is
     * forgotten after a day, and a client that did both once at start-up would be unfindable an
     * hour later — which is exactly the case a trackerless torrent depends on.
     *
     * **Not for a private torrent** (BEP 27), and the check is at the door in [start] rather than
     * here: the difference is a session that never opens a socket.
     */
    private suspend fun dhtLoop(scope: CoroutineScope) {
        val node = dht ?: return
        node.bootstrap(scope, config.dhtBootstrap)
        while (!stopping) {
            // Announcing to the DHT is saying "this client has it and will serve it", which a
            // paused one will not.
            if (paused) {
                delay(config.dhtInterval)
                continue
            }
            tick("dht lookup") {
                val found = node.lookup(scope, metainfo.infoHash)
                if (found.peers.isNotEmpty()) {
                    val before = known.size
                    found.peers.forEach { known += it }
                    if (known.size != before) {
                        publish { it.copy(knownPeers = known.size) }
                        connectMore(scope)
                    }
                }
                node.announce(scope, metainfo.infoHash, listenPort, found.tokens)
                dhtAnnouncedAt = timeSource.markNow()
                publish { it.copy(dhtNodes = node.table.size) }
            }
            delay(config.dhtInterval)
        }
    }

    /** Every tracker in order until one answers; a dead tracker is not a dead swarm. */
    private suspend fun announce(event: AnnounceEvent?): List<PeerAddress> {
        val snapshot = mutableState.value
        val request =
            AnnounceRequest(
                infoHash = metainfo.infoHash,
                peerId = peerId,
                port = listenPort,
                uploaded = uploadedBytes,
                downloaded = snapshot.downloaded,
                left = snapshot.left,
                event = event,
            )
        var lastError: String? = null
        metainfo.trackers.forEach { tracker ->
            try {
                val response = trackerClient.announce(tracker, request)
                announceInterval = response.interval.coerceAtLeast(MIN_ANNOUNCE_SECONDS)
                trackerReports[tracker] =
                    TrackerReport(timeSource.markNow(), failure = null, response.peers.size, announceInterval.toLong())
                publish { it.copy(trackerError = null, trackers = trackerViews()) }
                return response.peers
            } catch (refused: TrackerException) {
                lastError = refused.message
                trackerReports[tracker] =
                    TrackerReport(
                        timeSource.markNow(),
                        refused.message,
                        peers = 0,
                        intervalSeconds = announceInterval.toLong(),
                    )
            }
        }
        publish { it.copy(trackerError = lastError, trackers = trackerViews()) }
        return emptyList()
    }

    /**
     * Every announce URL the torrent names, with whatever is known about it.
     *
     * In the metainfo's order and never in the order things were tried: the list is the torrent's,
     * and a row that moved when a tracker failed would be a list nobody could read twice.
     */
    private fun trackerViews(): List<TrackerView> =
        metainfo.trackers.map { url ->
            val report = trackerReports[url]
            when {
                report == null -> {
                    TrackerView(url, TrackerView.Status.NotTried)
                }

                report.failure != null -> {
                    TrackerView(
                        url = url,
                        status = TrackerView.Status.Failed,
                        message = report.failure,
                        lastAnnounceSecondsAgo = report.at.elapsedNow().inWholeSeconds,
                    )
                }

                else -> {
                    TrackerView(
                        url = url,
                        status = TrackerView.Status.Working,
                        peers = report.peers,
                        lastAnnounceSecondsAgo = report.at.elapsedNow().inWholeSeconds,
                        nextAnnounceInSeconds =
                            (report.intervalSeconds - report.at.elapsedNow().inWholeSeconds)
                                .coerceAtLeast(0),
                    )
                }
            }
        }

    private fun connectMore(scope: CoroutineScope) {
        if (paused) return
        val room = maxPeers - connected.size
        if (room <= 0) return
        known
            .asSequence()
            .filter { it !in connected }
            .filter { address ->
                failed[address]?.let { it.elapsedNow() >= config.reconnectDelay } ?: true
            }.take(room)
            .toList()
            .forEach { address -> scope.launch { runPeer(scope, address) } }
    }

    /** One coroutine per peer, from the dial to the last event. */
    private suspend fun runPeer(
        scope: CoroutineScope,
        address: PeerAddress,
    ) {
        val connection =
            try {
                // Off the confined dispatcher: a dial blocks for up to the connect timeout, and
                // under confinement that would stop every other peer, the timer and the tracker
                // along with it.
                if (blocking != null) {
                    kotlinx.coroutines.withContext(blocking) { dialer.connect(address) }
                } else {
                    dialer.connect(address)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // A blanket `catch (Exception)` around a suspending call swallows cancellation as
                // well, and a peer that cannot be cancelled outlives the session it belongs to.
                // Rethrow it first, always.
                throw cancelled
            } catch (refused: Exception) {
                failed[address] = timeSource.markNow()
                // Kept and published: "no peers, no reason" is a state nobody can act on.
                publish {
                    it.copy(lastPeerError = "$address: ${refused.message ?: refused::class.simpleName}")
                }
                return
            }
        serve(scope, connection, dialled = true)
    }

    /**
     * One coroutine per connection, however it was made.
     *
     * A peer that dialled us and a peer we dialled differ only in who spoke first; from here they
     * are the same thing, which is why the accepting path adds no state machine of its own.
     */
    private suspend fun serve(
        scope: CoroutineScope,
        connection: PeerConnection,
        dialled: Boolean,
    ) {
        val address = connection.address
        val link = PeerLink(connection)
        // Which side dialled decides what BEP 11 may say about this peer: the address an accepted
        // connection came from is an ephemeral port, not one anybody can dial back.
        link.dialled = dialled
        connected[address] = link
        picker.addPeer(address)
        publish { it.copy(connectedPeers = connected.size) }
        try {
            // BEP 10: the extension handshake goes first, before the bitfield, and only to a peer
            // whose reserved bits asked for it. Sending id 20 to a peer that never advertised the
            // bit is an unknown message id, which BEP 3 clients close the connection over.
            if (connection.handshake.supportsExtensionProtocol) {
                link.send(Message.Extended(ExtensionHandshake.HANDSHAKE_ID, ourExtensionHandshake().encode()))
            }
            link.fast = ourFastExtension && connection.handshake.supportsFastExtension
            // BEP 6: on a fast connection the first message is one of these three and is never
            // omitted. `have all` and `have none` exist because the alternative is 250 KiB of ones
            // or of zeros for a torrent with two million pieces.
            when {
                !link.fast -> {
                    if (picker.completed.cardinality > 0) {
                        link.send(Message.Bitfield(picker.completed.toBytes()))
                    }
                }

                picker.isComplete -> {
                    link.send(Message.HaveAll)
                }

                picker.completed.cardinality == 0 -> {
                    link.send(Message.HaveNone)
                }

                else -> {
                    link.send(Message.Bitfield(picker.completed.toBytes()))
                }
            }
            for (event in connection.events) {
                handle(scope, link, event) ?: break
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // One peer must never take anything else down. Under the session's SupervisorJob a
            // throw here would reach the exception handler as an uncaught failure — visible in a
            // test as "uncaught exceptions before the test started", and in production as a log
            // line nobody can attribute. The peer is dropped and the reason is published.
            publish { it.copy(lastPeerError = "$address: ${failure.message ?: failure::class.simpleName}") }
        } finally {
            connection.close()
            // **Only if this link is still the registered one.**
            //
            // Everything below is keyed by address, and a reconnect to the same address installs a
            // new link, a new picker entry and a new bitfield. If this teardown then runs — the old
            // coroutine finishing after the new one started, which is exactly what a pause or a
            // re-check causes — it removes state the new connection is relying on. The symptom is a
            // peer that is connected and unchoked with the picker holding no bitfield for it, so
            // `next` returns nothing and the torrent asks for nothing, for ever. Found on Windows,
            // where the reconnect always wins the race; on Linux it wins sometimes.
            if (connected[address] === link) {
                connected.remove(address)
                picker.removePeer(address)
                // The same wait as after a failed dial, and for a stronger reason: a peer that
                // accepts and immediately hangs up would otherwise be redialled in a tight loop,
                // which is a busy wait against somebody else's machine as well as our own. A peer
                // this client hung up on for a pause or a re-check is not that, and must not be
                // made to serve the delay.
                if (!closedByUs.remove(address)) failed[address] = timeSource.markNow()
                publish { it.copy(connectedPeers = connected.size) }
                if (!stopping && !paused && scope.isActive) connectMore(scope)
            }
        }
    }

    /** Null means the connection is over. */
    private suspend fun handle(
        scope: CoroutineScope,
        link: PeerLink,
        event: PeerEvent,
    ): Unit? {
        val address = link.connection.address
        when (event) {
            is PeerEvent.Closed -> {
                return null
            }

            is PeerEvent.BlockReceived -> {
                link.outstanding--
                val block = event.block
                link.download.add(block.length.toLong(), elapsedMillis())
                picker.blockReceived(address, block.piece, block.begin).forEach { other ->
                    connected[other]?.send(Message.Cancel(block.piece, block.begin, block.length))
                }
                writer.blocks.send(block)
                requestMore(link)
            }

            is PeerEvent.Received -> {
                when (val message = event.message) {
                    Message.Choke -> {
                        link.choked = true
                        link.outstanding = 0
                        picker.requestsDropped(address)
                    }

                    Message.Unchoke -> {
                        link.choked = false
                        requestMore(link)
                    }

                    is Message.Bitfield -> {
                        picker.setBitfield(address, message.bits)
                        updateInterest(link)
                    }

                    is Message.Have -> {
                        picker.addHave(address, message.piece)
                        updateInterest(link)
                    }

                    is Message.Request -> {
                        serveRequest(link, message)
                    }

                    // Interest changes what the next choke pass will decide; it does not unchoke
                    // anyone on its own. The algorithm runs on the timer, not on the peer's word.
                    Message.Interested -> {
                        link.peerInterested = true
                    }

                    Message.NotInterested -> {
                        link.peerInterested = false
                    }

                    is Message.Extended -> {
                        receiveExtended(scope, link, message)
                    }

                    Message.HaveAll -> {
                        picker.setBitfield(address, everyPiece())
                        updateInterest(link)
                        requestMore(link)
                    }

                    Message.HaveNone -> {
                        picker.setBitfield(address, ByteArray((metainfo.pieceCount + 7) / 8))
                        updateInterest(link)
                    }

                    is Message.Reject -> {
                        // The whole point of BEP 6: the block is free now rather than in thirty
                        // seconds, and somebody else can be asked for it on this pass.
                        picker.requestRejected(address, message.piece, message.begin)
                        if (link.outstanding > 0) link.outstanding--
                        requestMore(link)
                    }

                    is Message.Suggest -> {
                        // BEP 6: a peer only suggests a piece it has. Honoured as a `have` and not
                        // as a preference — the picker orders by rarity and by what is already
                        // started, and putting one peer's hint above both needs a rule for two
                        // peers suggesting different pieces that this item has no data for.
                        picker.addHave(address, message.piece)
                        updateInterest(link)
                    }

                    is Message.AllowedFast -> {
                        link.allowedFast += message.piece.value
                        if (link.choked) requestMore(link)
                    }

                    // `cancel` is honoured by the connection's queue order; a block already handed
                    // to the writer is on its way out. Dropping a queued one arrives with B-33's
                    // reject, which is where the bookkeeping to do it properly lives.
                    else -> {}
                }
            }
        }
        return Unit
    }

    /**
     * BEP 6: a choke kills every request this peer had outstanding with us, and each one is said
     * out loud.
     *
     * Without the extension the peer learns it from a timeout, thirty seconds later, and cannot
     * tell "choked" from "went away". The queue here is the one the upload limit builds; with no
     * limit a request is answered as it arrives and there is nothing left to reject, which is the
     * same statement about outstanding work rather than a different one.
     */
    private suspend fun rejectWaiting(link: PeerLink) {
        if (!link.fast) {
            link.waiting.clear()
            return
        }
        while (link.waiting.isNotEmpty()) {
            val dropped = link.waiting.removeFirst()
            link.send(Message.Reject(dropped.piece, dropped.begin, dropped.length))
        }
    }

    /**
     * BEP 9 from the other side: a peer asks this client for the info dictionary.
     *
     * The bytes are the ones the torrent arrived with — `metainfo.infoBytes`, a slice of the file
     * or of what the swarm sent — and never a re-encode. The info hash is the SHA-1 of exactly
     * those bytes, and a torrent whose keys are not canonically sorted would re-encode into
     * something with a different hash: well formed, and the wrong answer to the question asked.
     *
     * Served from the moment the torrent is open, not from the moment it completes. The dictionary
     * is whole either way; it is the pieces that are missing.
     */
    private suspend fun serveMetadata(
        link: PeerLink,
        payload: ByteArray,
    ) {
        val request =
            try {
                MetadataMessage.decode(payload)
            } catch (malformed: WireException) {
                return
            }
        if (request.type != MetadataMessage.REQUEST) return
        val total = metainfo.infoBytes.size
        val from = request.piece.toLong() * MetadataMessage.BLOCK_SIZE
        if (request.piece < 0 || from >= total) {
            link.send(Message.Extended(METADATA_ID, MetadataMessage.reject(request.piece).encode()))
            return
        }
        val to = minOf(from + MetadataMessage.BLOCK_SIZE, total.toLong()).toInt()
        val block = metainfo.infoBytes.copyOfRange(from.toInt(), to)
        // Through the upload budget, so a peer cannot ask for the same block a thousand times to
        // get around a rate limit. BEP 9 gives `reject` for exactly this: a refusal that says so.
        if (!uploadBudget.take(block.size.toLong())) {
            link.send(Message.Extended(METADATA_ID, MetadataMessage.reject(request.piece).encode()))
            return
        }
        link.send(
            Message.Extended(METADATA_ID, MetadataMessage.data(request.piece, total, block).encode()),
        )
    }

    /**
     * A peer told us about peers.
     *
     * Treated exactly like a tracker's answer, which is what it is: addresses go into the same
     * `known` set and are dialled by the same rule. `dropped` is not acted on — a peer this client
     * is connected to and enjoying is not dropped because somebody else lost it.
     */
    private fun receivePex(
        scope: CoroutineScope,
        link: PeerLink,
        payload: ByteArray,
    ) {
        val message =
            try {
                PexMessage.decode(payload)
            } catch (malformed: WireException) {
                publish { it.copy(lastPeerError = "${link.connection.address}: ${malformed.message}") }
                return
            }
        val before = known.size
        // Bounded: `known` is fed by anything that can talk to us, and a peer sending a megabyte of
        // addresses should cost this client one message's worth and not a growing set.
        message.added.take(PexMessage.MAX_PER_MESSAGE).forEach { known += it }
        if (known.size == before) return
        publish { it.copy(knownPeers = known.size) }
        // Dialled the same way a tracker's peers are. Without this a peer learned from `ut_pex`
        // would wait for some *other* connection to end before anyone tried it, which for a client
        // whose peers are all healthy is never.
        scope.launch { connectMore(scope) }
    }

    /**
     * BEP 11: tell each peer what has changed in the swarm since it was last told.
     *
     * The address advertised for a peer this client *accepted* is not the address it dialled from
     * — that is an ephemeral port nothing listens on — but the one its BEP 10 handshake gave as
     * `p`. A peer with no such handshake is not advertised at all: sending everyone to a dead port
     * is worse than sending them one peer fewer.
     */
    private suspend fun sendPex() {
        if (metainfo.isPrivate) return
        val links = connected.snapshot()
        val reachable =
            links.mapNotNull { link -> advertisedAddress(link)?.let { link.connection.address to it } }.toMap()
        for (link in links) {
            val id = link.extensions?.id(ExtensionHandshake.UT_PEX) ?: continue
            val current = (reachable - link.connection.address).values.toSet()
            val added = (current - link.lastPexSent).take(PexMessage.MAX_PER_MESSAGE)
            val dropped = (link.lastPexSent - current).take(PexMessage.MAX_PER_MESSAGE)
            link.lastPexSent = current
            val message =
                PexMessage(
                    added = added,
                    dropped = dropped,
                    addedFlags = added.map { if (picker.isSeed(it)) PexMessage.FLAG_SEED else 0 },
                )
            if (message.isEmpty) continue
            link.send(Message.Extended(id, message.encode()))
        }
    }

    /** Where another client should dial this peer, or null if this client does not know. */
    private fun advertisedAddress(link: PeerLink): PeerAddress? {
        val address = link.connection.address
        if (!CompactPeers.isPackable(address.host)) return null
        if (link.dialled) return address
        val port = link.extensions?.listenPort ?: return null
        return PeerAddress(address.host, port)
    }

    /** Whether this client's own handshake carries BEP 6's bit. Read once, from what it sends. */
    private val ourFastExtension: Boolean = Handshake.hasFastExtension(config.reserved)

    private fun everyPiece(): ByteArray {
        val all = Bitfield(metainfo.pieceCount)
        (0 until metainfo.pieceCount).forEach { all.set(it) }
        return all.toBytes()
    }

    /**
     * Asks a choking peer for the pieces it said it would serve anyway.
     *
     * Bounded by the same pipeline depth as anything else: `allowed fast` changes which pieces may
     * be asked for, not how many.
     */
    private suspend fun requestAllowedFast(link: PeerLink) {
        val room = config.pipelineDepth - link.outstanding
        if (room <= 0) return
        var left = room
        for (index in link.allowedFast.toList()) {
            if (left <= 0) return
            if (picker.completed[index]) continue
            val requests = picker.nextFrom(link.connection.address, PieceIndex(index), left, elapsedMillis())
            for (request in requests) {
                if (!downloadBudget.take(request.length.toLong())) return
                if (!link.send(Message.Request(request.piece, request.begin, request.length))) return
                link.outstanding++
                left--
            }
        }
    }

    /**
     * What this client tells a peer it can do — and BEP 27: a private torrent gets **no** `ut_pex`
     * in the dictionary at all.
     *
     * Not "offered and then never sent": the point of `private = 1` is that the swarm is the
     * tracker's business, and a peer that sees `ut_pex` in the handshake will ask. The rule lives
     * here, next to the only place that could break it.
     */
    private val offeredExtensions: Map<String, Int> =
        buildMap {
            putAll(config.extensions)
            // BEP 27 names PEX, DHT and local discovery; metadata exchange is not on that list and
            // there is nothing to hide — every peer of a private torrent got the file from the
            // same tracker this client did.
            put(ExtensionHandshake.UT_METADATA, METADATA_ID)
            if (metainfo.isPrivate) remove(ExtensionHandshake.UT_PEX) else put(ExtensionHandshake.UT_PEX, PEX_ID)
        }

    /** The dictionary itself, which is [offeredExtensions] plus who this client is. */
    private fun ourExtensionHandshake(): ExtensionHandshake =
        ExtensionHandshake(
            extensions = offeredExtensions,
            clientVersion = config.clientVersion,
            listenPort = listenPort,
            requestQueueLength = config.pipelineDepth,
            // BEP 9: without this a peer knows the extension is offered and not how much to ask
            // for, which is the same as it not being offered.
            metadataSize = metainfo.infoBytes.size,
        )

    /**
     * BEP 10's extended messages: the handshake, and everything this client did not ask for.
     *
     * A peer may send an extended message under any id it likes; the ids it may *use* are the ones
     * this client offered in its own `m`, which in phase 1 is none. So anything but id 0 is a peer
     * being generous or confused, and either way it is dropped — not an error, because BEP 10's
     * whole mechanism rests on both sides ignoring what they do not recognise.
     */
    private fun receiveExtended(
        scope: CoroutineScope,
        link: PeerLink,
        message: Message.Extended,
    ) {
        if (message.extensionId == PEX_ID && offeredExtensions.containsKey(ExtensionHandshake.UT_PEX)) {
            receivePex(scope, link, message.payload)
            return
        }
        if (message.extensionId == METADATA_ID) {
            scope.launch { serveMetadata(link, message.payload) }
            return
        }
        if (message.extensionId != ExtensionHandshake.HANDSHAKE_ID) return
        val handshake =
            try {
                ExtensionHandshake.decode(message.payload)
            } catch (malformed: WireException) {
                // A peer whose handshake will not parse keeps its connection: everything BEP 3
                // needs still works, and the extensions are what it loses.
                publish { it.copy(lastPeerError = "${link.connection.address}: ${malformed.message}") }
                return
            }
        link.extensions = handshake
        publish { it.copy(extendedPeers = connected.values.count { peer -> peer.extensions != null }) }
    }

    /**
     * Answers a `request`, or refuses to.
     *
     * BEP 3: a request larger than 16 KiB is a connection every deployed client closes, so this
     * one does too — a peer asking for a megabyte is either broken or trying something, and there
     * is no reading of the specification under which it is neither.
     */
    private suspend fun serveRequest(
        link: PeerLink,
        request: Message.Request,
    ) {
        if (request.length !in 1..PeerWire.BLOCK_SIZE) {
            link.connection.close()
            return
        }
        if (link.choking || !picker.completed[request.piece.value]) {
            // BEP 6: a request that will not be answered is answered anyway, so that the peer's
            // picker can free the block instead of waiting out its own timeout. Without the
            // extension the only honest thing to do is nothing.
            if (link.fast) link.send(Message.Reject(request.piece, request.begin, request.length))
            return
        }
        if (uploadBudget.take(request.length.toLong())) {
            serveNow(link, request)
            return
        }
        // The limit is reached, so the block waits for the timer's refill rather than being
        // refused: BEP 3 has no way to say "not now", and a request dropped in silence costs the
        // peer a timeout it did not earn. A peer that queues more than it could ever be sent is
        // ignored past the bound — the alternative is a queue a peer can grow from the other end
        // of the wire.
        if (link.waiting.size < MAX_WAITING_REQUESTS) link.waiting.addLast(request)
    }

    private suspend fun serveNow(
        link: PeerLink,
        request: Message.Request,
    ) {
        link.connection.sendBlock(request.piece, request.begin, request.length)
        link.upload.add(request.length.toLong(), elapsedMillis())
        publish { it.copy(uploaded = connected.values.sumOf { peer -> peer.connection.uploaded }) }
    }

    /**
     * Spends the tick's upload tokens on the requests that were waiting for them.
     *
     * Round robin from a rotating start, so that a peer which asks first does not take the whole
     * budget every tick. Conditions are re-checked before each block: a peer choked or a piece
     * dropped between the request and the tokens for it is a block that must not go out, and
     * checking after spending would throw the tokens away with it.
     */
    private suspend fun drainWaitingUploads() {
        if (uploadBudget.isUnlimited) return
        val links = connected.snapshot()
        if (links.isEmpty()) return
        uploadTurn = (uploadTurn + 1) % links.size
        val order = links.drop(uploadTurn) + links.take(uploadTurn)
        for (link in order) {
            while (true) {
                val next = link.waiting.firstOrNull() ?: break
                if (link.choking || !picker.completed[next.piece.value]) {
                    link.waiting.removeFirst()
                    continue
                }
                if (!uploadBudget.take(next.length.toLong())) return
                link.waiting.removeFirst()
                serveNow(link, next)
            }
        }
    }

    /**
     * Runs BEP 3's choking algorithm and tells the peers whose answer changed.
     *
     * From the one timer, every ten seconds, with the optimistic choice rotating every thirty —
     * "the currently deployed choking algorithm avoids fibrillation by only changing who's choked
     * once every ten seconds", and a peer given an optimistic chance needs long enough to use it.
     *
     * Only the differences are sent. A `choke` to a peer that is already choked is a byte that
     * says nothing, and fifty of them every ten seconds is a client that talks more than it
     * listens.
     */
    private suspend fun chokePass(rotateOptimistic: Boolean) {
        val now = elapsedMillis()
        val links = connected.snapshot()
        val decision =
            choker.pass(
                links.map { link ->
                    PeerRates(
                        peer = link.connection.address,
                        interested = link.peerInterested,
                        downloadRate = link.download.bytesPerSecond(now),
                        uploadRate = link.upload.bytesPerSecond(now),
                    )
                },
                seeding = picker.isComplete,
                rotateOptimistic = rotateOptimistic,
            )
        links.forEach { link ->
            val shouldChoke = link.connection.address !in decision.unchoked
            if (shouldChoke == link.choking) return@forEach
            link.choking = shouldChoke
            link.send(if (shouldChoke) Message.Choke else Message.Unchoke)
            if (shouldChoke) rejectWaiting(link) else drainWaitingUploads()
        }
    }

    private suspend fun updateInterest(link: PeerLink) {
        val interesting = picker.isInteresting(link.connection.address)
        if (interesting == link.interested) return
        link.interested = interesting
        link.send(if (interesting) Message.Interested else Message.NotInterested)
        if (interesting) requestMore(link)
    }

    private suspend fun requestMore(link: PeerLink) {
        if (stopping || paused) return
        // BEP 6: a choked peer will still serve the pieces it named as `allowed fast`, and asking
        // for them is the difference between a cold start and waiting for an unchoke.
        if (link.choked) {
            if (link.fast && link.allowedFast.isNotEmpty()) requestAllowedFast(link)
            return
        }
        if (!link.interested) return
        val room = config.pipelineDepth - link.outstanding
        if (room <= 0) return
        // Asked of the budget *before* the picker, because `next` marks the blocks it hands back as
        // in flight. Taking more than the limit pays for and then not sending them would leave the
        // picker holding blocks nobody is fetching until the request timeout expired them.
        val affordable =
            if (downloadBudget.isUnlimited) {
                room
            } else {
                minOf(room.toLong(), downloadBudget.available / PeerWire.BLOCK_SIZE).toInt()
            }
        if (affordable <= 0) return
        picker.next(link.connection.address, affordable, elapsedMillis()).forEach { request ->
            downloadBudget.take(request.length.toLong())
            if (!link.send(Message.Request(request.piece, request.begin, request.length))) return
            link.outstanding++
        }
    }

    private suspend fun consumeOutcomes(scope: CoroutineScope) {
        for (outcome in writer.outcomes) {
            when (outcome) {
                is PieceOutcome.Verified -> {
                    picker.pieceVerified(outcome.piece)
                    val length = metainfo.pieceLengthAt(outcome.piece).toLong()
                    publish {
                        it.copy(
                            completedPieces = picker.completed.cardinality,
                            downloaded = it.downloaded + length,
                            left = it.left - length,
                            isComplete = picker.isComplete,
                        )
                    }
                    connected.snapshot().forEach { it.send(Message.Have(outcome.piece)) }
                    if (picker.isComplete) scope.launch { announce(AnnounceEvent.COMPLETED) }
                }

                is PieceOutcome.HashMismatch -> {
                    picker.pieceFailed(outcome.piece)
                    publish { it.copy(hashFailures = it.hashFailures + 1) }
                }
            }
            connected.snapshot().forEach { requestMore(it) }
        }
    }

    /**
     * The one timer. Everything periodic in the session is a multiple of [SessionConfig.tick] and
     * happens here; a per-peer ticker would be one waking coroutine per peer for periods that are
     * not per-peer in the first place.
     */
    private suspend fun timerLoop() {
        var sinceKeepAlive = kotlin.time.Duration.ZERO
        var sinceFlush = kotlin.time.Duration.ZERO
        var sinceChoke = kotlin.time.Duration.ZERO
        var sinceResume = kotlin.time.Duration.ZERO
        var sincePex = kotlin.time.Duration.ZERO
        var sinceOptimistic = config.optimisticInterval
        while (!stopping) {
            delay(config.tick)
            sinceKeepAlive += config.tick
            sinceFlush += config.tick
            if (sinceKeepAlive >= config.keepAliveInterval) {
                sinceKeepAlive = kotlin.time.Duration.ZERO
                tick("keep-alives") { connected.snapshot().forEach { it.send(Message.KeepAlive) } }
            }
            if (sinceFlush >= config.flushInterval) {
                sinceFlush = kotlin.time.Duration.ZERO
                tick("flush") { storage.flush() }
            }
            sincePex += config.tick
            if (sincePex >= config.pexInterval) {
                sincePex = kotlin.time.Duration.ZERO
                tick("peer exchange") { sendPex() }
            }
            tick("rates") { refillRateLimits() }
            tick("expiry") { expireRequests() }
            sinceResume += config.tick
            if (sinceResume >= config.resumeInterval) {
                sinceResume = kotlin.time.Duration.ZERO
                tick("resume") { saveResume() }
            }
            sinceChoke += config.tick
            if (sinceChoke >= config.chokeInterval) {
                sinceChoke = kotlin.time.Duration.ZERO
                sinceOptimistic += config.chokeInterval
                val rotate = sinceOptimistic >= config.optimisticInterval
                if (rotate) sinceOptimistic = kotlin.time.Duration.ZERO
                tick("choking") { chokePass(rotate) }
            }
            publishPeerCounts()
        }
    }

    /**
     * One tick's worth of tokens, and what they pay for.
     *
     * A throttled download would otherwise stall until some other event woke it: requests are
     * normally issued when a block arrives, and a peer sends no block while nothing is asked of it.
     * The refill is what breaks that circle, which is why it also asks every peer for more.
     */
    private suspend fun refillRateLimits() {
        if (uploadBudget.isUnlimited && downloadBudget.isUnlimited) return
        uploadBudget.refill(config.tick)
        downloadBudget.refill(config.tick)
        drainWaitingUploads()
        if (!downloadBudget.isUnlimited) connected.snapshot().forEach { requestMore(it) }
    }

    /**
     * Runs one of the timer's jobs so that its failure does not end the others.
     *
     * The timer is the session's only periodic anything: keep-alives, `force()`, and taking back
     * requests nobody answered. A single throw used to end the loop, after which a download simply
     * stopped — no error a user could see, because the failure went to `sessionError` and nothing
     * printed it. One job failing is a problem; all three stopping is a dead client.
     */
    private suspend fun tick(
        what: String,
        job: suspend () -> Unit,
    ) {
        try {
            job()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            publish { it.copy(sessionError = "$what: ${failure.message ?: failure::class.simpleName}") }
        }
    }

    /**
     * Sends, and reports whether the peer was still there.
     *
     * A peer that went away has a closed outgoing queue, and sending to one throws. Every loop
     * that writes to *all* peers — the timer's keep-alives, the `have` broadcast, the re-request
     * after an expiry — would otherwise die on the first departed peer and take the whole periodic
     * half of the session with it. That is not hypothetical: it is what stopped the Debian
     * download dead at 123 pieces with five peers unchoked and sixteen requests outstanding for
     * ever (B-19).
     *
     * The reason is published rather than dropped. It is usually "the peer left", which the
     * connection also reports as a `Closed` event — but "usually" is not "always", and a send that
     * fails for another reason should not be the one failure nobody can see.
     */
    private suspend fun PeerLink.send(message: Message): Boolean =
        try {
            connection.send(message)
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (gone: Exception) {
            publish {
                it.copy(lastPeerError = "${connection.address}: ${gone.message ?: gone::class.simpleName}")
            }
            false
        }

    /**
     * The files, derived from the pieces.
     *
     * On the timer with the peer list, because it is a pass over the piece bitfield: doing it when
     * a piece lands would be work proportional to the torrent on the hot path.
     */
    private fun fileViews(): List<FileView> {
        val verified = verifiedBytesPerFile(metainfo, picker.completed)
        return metainfo.files.mapIndexed { at, file ->
            FileView(
                path = file.path.joinToString("/"),
                length = file.length,
                verifiedBytes = verified[at],
                wanted = at !in unwantedFiles,
            )
        }
    }

    /** Recomputed rather than tracked: two counters that must agree with the peer table. */
    private fun publishPeerCounts() {
        val links = connected.values
        val now = elapsedMillis()
        publish {
            it.copy(
                connectedPeers = links.size,
                unchokedPeers = links.count { link -> !link.choked },
                outstandingRequests = links.sumOf { link -> link.outstanding },
                // Built here and nowhere else. This is the one field whose cost grows with the
                // swarm, and the timer is the one place in the session that already runs at the
                // rate a table is redrawn at.
                peers = links.map { link -> link.view(now, picker.piecesHeldBy(link.connection.address)) },
                files = fileViews(),
                // Recomputed here so the countdowns tick down instead of standing still between
                // announces, which are half an hour apart.
                trackers = trackerViews(),
                dhtAnnouncedSecondsAgo = dhtAnnouncedAt?.elapsedNow()?.inWholeSeconds,
                dhtNextInSeconds =
                    dhtAnnouncedAt?.let {
                        (config.dhtInterval - it.elapsedNow()).inWholeSeconds.coerceAtLeast(0)
                    },
            )
        }
    }

    /**
     * Takes back the blocks nobody answered for.
     *
     * The one timer's third job, after keep-alives and `force()`. Each freed block is a request
     * this client is still owed and will now ask somebody else for; the peer keeps its connection,
     * because a peer that is slow now may be fast in a minute, but it loses the claim.
     */
    private suspend fun expireRequests() {
        val expired = picker.expireRequests(elapsedMillis() - config.requestTimeout.inWholeMilliseconds)
        if (expired.isEmpty()) return
        expired.forEach { request ->
            connected[request.peer]?.let { link -> link.outstanding = (link.outstanding - 1).coerceAtLeast(0) }
        }
        connected.snapshot().forEach { requestMore(it) }
    }

    /** Milliseconds since this session started; the picker's only notion of time. */
    private fun elapsedMillis(): Long = startedAt.elapsedNow().inWholeMilliseconds

    /**
     * A copy of the peer table to iterate over.
     *
     * Every loop that sends to each peer suspends inside itself, and a suspension is a point where
     * another coroutine runs — including a peer coroutine adding or removing its own entry. Being
     * single-threaded does not help: coroutines interleave at suspension points exactly as threads
     * interleave anywhere, and the symptom is an intermittent `ConcurrentModificationException`
     * from a loop that "cannot" race.
     */
    private fun Map<PeerAddress, PeerLink>.snapshot(): List<PeerLink> = values.toList()

    /**
     * Launches one of the session's loops so that its failure becomes state rather than smoke.
     *
     * Under a `SupervisorJob` a child that throws does not cancel its siblings — it goes to the
     * context's exception handler, which by default means "reported in a platform-dependent
     * manner", which means a log line nobody attributes and, in a test suite, an intermittent
     * failure in whichever test runs next. A session that is degraded should say so.
     */
    private fun CoroutineScope.launchGuarded(
        what: String,
        block: suspend () -> Unit,
    ): Job =
        launch {
            try {
                block()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                publish { it.copy(sessionError = "$what: ${failure.message ?: failure::class.simpleName}") }
            }
        }

    private inline fun publish(update: (SessionState) -> SessionState) {
        mutableState.value = update(mutableState.value)
    }

    /** What the session tracks about one connection: the protocol's two flags and the pipeline. */
    private class PeerLink(
        val connection: PeerConnection,
    ) {
        /** This peer as a row: everything a reader is allowed to know, and nothing they can hold. */
        fun view(
            nowMillis: Long,
            pieces: Int,
        ): PeerView =
            PeerView(
                address = connection.address.toString(),
                client = clientOf(connection.handshake.peerId),
                dialled = dialled,
                choking = choked,
                choked = choking,
                interested = interested,
                peerInterested = peerInterested,
                fast = fast,
                extended = extensions != null,
                outstanding = outstanding,
                pieces = pieces,
                downBytesPerSecond = download.bytesPerSecond(nowMillis),
                upBytesPerSecond = upload.bytesPerSecond(nowMillis),
            )

        /** BEP 3: "Connections start out choked and not interested." Four flags, two per side. */
        var choked: Boolean = true
        var interested: Boolean = false

        /** Whether *we* are choking *them*, and whether they said they want anything. */
        var choking: Boolean = true
        var peerInterested: Boolean = false
        var outstanding: Int = 0

        /** What this peer is doing for us, and we for it, over the choker's window. */
        val download: RateMeter = RateMeter()
        val upload: RateMeter = RateMeter()

        /**
         * BEP 10's handshake, once it arrives. Null means either that the peer does not speak the
         * extension protocol or that it has not said so yet — and the difference does not matter
         * to anyone asking "can I send this peer a `ut_pex`", which is the only question this
         * answers.
         */
        var extensions: ExtensionHandshake? = null

        /**
         * Requests this peer made that the upload limit has not paid for yet.
         *
         * Empty whenever there is no limit: an unthrottled client answers a request as it arrives
         * and never queues one.
         */
        val waiting: ArrayDeque<Message.Request> = ArrayDeque()

        /** BEP 6, and only when *both* sides advertised it. */
        var fast: Boolean = false

        /** Pieces this peer will serve while it is choking us (BEP 6's `allowed fast`). */
        val allowedFast: MutableSet<Int> = LinkedHashSet()

        /** Whether this client dialled the peer, or the peer dialled it (BEP 11 cares). */
        var dialled: Boolean = false

        /** What this peer was last told about the swarm, so the next `ut_pex` can be a delta. */
        var lastPexSent: Set<PeerAddress> = emptySet()
    }

    private companion object {
        const val DEFAULT_ANNOUNCE_SECONDS = 1800
        const val MIN_ANNOUNCE_SECONDS = 60
        const val MILLIS_PER_SECOND = 1000L

        /**
         * The ids this client asks peers to use. Ours to choose, and theirs to choose theirs —
         * BEP 10's ids are not symmetric.
         */
        const val PEX_ID = ExtensionHandshake.ID_UT_PEX
        const val METADATA_ID = ExtensionHandshake.ID_UT_METADATA

        /**
         * How many of a peer's requests may wait for upload tokens.
         *
         * Bounded because the other end of this queue is somebody else's client: without a bound a
         * peer could grow it by pipelining, and the memory would be this client's problem.
         */
        const val MAX_WAITING_REQUESTS = 64
    }
}

private fun SessionState.copy(
    completedPieces: Int = this.completedPieces,
    downloaded: Long = this.downloaded,
    uploaded: Long = this.uploaded,
    left: Long = this.left,
    connectedPeers: Int = this.connectedPeers,
    unchokedPeers: Int = this.unchokedPeers,
    outstandingRequests: Int = this.outstandingRequests,
    knownPeers: Int = this.knownPeers,
    dhtNodes: Int = this.dhtNodes,
    extendedPeers: Int = this.extendedPeers,
    hashFailures: Int = this.hashFailures,
    verifiedPieces: Int = this.verifiedPieces,
    verifyingOf: Int = this.verifyingOf,
    trackerError: String? = this.trackerError,
    lastPeerError: String? = this.lastPeerError,
    sessionError: String? = this.sessionError,
    isComplete: Boolean = this.isComplete,
    paused: Boolean = this.paused,
    sequential: Boolean = this.sequential,
    dhtAnnouncedSecondsAgo: Long? = this.dhtAnnouncedSecondsAgo,
    dhtNextInSeconds: Long? = this.dhtNextInSeconds,
    peers: List<PeerView> = this.peers,
    files: List<FileView> = this.files,
    trackers: List<TrackerView> = this.trackers,
): SessionState =
    SessionState(
        infoHash = infoHash,
        name = name,
        totalLength = totalLength,
        pieceCount = pieceCount,
        completedPieces = completedPieces,
        downloaded = downloaded,
        uploaded = uploaded,
        left = left,
        connectedPeers = connectedPeers,
        unchokedPeers = unchokedPeers,
        outstandingRequests = outstandingRequests,
        knownPeers = knownPeers,
        dhtNodes = dhtNodes,
        extendedPeers = extendedPeers,
        hashFailures = hashFailures,
        verifiedPieces = verifiedPieces,
        verifyingOf = verifyingOf,
        trackerError = trackerError,
        lastPeerError = lastPeerError,
        sessionError = sessionError,
        isComplete = isComplete,
        paused = paused,
        sequential = sequential,
        dhtAnnouncedSecondsAgo = dhtAnnouncedSecondsAgo,
        dhtNextInSeconds = dhtNextInSeconds,
        peers = peers,
        files = files,
        trackers = trackers,
    )
