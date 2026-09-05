package ru.workinprogress.kachok.engine.session

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
import ru.workinprogress.kachok.engine.PeerId
import ru.workinprogress.kachok.engine.metainfo.Metainfo
import ru.workinprogress.kachok.engine.peer.PeerAddress
import ru.workinprogress.kachok.engine.peer.PeerConnection
import ru.workinprogress.kachok.engine.peer.PeerDialer
import ru.workinprogress.kachok.engine.peer.PeerEvent
import ru.workinprogress.kachok.engine.picker.PiecePicker
import ru.workinprogress.kachok.engine.storage.BlockWriter
import ru.workinprogress.kachok.engine.storage.PieceHasher
import ru.workinprogress.kachok.engine.storage.PieceOutcome
import ru.workinprogress.kachok.engine.storage.Storage
import ru.workinprogress.kachok.engine.tracker.AnnounceEvent
import ru.workinprogress.kachok.engine.tracker.AnnounceRequest
import ru.workinprogress.kachok.engine.tracker.TrackerClient
import ru.workinprogress.kachok.engine.tracker.TrackerException
import ru.workinprogress.kachok.engine.wire.Message
import kotlin.coroutines.ContinuationInterceptor
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
    hasher: PieceHasher,
    private val storage: Storage,
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
) {
    private val picker = PiecePicker(metainfo, config.maxStartedPieces)
    private val writer = BlockWriter(metainfo, hasher, storage)
    private val commands = Channel<Command>(Channel.BUFFERED)

    private val mutableState =
        MutableStateFlow(
            SessionState(
                infoHash = metainfo.infoHash,
                name = metainfo.name,
                totalLength = metainfo.totalLength,
                pieceCount = metainfo.pieceCount,
            ),
        )

    /** The only thing outside the engine reads. Conflated: the latest state, not every update. */
    public val state: StateFlow<SessionState> = mutableState.asStateFlow()

    private val connected = LinkedHashMap<PeerAddress, PeerLink>()
    private val known = LinkedHashSet<PeerAddress>()
    private val failed = HashMap<PeerAddress, kotlin.time.TimeMark>()
    private var announceInterval = DEFAULT_ANNOUNCE_SECONDS
    private var uploadedBytes = 0L
    private var stopping = false
    private val startedAt = timeSource.markNow()

    /** The only way to change a session from outside. */
    public suspend fun send(command: Command) {
        commands.send(command)
    }

    /**
     * Starts every coroutine of this session as a child of [scope] and returns their parent.
     *
     * Joining the returned job waits for a clean stop: the tracker has heard `stopped`, the peers
     * are closed and the storage is flushed.
     */
    public fun start(scope: CoroutineScope): Job {
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
                    if (address in connected) {
                        // Already talking to them, from our side. One connection per peer.
                        command.connection.close()
                    } else {
                        known += address
                        scope.launch { serve(scope, command.connection) }
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
    }

    private suspend fun announceLoop(scope: CoroutineScope) {
        var event: AnnounceEvent? = AnnounceEvent.STARTED
        while (scope.isActive && !stopping) {
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
                publish { it.copy(trackerError = null) }
                return response.peers
            } catch (refused: TrackerException) {
                lastError = refused.message
            }
        }
        if (lastError != null) publish { it.copy(trackerError = lastError) }
        return emptyList()
    }

    private fun connectMore(scope: CoroutineScope) {
        val room = config.maxPeers - connected.size
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
        serve(scope, connection)
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
    ) {
        val address = connection.address
        val link = PeerLink(connection)
        connected[address] = link
        picker.addPeer(address)
        publish { it.copy(connectedPeers = connected.size) }
        try {
            if (picker.completed.cardinality > 0) {
                link.send(Message.Bitfield(picker.completed.toBytes()))
            }
            for (event in connection.events) {
                handle(link, event) ?: break
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
            connected.remove(address)
            picker.removePeer(address)
            connection.close()
            publish { it.copy(connectedPeers = connected.size) }
            if (!stopping && scope.isActive) connectMore(scope)
        }
    }

    /** Null means the connection is over. */
    private suspend fun handle(
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

                    // Seeding — `request`, `cancel`, `interested` — arrives with B-20 and B-21.
                    else -> {}
                }
            }
        }
        return Unit
    }

    private suspend fun updateInterest(link: PeerLink) {
        val interesting = picker.isInteresting(link.connection.address)
        if (interesting == link.interested) return
        link.interested = interesting
        link.send(if (interesting) Message.Interested else Message.NotInterested)
        if (interesting) requestMore(link)
    }

    private suspend fun requestMore(link: PeerLink) {
        if (link.choked || !link.interested || stopping) return
        val room = config.pipelineDepth - link.outstanding
        if (room <= 0) return
        picker.next(link.connection.address, room, elapsedMillis()).forEach { request ->
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
            tick("expiry") { expireRequests() }
            publishPeerCounts()
        }
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

    /** Recomputed rather than tracked: two counters that must agree with the peer table. */
    private fun publishPeerCounts() {
        val links = connected.values
        publish {
            it.copy(
                connectedPeers = links.size,
                unchokedPeers = links.count { link -> !link.choked },
                outstandingRequests = links.sumOf { link -> link.outstanding },
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
        /** BEP 3: "Connections start out choked and not interested." */
        var choked: Boolean = true
        var interested: Boolean = false
        var outstanding: Int = 0
    }

    private companion object {
        const val DEFAULT_ANNOUNCE_SECONDS = 1800
        const val MIN_ANNOUNCE_SECONDS = 60
        const val MILLIS_PER_SECOND = 1000L
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
    hashFailures: Int = this.hashFailures,
    trackerError: String? = this.trackerError,
    lastPeerError: String? = this.lastPeerError,
    sessionError: String? = this.sessionError,
    isComplete: Boolean = this.isComplete,
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
        hashFailures = hashFailures,
        trackerError = trackerError,
        lastPeerError = lastPeerError,
        sessionError = sessionError,
        isComplete = isComplete,
    )
