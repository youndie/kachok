package io.github.youndie.kachok.engine.runtime

import io.github.youndie.kachok.engine.dht.Dht
import io.github.youndie.kachok.engine.dht.NodeId
import io.github.youndie.kachok.engine.hex
import io.github.youndie.kachok.engine.io.DatagramKrpcTransport
import io.github.youndie.kachok.engine.io.EngineDispatchers
import io.github.youndie.kachok.engine.io.PeerListener
import io.github.youndie.kachok.engine.io.SocketPeerConnection
import io.github.youndie.kachok.engine.metainfo.Metainfo
import io.github.youndie.kachok.engine.nat.LsdSocket
import io.github.youndie.kachok.engine.nat.PortMapper
import io.github.youndie.kachok.engine.nat.PortMapping
import io.github.youndie.kachok.engine.peer.Encryption
import io.github.youndie.kachok.engine.session.Command
import io.github.youndie.kachok.engine.session.FilePriority
import io.github.youndie.kachok.engine.storage.FileSet
import io.github.youndie.kachok.engine.tracker.TrackerProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.BindException
import java.net.DatagramSocket
import java.net.SocketException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

/** What the whole process chooses, as opposed to what one torrent does. */
public class SetOptions(
    public val port: Int? = null,
    /**
     * Join the DHT (BEP 5).
     *
     * **Off here and on in the products, and the split is deliberate (B-99).** Joining opens a
     * socket and announces this machine's address to three public routers; a library must not do
     * that because it was constructed, and ten of this repository's own tests build a set with
     * these defaults. What a *person* running the client should get is a different question, and
     * the CLI and the window answer it with `true` — measured: without the DHT, a public torrent
     * whose tracker hands out one peer per announce leaves this client with one peer.
     */
    public val dht: Boolean = false,
    /**
     * What this process offers the peers that dial *it* (B-100).
     *
     * On the accepting side the choice is only ever "which dialects do we answer": the peer has
     * already decided, and `PREFERRED` answers both. It is here and not on the torrent because
     * one listener serves every torrent in the set.
     */
    public val encryption: Encryption = Encryption.PREFERRED,
    /**
     * Where every torrent of the set keeps its resume record, or null to keep it beside the
     * torrent's files (B-150).
     *
     * Null here and a directory in the products, for the same reason as [dht]: the CLI's MCP
     * engine and the window keep their records next to the list of torrents they remember, so a
     * download folder holds downloads; a library, and the tests built on it, write nothing outside
     * the directory they were given. A record still beside the files is read once and moved.
     */
    public val resumeDirectory: Path? = null,
    /** What the set starts with; [TorrentSet.limit] changes it while it runs (B-151). */
    public val limits: SetLimits = SetLimits(),
)

/**
 * The client's own limits, across every torrent it runs
 * ([B-151](../../../../../../../../docs/backlog/B-151-one-limit-for-the-whole-client.md)).
 *
 * **One budget for the client, not one per torrent.** A limit exists because a link is shared, and
 * a per-torrent limit times however many torrents happen to be running is not a limit: eleven
 * torrents at 10 Mbit/s each is 110. A torrent's own limit, where somebody set one, still applies on
 * top of this.
 */
public data class SetLimits(
    /** Bytes a second the whole client asks for. Zero means no limit. */
    public val downloadBytesPerSecond: Long = 0,
    /** Bytes a second the whole client serves. Zero means no limit. */
    public val uploadBytesPerSecond: Long = 0,
    /**
     * Downloads that run at once; the rest wait, queued, in the order they were added, and start
     * as turns free up. Seeding is not a download and never waits. Zero means no limit.
     */
    public val maxActiveDownloads: Int = 0,
    /**
     * Connections the whole client holds, dials in flight included
     * ([B-152](../../../../../../../../docs/backlog/B-152-one-cap-on-connections-for-the-whole-client.md)).
     * Shared out equally among the torrents that are transferring; one that is paused or queued
     * holds none. Zero means no cap, and each torrent keeps its own `maxPeers`.
     */
    public val maxConnections: Int = 0,
    /**
     * Dials in flight at once, across every torrent (B-152). Each is a half-open entry in the
     * router's table for up to the connect timeout, and most of a public swarm's never answer.
     * Zero means no cap.
     */
    public val maxDialsInFlight: Int = 0,
) {
    init {
        require(downloadBytesPerSecond >= 0) { "a download limit of $downloadBytesPerSecond" }
        require(uploadBytesPerSecond >= 0) { "an upload limit of $uploadBytesPerSecond" }
        require(maxActiveDownloads >= 0) { "$maxActiveDownloads downloads at once" }
        require(maxConnections >= 0) { "$maxConnections connections" }
        require(maxDialsInFlight >= 0) { "$maxDialsInFlight dials at once" }
    }

    public companion object {
        /**
         * What a client a person runs starts with when nobody has chosen (B-152). Not the engine's
         * default, which is no cap at all, for the reason the DHT is off there: a library caps
         * nothing because it was constructed. 200 connections is a dozen torrents of useful peers;
         * 20 dials is a swarm reached in a few seconds without a burst a router notices.
         */
        public val RECOMMENDED_MAX_CONNECTIONS: Int = 200
        public val RECOMMENDED_MAX_DIALS: Int = 20
    }
}

