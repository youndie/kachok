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
import ru.workinprogress.kachok.engine.PieceIndex
import ru.workinprogress.kachok.engine.choke.Choker
import ru.workinprogress.kachok.engine.choke.PeerRates
import ru.workinprogress.kachok.engine.choke.RateMeter
import ru.workinprogress.kachok.engine.choke.TokenBucket
import ru.workinprogress.kachok.engine.metainfo.Metainfo
import ru.workinprogress.kachok.engine.peer.PeerAddress
import ru.workinprogress.kachok.engine.peer.PeerConnection
import ru.workinprogress.kachok.engine.peer.PeerDialer
import ru.workinprogress.kachok.engine.peer.PeerEvent
import ru.workinprogress.kachok.engine.picker.PiecePicker
import ru.workinprogress.kachok.engine.resume.ResumeRecord
import ru.workinprogress.kachok.engine.resume.ResumeStore
import ru.workinprogress.kachok.engine.resume.StartupVerifier
import ru.workinprogress.kachok.engine.storage.BlockWriter
import ru.workinprogress.kachok.engine.storage.PieceHasher
import ru.workinprogress.kachok.engine.storage.PieceOutcome
import ru.workinprogress.kachok.engine.storage.Storage
import ru.workinprogress.kachok.engine.tracker.AnnounceEvent
import ru.workinprogress.kachok.engine.tracker.AnnounceRequest
import ru.workinprogress.kachok.engine.tracker.TrackerClient
import ru.workinprogress.kachok.engine.tracker.TrackerException
import ru.workinprogress.kachok.engine.wire.ExtensionHandshake
import ru.workinprogress.kachok.engine.wire.Message
import ru.workinprogress.kachok.engine.wire.PeerWire
import ru.workinprogress.kachok.engine.wire.WireException
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
) {
    private val picker = PiecePicker(metainfo, config.maxStartedPieces)
    private val choker = Choker(config.maxUnchoked)
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
        val record = resume?.load()
        val verified =
            StartupVerifier(metainfo, storage, hasher).verify(record) { checked, total ->
                publish { it.copy(verifiedPieces = checked, verifyingOf = total) }
            }
        picker.restore(verified)
        val bytes =
            (0 until metainfo.pieceCount)
                .filter { verified[it] }
                .sumOf { metainfo.pieceLengthAt(PieceIndex(it)).toLong() }
        publish {
            it.copy(
                completedPieces = verified.cardinality,
                downloaded = bytes,
                left = metainfo.totalLength - bytes,
                uploaded = record?.uploaded ?: 0,
                isComplete = verified.isComplete,
                verifiedPieces = metainfo.pieceCount,
                verifyingOf = metainfo.pieceCount,
            )
        }
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
            // BEP 10: the extension handshake goes first, before the bitfield, and only to a peer
            // whose reserved bits asked for it. Sending id 20 to a peer that never advertised the
            // bit is an unknown message id, which BEP 3 clients close the connection over.
            if (connection.handshake.supportsExtensionProtocol) {
                link.send(Message.Extended(ExtensionHandshake.HANDSHAKE_ID, ourExtensionHandshake().encode()))
            }
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
            // The same wait as after a failed dial, and for a stronger reason: a peer that accepts
            // and immediately hangs up would otherwise be redialled in a tight loop, which is a
            // busy wait against somebody else's machine as well as our own.
            failed[address] = timeSource.markNow()
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
                        receiveExtended(link, message)
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

    /** What this client tells a peer it can do. Its `m` is empty in phase 1 and that is a fact. */
    private fun ourExtensionHandshake(): ExtensionHandshake =
        ExtensionHandshake(
            extensions = config.extensions,
            clientVersion = config.clientVersion,
            listenPort = listenPort,
            requestQueueLength = config.pipelineDepth,
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
        link: PeerLink,
        message: Message.Extended,
    ) {
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
        if (link.choking || !picker.completed[request.piece.value]) return
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
        if (link.choked || !link.interested || stopping) return
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
    }

    private companion object {
        const val DEFAULT_ANNOUNCE_SECONDS = 1800
        const val MIN_ANNOUNCE_SECONDS = 60
        const val MILLIS_PER_SECOND = 1000L

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
    extendedPeers: Int = this.extendedPeers,
    hashFailures: Int = this.hashFailures,
    verifiedPieces: Int = this.verifiedPieces,
    verifyingOf: Int = this.verifyingOf,
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
        extendedPeers = extendedPeers,
        hashFailures = hashFailures,
        verifiedPieces = verifiedPieces,
        verifyingOf = verifyingOf,
        trackerError = trackerError,
        lastPeerError = lastPeerError,
        sessionError = sessionError,
        isComplete = isComplete,
    )