/** How the client's connections stand against its caps (B-152). */
public data class ConnectionStatus(
    public val connections: Int,
    public val dialling: Int,
    public val maxConnections: Int,
    public val maxDialsInFlight: Int,
)

/** How the queue stands: downloads running, downloads waiting, and the most that may run. */
public data class QueueStatus(
    public val active: Int,
    public val queued: Int,
    public val maxActive: Int,
)

/**
 * Several torrents in one process, sharing the three things that cannot be had twice.
 *
 * **One listener.** There is one port, it is announced to every tracker, and an incoming peer says
 * which torrent it wants in its own handshake — so the socket is read first and routed second.
 * A second `ServerSocketChannel` would need a second port nobody was told about.
 *
 * **One DHT.** One routing table and one socket for the process: BEP 5's `implied_port` needs a
 * stable source port, and two tables would be two half-populated ones bootstrapping past each
 * other.
 *
 * **One dispatcher.** Every coroutine in the engine already runs on the same virtual-thread
 * executor; a second would be a second set of carriers for no reason.
 *
 * **A pool per torrent, not one for the set**, which is the one sharing this deliberately does not
 * do. The cap is the back-pressure and it is per session: a global queue would let a fast torrent
 * starve a slow one, and direct buffers are allocated lazily, so N pools cost what N torrents
 * actually use rather than the sum of their caps
 * ([B-54](../../../../../../../../docs/backlog/B-54-many-torrents.md)).
 */
public class TorrentSet(
    private val dispatchers: EngineDispatchers,
    private val scope: CoroutineScope,
    options: SetOptions = SetOptions(),
    private val onBindFailure: (String) -> Unit = {},
) : AutoCloseable {
    /** What this process answers a peer that dials it; see [SetOptions.encryption]. */
    private val answeringWith: Encryption = options.encryption

    private val resumeDirectory: Path? = options.resumeDirectory

    /**
     * Peers that dialled this process and were turned away before any session saw them.
     *
     * The session's own disconnect reasons (B-105) start after a handshake; these never got one,
     * and until B-100 there was no way to tell "nobody dials us" from "everybody who dials us is
     * refused". Read by the acceptance run and by anybody wondering the same thing.
     */
    @Volatile
    public var refusedAccepts: Int = 0
        private set

    /** Why the last one was turned away, in its own words. */
    @Volatile
    public var lastRefusedAccept: String? = null
        private set

    private val listener =
        try {
            PeerListener.bind(options.port?.let { it..it } ?: TrackerProtocol.PORT_RANGE)
        } catch (unavailable: BindException) {
            onBindFailure(unavailable.message ?: "the port is in use")
            null
        }

    /**
     * The DHT, built the first time it is asked for and not before.
     *
     * **Joining is a decision, so it is taken when somebody takes it.** A socket that announces
     * this machine's address to three public routers is not something to open because a flag was
     * true at start-up and might be turned off a second later — and it is not something a person
     * should have to restart the client to change, which is what a `val` here made it
     * ([B-63](../../../../../../../../docs/backlog/B-63-joining-the-dht-at-runtime.md)).
     *
     * A torrent already running keeps the `Dht` it was opened with, which for one opened before
     * this is null. That is the same rule every other setting follows: it reaches the next torrent.
     */
    private var dhtTransport: DatagramKrpcTransport? = null

    private var dht: Dht? = null

    /** Whether a torrent added now would be given the DHT. */
    public val dhtEnabled: Boolean get() = dht != null

    /**
     * The DHT a magnet can be looked up in before it is a torrent, or null when it is off
     * ([B-135](../../../../../../../../docs/backlog/B-135-the-magnet-fetch-gives-up-too-early.md)).
     */
    public val dhtForLookups: Dht? get() = dht

    /**
     * Turns the DHT on or off for torrents added from now on.
     *
     * Turning it off closes the socket, which stops the announcing this client is doing; a session
     * that already holds the `Dht` finds its transport shut, which its own loop reports the way it
     * reports any other failure to reach the network.
     */
    @Synchronized
    public fun useDht(on: Boolean) {
        if (on == dhtEnabled) return
        if (on) {
            val transport = DatagramKrpcTransport(dispatchers.io, socket = dhtSocket())
            dhtTransport = transport
            val joined = Dht(self = NodeId.random(), transport = transport)
            dht = joined
            transport.onQuery = joined::answer
            transport.start(scope)
            dhtMappingJob = keepMapped(transport.port, tcp = false) { dhtMapping = it }
        } else {
            dhtMappingJob?.cancel()
            dhtMappingJob = null
            val port = dhtTransport?.port
            if (dhtMapping is PortMapping.Mapped && port != null) {
                // Off the caller's thread: a release is a request to a router, and turning the DHT
                // off is a click.
                scope.launch(dispatchers.io) { mapper.release(port, tcp = false) }
            }
            dhtMapping = PortMapping.NotTried
            dhtTransport?.close()
            dhtTransport = null
            dht = null
        }
    }

    private val byInfoHash = ConcurrentHashMap<String, TorrentRuntime>()

    /** The port the trackers are told about, which is the one that was actually free. */
    public val listenPort: Int = listener?.port ?: options.port ?: TrackerProtocol.PORT_RANGE.first

    /**
     * Whether the router is forwarding [listenPort], and what it said if not (B-103).
     *
     * **The port mapped is the one that was bound**, never the one that was asked for.
     * `PeerListener` already makes that distinction for the tracker — "the port that was free is
     * the one the tracker must be told about" — and a mapping that disagreed with the announce
     * would be the same defect one layer down: a client telling everyone about a port that is
     * forwarded nowhere.
     */
    public val portMapping: String
        get() = describe(mapping, "no listener to map")

    /**
     * Whether the router forwards the DHT's UDP port, or null with the DHT off (B-143).
     *
     * The DHT answers queries since B-142, and a query from a node this client has not just
     * written to reaches it only through a forwarded port.
     */
    public val dhtPortMapping: String?
        get() = if (dhtTransport == null) null else describe(dhtMapping, "not asked yet")

    private fun describe(
        state: PortMapping,
        notTried: String,
    ): String =
        when (state) {
            is PortMapping.Mapped -> "mapped to ${state.externalPort}"
            is PortMapping.NotMapped -> "not mapped: ${state.because}"
            PortMapping.NotTried -> "not mapped: $notTried"
        }

    /**
     * The port the router is forwarding, or null.
     *
     * Beside [portMapping] rather than parsed out of it: a number a screen wants to draw and a
     * sentence a person wants to read are different things, and deriving the first from the second
     * is how a status line starts depending on the wording of an error message.
     */
    public val mappedExternalPort: Int?
        get() = (mapping as? PortMapping.Mapped)?.externalPort

    private val mapper = PortMapper()

    @Volatile
    private var mapping: PortMapping = PortMapping.NotTried

    /**
     * Asks the router for the port, then renews at half the lease for as long as the set lives.
     *
     * **Launched rather than awaited**, because on a network whose router does not answer this
     * costs four seconds and a client must not spend them before it dials anybody. The network
     * this was written on is exactly that network, so the asynchronous shape is not speculative.
     */
    private val mappingJob: Job? = listener?.let { bound -> keepMapped(bound.port, tcp = true) { mapping = it } }

    @Volatile
    private var dhtMapping: PortMapping = PortMapping.NotTried

    private var dhtMappingJob: Job? = null

    init {
        // After the mapper and the listen port, which joining the DHT now uses: it binds the same
        // port and asks the router to forward it for UDP (B-143).
        if (options.dht) useDht(true)
    }

    /** Maps [port] and renews it at half the lease, reporting each answer to [record]. */
    private fun keepMapped(
        port: Int,
        tcp: Boolean,
        record: (PortMapping) -> Unit,
    ): Job =
        scope.launch(dispatchers.io) {
            while (isActive) {
                val result = mapper.map(port, tcp)
                record(result)
                // A refusal is not retried on a timer. A router that does not speak NAT-PMP
                // will not have learned it in an hour, and asking again every half hour is
                // noise on somebody's network for no chance of a different answer.
                val next = (result as? PortMapping.Mapped)?.let { mapper.renewAfter(it) } ?: break
                delay(next)
            }
        }

    /**
     * The DHT's socket, on the listener's port when it is free (B-143).
     *
     * **It used to be an ephemeral port**, so nothing forwarded it and it changed at every start:
     * a node that kept this one lost it at the next restart, and behind a NAT only nodes this
     * client had just written to could reach it. The listener's port is what libtorrent and
     * Transmission use for UDP, and one number is one thing for a person to forward by hand.
     */
    private fun dhtSocket(): DatagramSocket =
        try {
            DatagramSocket(listenPort)
        } catch (taken: SocketException) {
            onBindFailure("the DHT could not have UDP port $listenPort (${taken.message}), so it took another")
            DatagramSocket()
        }

    /** Null when the DHT is off, which is what the status bar draws differently from zero nodes. */
    public val dhtPort: Int? get() = dhtTransport?.port

    public val torrents: List<TorrentRuntime> get() = byInfoHash.values.toList()

    /** Info hashes in the order they were added, which is the order queued torrents get turns in. */
    private val added = CopyOnWriteArrayList<String>()

    private val sharedDownload = SharedRateLimit(options.limits.downloadBytesPerSecond)
    private val sharedUpload = SharedRateLimit(options.limits.uploadBytesPerSecond)
    private val limitsState = MutableStateFlow(options.limits)
    private val connectionBudget =
        SharedConnectionBudget(options.limits.maxConnections, options.limits.maxDialsInFlight)

    /** Each running torrent's share of the connections and dials as last sent, so it is sent only on change. */
    private val shares = ConcurrentHashMap<String, Pair<Int?, Int?>>()

    /** Connections and dials held now, against the caps (B-152). */
    public val connectionStatus: ConnectionStatus
        get() =
            ConnectionStatus(
                connections = connectionBudget.connections,
                dialling = connectionBudget.dialling,
                maxConnections = limitsState.value.maxConnections,
                maxDialsInFlight = limitsState.value.maxDialsInFlight,
            )

    /** The client's own limits as they stand (B-151); every surface reads them from here. */
    public val limits: StateFlow<SetLimits> = limitsState.asStateFlow()

    /** New limits for every torrent, running ones included; they take effect within a tick. */
    public fun limit(limits: SetLimits) {
        sharedDownload.retune(limits.downloadBytesPerSecond)
        sharedUpload.retune(limits.uploadBytesPerSecond)
        connectionBudget.retune(limits.maxConnections, limits.maxDialsInFlight)
        limitsState.value = limits
        queueWake.trySend(Unit)
    }

    /** Running, waiting and allowed downloads, counted from the torrents' own states. */
    public val queueStatus: QueueStatus
        get() {
            val downloading = inOrder().filter { it.isStarted && it.wantsToDownload() }
            return QueueStatus(
                active = downloading.count { !it.state.value.queued },
                queued = downloading.count { it.state.value.queued },
                maxActive = limitsState.value.maxActiveDownloads,
            )
        }

    private val queueWake = Channel<Unit>(Channel.CONFLATED)

    /**
     * The queue's one loop: hold what is over the limit, release what fits.
     *
     * A loop over states rather than a reaction to each change, because the changes come from
     * everywhere — a piece completing a torrent, a person pausing one, an agent raising the limit —
     * and every one of them is answered by the same count. Twice a second is quick enough that a
     * finished download's turn is taken before anybody notices it was free, and costs a pass over a
     * list of torrents. A torrent that starts while the queue is full is held from its first moment
     * instead ([TorrentRuntime.holdOnStart]): waiting for this loop would let it announce first.
     */
    private val queueJob =
        scope.launch {
            while (isActive) {
                try {
                    balanceQueue()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failed: Exception) {
                    // A torrent removed under the pass: the next one counts again from scratch.
                    lastQueueError = failed.message ?: failed::class.simpleName
                }
                withTimeoutOrNull(QUEUE_TICK) { queueWake.receive() }
            }
        }

    /** What the queue's last failed pass said, for whoever is looking; null while it works. */
    @Volatile
    public var lastQueueError: String? = null
        private set

    private suspend fun balanceQueue() {
        val started = inOrder().filter { it.isStarted }
        // A held torrent that has nothing left to fetch — its files were skipped while it waited —
        // is not a download any more, and holding it would keep a seed off the swarm.
        started.filter { it.state.value.queued && !it.wantsToDownload() }.forEach { it.queue(held = false) }
        val downloading = started.filter { it.wantsToDownload() }
        val running = downloading.filter { !it.state.value.queued }
        val waiting = downloading.filter { it.state.value.queued }
        val max = limitsState.value.maxActiveDownloads
        when {
            max <= 0 -> waiting.forEach { it.queue(held = false) }

            // The newest wait, so a lowered limit keeps the downloads that have been running longest.
            running.size > max -> running.drop(max).forEach { it.queue(held = true) }

            else -> waiting.take(max - running.size).forEach { it.queue(held = false) }
        }
        shareConnections(started)
        lastQueueError = null
    }

    /**
     * The client's connections divided equally among the torrents transferring now (B-152).
     *
     * Equally rather than by need, because need is what a share decides: a torrent with more peers
     * than it can use would keep them, and one that has just started would have none to take. A
     * torrent that is paused or queued holds none, so its share goes to the others. Never below
     * [MIN_SHARE], so a cap divided among many torrents still leaves each enough peers to finish.
     */
    private suspend fun shareConnections(started: List<TorrentRuntime>) {
        val limits = limitsState.value
        val transferring =
            started.filter {
                val state = it.state.value
                !state.paused && !state.queued && state.sessionError == null
            }
        if (transferring.isEmpty()) return
        val peers = limits.maxConnections.takeIf { it > 0 }?.let { maxOf(MIN_SHARE, it / transferring.size) }
        // The dials in flight the same way (B-154), at least one each: a torrent that may not dial at
        // all is a torrent that never finds a peer.
        val dials = limits.maxDialsInFlight.takeIf { it > 0 }?.let { maxOf(1, it / transferring.size) }
        val share = peers to dials
        transferring.forEach { runtime ->
            val key = runtime.metainfo.infoHash.hex()
            if (shares[key] != share) {
                shares[key] = share
                runtime.share(peers, dials)
            }
        }
    }

    /** Whether a torrent that starts now has to wait for a turn. */
    private fun holdsOnStart(runtime: TorrentRuntime): Boolean {
        val max = limitsState.value.maxActiveDownloads
        if (max <= 0 || runtime.state.value.isComplete) return false
        val running =
            inOrder().count { it !== runtime && it.isStarted && it.wantsToDownload() && !it.state.value.queued }
        return running >= max
    }

    private fun TorrentRuntime.wantsToDownload(): Boolean {
        val state = this.state.value
        return !state.paused && !state.isComplete && state.sessionError == null
    }

    private fun inOrder(): List<TorrentRuntime> = added.mapNotNull { byInfoHash[it] }

    /**
     * Starts accepting, once.
     *
     * Called by the first [add] rather than by the caller: a listener running before there is any
     * torrent to route to would answer a handshake and then have to hang up on it.
     */
    private var accepting = false

    /**
     * Opens the files and builds the session. Nothing runs until [TorrentRuntime.start].
     *
     * Refuses a torrent this set already has: two sessions on one info hash would announce twice,
     * dial the same peers twice and write the same pieces to two different directories.
     */
    public fun add(
        metainfo: Metainfo,
        options: RuntimeOptions,
        onResumeFailure: (String) -> Unit = {},
    ): TorrentRuntime {
        val key = metainfo.infoHash.hex()
        require(!byInfoHash.containsKey(key)) { "this set already has ${metainfo.name}" }
        // By path and not by name: two torrents can name a hundred files each and collide on one.
        // Both would open a `FileChannel` on it and interleave two downloads into one file, and
        // neither would then hash — found by driving the window, not by a test.
        collisionWith(metainfo, options.directory)?.let { (path, owner) ->
            throw IllegalArgumentException("$path already belongs to $owner")
        }
        val runtime =
            TorrentRuntime.open(
                metainfo = metainfo,
                options = options,
                dispatchers = dispatchers,
                scope = scope,
                listenPort = listenPort,
                dht = if (metainfo.isPrivate) null else dht,
                onResumeFailure = onResumeFailure,
                resumeDirectory = resumeDirectory,
                sharedUpload = sharedUpload,
                sharedDownload = sharedDownload,
                connections = connectionBudget,
            )
        runtime.holdOnStart = { holdsOnStart(runtime) }
        byInfoHash[key] = runtime
        added += key
        queueWake.trySend(Unit)
        startAccepting()
        return runtime
    }

    /**
     * The first path this torrent would open that a running one already owns, and whose it is.
     *
     * Public so that a caller can ask *before* offering to add — the add dialog's whole job is to
     * let somebody choose a different directory, and it needs something to offer it from. `add`
     * asks the same question and refuses, so a caller that does not ask is still safe.
     */
    public fun collisionWith(
        metainfo: Metainfo,
        directory: Path,
    ): Pair<Path, String>? {
        val taken =
            byInfoHash.values.flatMap { runtime -> runtime.paths.map { it to runtime.metainfo.name } }.toMap()
        return FileSet
            .pathsIn(directory, metainfo)
            .firstNotNullOfOrNull { path -> taken[path]?.let { path to it } }
    }

    /**
     * Stops one torrent and forgets it. The set stays open; the others keep running.
     *
     * With [forgetProgress] its resume record goes too — what a removal *with the data* wants,
     * because a record vouching for files that are gone vouches for nothing, and in the set's resume
     * directory nobody would ever see it to delete it (B-150). The stop is waited for first: the
     * stop is what writes the record, and a record deleted before it was written comes back.
     */
    public suspend fun remove(
        runtime: TorrentRuntime,
        forgetProgress: Boolean = false,
    ) {
        byInfoHash.remove(runtime.metainfo.infoHash.hex()) ?: return
        added.remove(runtime.metainfo.infoHash.hex())
        shares.remove(runtime.metainfo.infoHash.hex())
        queueWake.trySend(Unit)
        runtime.stop()
        if (forgetProgress) runtime.awaitStopped()
        runtime.close()
        if (forgetProgress) {
            try {
                Files.deleteIfExists(runtime.resumePath)
            } catch (undeletable: IOException) {
                runtime.onResumeFailure("${runtime.resumePath} was not deleted: ${undeletable.message}")
            }
        }
    }

    /** What [move] did. */
    public class Moved(
        /**
         * The torrent, opened again and not yet started — at the new directory, or at the old one
         * when the move failed. The caller restores and starts it, the way it does after [add].
         */
        public val runtime: TorrentRuntime,
        /** Whether it was paused before the move, so the caller starts it the way it was. */
        public val paused: Boolean,
        /** Null when every file arrived; otherwise what went wrong, with every file put back. */
        public val failure: String?,
    )

    /**
     * Moves a torrent's files and its resume record to [directory] and opens it again there
     * ([B-134](../../../../../../../../docs/backlog/B-134-move-a-torrent-s-data.md)).
     *
     * **The resume record goes with the files, and that is what keeps the progress.** The torrent
     * is stopped first — the stop flushes and writes the record — and the record is moved beside the
     * files, so the start-up check at the new place trusts it the way it trusts it after a restart
     * and hashes only what it does not vouch for. A move is a restart somewhere else. A record kept
     * in the set's resume directory (B-150) is not beside the files and stays where it is.
     *
     * **Refused before anything is touched** when the torrent is already there, when a file it would
     * write already exists at the target, when another torrent in this set owns one of those paths,
     * or when the target's volume has less room than the files take. Those are the cases where a
     * half-done move is worse than none; `IllegalArgumentException`, with a sentence.
     *
     * One file at a time with `Files.move`: a rename on one volume, a copy and a delete across two.
     * A failure part-way puts back the files already moved and opens the torrent where it was, and
     * says so in [Moved.failure] rather than throwing, because by then the torrent has been stopped
     * and the caller has a runtime to start again either way.
     *
     * The torrent stays in [torrents] for the whole move, stopped, so a list drawn from the set does
     * not lose its row while a large copy runs.
     */
    public suspend fun move(
        runtime: TorrentRuntime,
        directory: Path,
    ): Moved {
        val metainfo = runtime.metainfo
        val key = metainfo.infoHash.hex()
        require(byInfoHash[key] === runtime) { "${metainfo.name} is not in this client" }
        val from = runtime.directory.toAbsolutePath().normalize()
        val to = directory.toAbsolutePath().normalize()
        require(from != to) { "${metainfo.name} is already saved in $to" }
        // Every file the torrent can have, and the record — which the stop below writes, so it is
        // not on the disk yet and is decided on after the stop, not now.
        val candidates =
            // `listOf`, not `+ path`: a `Path` is an `Iterable<Path>`, and adding one to a list adds its
            // name elements — `Users`, `youndie`, … — one by one.
            (
                runtime.paths +
                    listOfNotNull(runtime.resumePath.takeIf { it.toAbsolutePath().normalize().startsWith(from) })
            ).map { it.toAbsolutePath().normalize() }
                .map { it to to.resolve(from.relativize(it)) }
        candidates.firstOrNull { Files.exists(it.second) }?.let { (_, target) ->
            throw IllegalArgumentException("$target already exists; nothing was moved")
        }
        collisionWith(metainfo, to)?.let { (path, owner) ->
            throw IllegalArgumentException("$path belongs to $owner; nothing was moved")
        }
        val store = Files.getFileStore(existingAncestor(to))
        if (store != Files.getFileStore(from)) {
            val needed = candidates.filter { Files.exists(it.first) }.sumOf { Files.size(it.first) }
            require(needed <= store.usableSpace) {
                "$to has ${store.usableSpace} bytes free and ${metainfo.name} takes $needed; nothing was moved"
            }
        }

        val state = runtime.state.value
        runtime.stop()
        runtime.awaitStopped()
        runtime.close()
        val moves = candidates.filter { Files.exists(it.first) }

        val done = mutableListOf<Pair<Path, Path>>()
        val failure =
            try {
                moves.forEach { (source, target) ->
                    Files.createDirectories(target.parent)
                    Files.move(source, target)
                    done += source to target
                }
                null
            } catch (failed: IOException) {
                done.asReversed().forEach { (source, target) ->
                    try {
                        Files.move(target, source)
                    } catch (stuck: IOException) {
                        // Nothing better to do than say so: the file is at `target`, whole, and the
                        // torrent reopens at `from` and will fetch it again.
                        runtime.onResumeFailure("$target could not be moved back to $source: ${stuck.message}")
                    }
                }
                "${failed.message ?: failed::class.simpleName}; everything was put back in $from"
            }
        if (failure == null) pruneEmpty(moves.map { it.first }, from)

        val tiers = state.files
        val options =
            runtime.options.let {
                RuntimeOptions(
                    directory = if (failure == null) to else from,
                    port = it.port,
                    maxPeers = it.maxPeers,
                    pipelineDepth = it.pipelineDepth,
                    announceToAllTrackers = it.announceToAllTrackers,
                    uploadLimitBytesPerSecond = it.uploadLimitBytesPerSecond,
                    downloadLimitBytesPerSecond = it.downloadLimitBytesPerSecond,
                    // The decisions as they stand now, not as they were when the torrent opened:
                    // a tier or the order changed since is the one to keep.
                    unwantedFiles = tiers.indices.filter { tiers[it].priority == FilePriority.SKIP }.toSet(),
                    highFiles = tiers.indices.filter { tiers[it].priority == FilePriority.HIGH }.toSet(),
                    sequential = state.sequential,
                    encryption = it.encryption,
                )
            }
        val reopened =
            TorrentRuntime.open(
                metainfo = metainfo,
                options = options,
                dispatchers = dispatchers,
                scope = scope,
                listenPort = listenPort,
                dht = if (metainfo.isPrivate) null else dht,
                onResumeFailure = runtime.onResumeFailure,
                resumeDirectory = resumeDirectory,
                sharedUpload = sharedUpload,
                sharedDownload = sharedDownload,
                connections = connectionBudget,
            )
        reopened.holdOnStart = { holdsOnStart(reopened) }
        shares.remove(key)
        byInfoHash[key] = reopened
        return Moved(reopened, paused = state.paused, failure = failure)
    }

    /** The nearest directory on the way up to [path] that exists, which is where its volume is asked. */
    private fun existingAncestor(path: Path): Path {
        var at: Path? = path
        while (at != null && !Files.exists(at)) at = at.parent
        return at ?: path.root
    }

    /**
     * The directories the move emptied, deepest first, up to and not including [root].
     *
     * A multi-file torrent's folder, and any folder inside it, would otherwise stay behind as an
     * empty tree beside wherever the files went. Only empty ones: a folder that holds something
     * else is somebody's, and a move is no reason to touch it.
     */
    private fun pruneEmpty(
        moved: List<Path>,
        root: Path,
    ) {
        moved
            .flatMap { file ->
                generateSequence(file.parent) { it.parent }.takeWhile { it != root && it.startsWith(root) }
            }.distinct()
            .sortedByDescending { it.nameCount }
            .forEach { folder ->
                try {
                    Files.deleteIfExists(folder)
                } catch (kept: IOException) {
                    // Not empty — somebody else's files are in it, and they stay — or not ours to
                    // delete. Either way the move itself is done.
                }
            }
    }

    /**
     * Local service discovery, started with the first torrent and stopped with the set (B-102).
     *
     * **On, and unlike the DHT it is not a decision.** Nothing leaves the segment, so there is
     * nothing to announce to strangers and nothing to argue about; what it costs is one datagram
     * every five minutes and what it buys is the nearest peer in the swarm by a long way. A private
     * torrent is excluded by BEP 27 below, which is the one rule it does answer to.
     */
    private val lsd = LsdSocket(dispatchers.io)

    /** Why the segment is not being announced to, or null when it is. */
    public var localDiscovery: String? = "not started"
        private set

    @Synchronized
    private fun startAccepting() {
        if (accepting) return
        accepting = true
        listener?.let { bound ->
            localDiscovery =
                lsd.start(
                    scope = scope,
                    listenPort = bound.port,
                    // BEP 27: a private torrent's swarm is the tracker's business, and local
                    // discovery is on the list of things it switches off — the same list `ut_pex`
                    // and the DHT are on.
                    held = { byInfoHash.values.filterNot { it.metainfo.isPrivate }.map { it.metainfo.infoHash } },
                    onPeer = { infoHash, address ->
                        byInfoHash[infoHash.hex()]?.let { runtime ->
                            scope.launch { runtime.session.send(Command.AddPeers(listOf(address))) }
                        }
                    },
                )
        }
        listener?.start(scope) { socket ->
            // Read first, route second: which torrent this peer wants is in its handshake, and
            // nothing before that says which session should answer.
            val accepted =
                try {
                    SocketPeerConnection.readHandshake(
                        socket,
                        // Only the torrents this process holds: an encrypted dialler names its
                        // torrent as a hash that can be recognised and not read, so the lookup is
                        // a scan over what we have rather than a map.
                        { byInfoHash.values.map { it.metainfo.infoHash } },
                        encryption = answeringWith,
                    )
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (refused: Exception) {
                    // A peer that opened in a dialect this client does not speak, asked for a
                    // torrent it does not hold, or said nothing at all. `readHandshake` has closed
                    // the socket, and there is no session to tell — so it is counted here, because
                    // a listener that turns peers away silently is one nobody can measure (B-100).
                    refusedAccepts++
                    lastRefusedAccept = refused.message ?: refused::class.simpleName
                    return@start
                }
            val runtime = byInfoHash[accepted.handshake.infoHash.hex()]
            if (runtime == null) {
                // A peer asking for a torrent this process does not have gets a closed socket
                // rather than our handshake — answering would claim a torrent we cannot serve.
                socket.closeQuietly()
                return@start
            }
            val connection =
                SocketPeerConnection.answer(
                    scope,
                    socket,
                    accepted,
                    runtime.metainfo.infoHash,
                    runtime.peerId,
                    runtime.pool,
                    runtime.blocks,
                    runtime.reserved,
                )
            runtime.session.send(Command.AcceptPeer(connection))
        }
    }

    override fun close() {
        queueJob.cancel()
        // **The mapping is given back before anything else goes**, and it is best effort by
        // design: a client that maps a port and exits without releasing leaves a hole in a router
        // it does not own for the rest of the lease. The cancel comes first so the renewal loop
        // cannot re-map what this is dropping.
        mappingJob?.cancel()
        dhtMappingJob?.cancel()
        if (mapping is PortMapping.Mapped) listener?.let { mapper.release(it.port) }
        if (dhtMapping is PortMapping.Mapped) dhtTransport?.let { mapper.release(it.port, tcp = false) }
        lsd.close()
        listener?.close()
        dhtTransport?.close()
        byInfoHash.values.forEach { it.close() }
        byInfoHash.clear()
    }

    private companion object {
        /** How often the queue counts again when nothing woke it; see [queueJob]. */
        val QUEUE_TICK = 500.milliseconds

        /** The fewest connections a transferring torrent is left with, however many share the cap. */
        const val MIN_SHARE = 10

        private fun AutoCloseable.closeQuietly() {
            try {
                close()
            } catch (ignored: IOException) {
                // A socket we are refusing anyway; there is nothing a caller could do with this.
            }
        }
    }
}
