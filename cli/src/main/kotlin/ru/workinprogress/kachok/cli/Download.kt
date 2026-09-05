package ru.workinprogress.kachok.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import ru.workinprogress.kachok.engine.PeerId
import ru.workinprogress.kachok.engine.dht.Dht
import ru.workinprogress.kachok.engine.dht.NodeId
import ru.workinprogress.kachok.engine.hash.MessageDigestPieceHasher
import ru.workinprogress.kachok.engine.io.BufferPool
import ru.workinprogress.kachok.engine.io.DatagramKrpcTransport
import ru.workinprogress.kachok.engine.io.EngineDispatchers
import ru.workinprogress.kachok.engine.io.PeerListener
import ru.workinprogress.kachok.engine.io.SocketPeerConnection
import ru.workinprogress.kachok.engine.io.SocketPeerDialer
import ru.workinprogress.kachok.engine.metainfo.MagnetParser
import ru.workinprogress.kachok.engine.metainfo.MetadataFetcher
import ru.workinprogress.kachok.engine.metainfo.Metainfo
import ru.workinprogress.kachok.engine.metainfo.MetainfoParser
import ru.workinprogress.kachok.engine.runtime.RuntimeOptions
import ru.workinprogress.kachok.engine.runtime.TorrentRuntime
import ru.workinprogress.kachok.engine.session.Command
import ru.workinprogress.kachok.engine.session.Session
import ru.workinprogress.kachok.engine.session.SessionConfig
import ru.workinprogress.kachok.engine.session.SessionState
import ru.workinprogress.kachok.engine.storage.FileSet
import ru.workinprogress.kachok.engine.storage.FileStorage
import ru.workinprogress.kachok.engine.storage.PieceLayout
import ru.workinprogress.kachok.engine.tracker.HttpTrackerClient
import ru.workinprogress.kachok.engine.tracker.TrackerClientByScheme
import ru.workinprogress.kachok.engine.tracker.TrackerProtocol
import ru.workinprogress.kachok.engine.tracker.UdpTrackerClient
import ru.workinprogress.kachok.engine.wire.Handshake
import ru.workinprogress.kachok.engine.wire.PeerWire
import java.nio.file.Files
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * The headless surface: arguments in, a running torrent, progress lines out, an exit code.
 *
 * The wiring itself moved to `TorrentRuntime` in the engine's `jvmMain` when the desktop window
 * needed the same one. What is left here is what makes this a *command*: the rendering, the exit
 * codes, and a shutdown hook that turns a signal into the same clean stop `Command.Stop` is.
 */
class Download(
    private val options: DownloadOptions,
    private val out: Appendable,
    private val err: Appendable,
) {
    suspend fun run(scope: CoroutineScope): Int {
        val dispatchers = EngineDispatchers()
        val sessionJob = SupervisorJob(scope.coroutineContext[Job])
        val sessionScope = CoroutineScope(scope.coroutineContext + dispatchers.io + sessionJob)
        try {
            val metainfo =
                when (val source = options.source) {
                    is TorrentSource.File -> readFile(source) ?: return EXIT_FAILED

                    // A magnet is an identifier and nothing else: the torrent has to be fetched
                    // from the swarm before there is anything to open a file for (BEP 9).
                    is TorrentSource.Magnet -> fetchMagnet(source, dispatchers, sessionScope) ?: return EXIT_FAILED
                }

            return download(metainfo, dispatchers, sessionScope)
        } finally {
            sessionJob.cancelAndJoin()
            dispatchers.close()
        }
    }

    private fun readFile(source: TorrentSource.File): Metainfo? =
        try {
            MetainfoParser.parse(Files.readAllBytes(source.path))
        } catch (unreadable: java.io.IOException) {
            err.appendLine("kachok: cannot read ${source.path}: ${unreadable.message}")
            null
        } catch (malformed: IllegalArgumentException) {
            err.appendLine("kachok: ${source.path} is not a usable torrent: ${malformed.message}")
            null
        }

    /**
     * BEP 9: a magnet link names a torrent and carries none of it.
     *
     * The peers this asks are the link's trackers, plus the DHT's when it is on — which is the one
     * case where `--dht` is not optional in practice: a magnet with no trackers has nowhere else to
     * look.
     */
    private suspend fun fetchMagnet(
        source: TorrentSource.Magnet,
        dispatchers: EngineDispatchers,
        scope: CoroutineScope,
    ): Metainfo? {
        val link =
            try {
                MagnetParser.parse(source.uri)
            } catch (malformed: IllegalArgumentException) {
                err.appendLine("kachok: $source is not a usable magnet link: ${malformed.message}")
                return null
            }
        out.appendLine("${link.displayName ?: "magnet"}: fetching the torrent from the swarm")
        val identity = randomPeerId()
        val pool = BufferPool(capacity = MAGNET_POOL)
        return try {
            MetadataFetcher(
                link = link,
                peerId = identity,
                listenPort = options.port ?: TrackerProtocol.PORT_RANGE.first,
                dialer =
                    SocketPeerDialer(
                        scope,
                        link.infoHash,
                        identity,
                        pool,
                        Handshake.reservedBits(extensionProtocol = true, fastExtension = true),
                    ),
                trackerClient =
                    TrackerClientByScheme(
                        http = HttpTrackerClient(dispatchers.io),
                        udp = UdpTrackerClient(dispatchers.io),
                    ),
                blocking = dispatchers.io,
            ).fetch(scope)
        } catch (unavailable: IllegalArgumentException) {
            err.appendLine("kachok: ${unavailable.message}")
            null
        }
    }

    private suspend fun download(
        metainfo: Metainfo,
        dispatchers: EngineDispatchers,
        sessionScope: CoroutineScope,
    ): Int {
        val runtime =
            TorrentRuntime.open(
                metainfo = metainfo,
                options =
                    RuntimeOptions(
                        directory = options.directory,
                        port = options.port,
                        maxPeers = options.maxPeers,
                        pipelineDepth = options.pipelineDepth,
                        dht = options.dht,
                        uploadLimitBytesPerSecond = options.uploadLimit,
                        downloadLimitBytesPerSecond = options.downloadLimit,
                    ),
                dispatchers = dispatchers,
                scope = sessionScope,
                onResumeFailure = { err.appendLine("kachok: $it") },
                onBindFailure = { err.appendLine("kachok: cannot listen: $it") },
            )
        val session = runtime.session
        val pool = runtime.pool
        out.appendLine("${metainfo.name}: ${metainfo.totalLength} bytes in ${metainfo.pieceCount} pieces")
        runtime.restore()
        session.state.value.let { state ->
            if (state.completedPieces > 0) {
                out.appendLine("resuming with ${state.completedPieces} of ${state.pieceCount} pieces")
            }
        }
        out.appendLine("listening on port ${runtime.listenPort}")
        val runningJob = runtime.start(sessionScope)
        runtime.dhtPort?.let { out.appendLine("dht on udp port $it") }
        val renderer = sessionScope.launch { render(session) }

        // A signal is a request to stop, not a reason to lose the download's progress: the handler
        // asks the session to stop the way `Command.Stop` does — tracker, peers, flush, record —
        // and waits for that sequence, bounded, before letting the process go.
        val interrupted = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val hook =
            Thread {
                interrupted.complete(Unit)
                runBlocking { withTimeoutOrNull(SHUTDOWN_TIMEOUT) { stopped.await() } }
            }
        Runtime.getRuntime().addShutdownHook(hook)

        val finished =
            try {
                val settled =
                    sessionScope.async {
                        session.state.first { state -> state.isComplete || hopeless(state) }
                    }
                select {
                    settled.onAwait { it }
                    interrupted.onAwait {
                        settled.cancel()
                        null
                    }
                }
            } finally {
                renderer.cancel()
                runtime.close()
            }

        if (finished == null) {
            out.appendLine(
                "buffer pool: ${pool.peakOutstanding} of ${pool.capacity} used at peak, " +
                    "${pool.allocated} allocated",
            )
            out.appendLine("stopping")
            runtime.stop()
            val clean = withTimeoutOrNull(SHUTDOWN_TIMEOUT) { runningJob.join() } != null
            stopped.complete(Unit)
            dropHook(hook)
            if (!clean) err.appendLine("kachok: the session did not stop within $SHUTDOWN_TIMEOUT")
            return if (clean) EXIT_OK else EXIT_FAILED
        }
        stopped.complete(Unit)
        dropHook(hook)

        return when {
            finished.isComplete -> {
                out.appendLine(progress(finished))
                out.appendLine("${metainfo.name}: complete")
                out.appendLine(
                    "buffer pool: ${pool.peakOutstanding} of ${pool.capacity} used at peak, " +
                        "${pool.allocated} allocated",
                )
                if (options.seedAfterCompletion) {
                    out.appendLine("seeding; stop with Ctrl-C")
                    session.state.first { false }
                }
                runtime.stop()
                EXIT_OK
            }

            else -> {
                err.appendLine("kachok: ${finished.trackerError ?: finished.lastPeerError ?: "no peers"}")
                runtime.stop()
                EXIT_FAILED
            }
        }
    }

    /**
     * Takes the shutdown hook back off, if there is still a JVM to take it off.
     *
     * `removeShutdownHook` refuses once shutdown has begun, which is exactly the case when the
     * hook itself is what asked the session to stop. That refusal is the expected answer, not a
     * failure: the hook is running, it will finish, and there is nothing to remove.
     */
    private fun dropHook(hook: Thread) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook)
        } catch (shuttingDown: IllegalStateException) {
            // See above: the hook is already running because the signal is what got us here.
        }
    }

    /**
     * Nothing to wait for: every tracker refused and no peer came from anywhere else.
     *
     * Deliberately not "no progress for a while" — a slow swarm is not a failed download, and a
     * client that gives up on one is worse than a client that waits.
     */
    private fun hopeless(state: SessionState): Boolean = state.trackerError != null && state.knownPeers == 0

    private suspend fun render(session: Session) {
        while (true) {
            delay(RENDER_INTERVAL)
            out.appendLine(progress(session.state.value))
        }
    }

    private fun progress(state: SessionState): String {
        val percent = if (state.totalLength == 0L) 0 else state.downloaded * PERCENT / state.totalLength
        return buildString {
            append(state.completedPieces).append('/').append(state.pieceCount).append(" pieces")
            append(" (").append(percent).append("%)")
            append(", ")
                .append(state.connectedPeers)
                .append(" of ")
                .append(state.knownPeers)
                .append(" peers (")
                .append(state.unchokedPeers)
                .append(" unchoked, ")
                .append(state.outstandingRequests)
                .append(" out)")
            if (state.hashFailures > 0) append(", ").append(state.hashFailures).append(" hash failures")
            state.trackerError?.let { append(", tracker: ").append(it) }
            // A degraded session that says nothing is how a stalled download looked for three runs.
            state.sessionError?.let { append(", DEGRADED: ").append(it) }
        }
    }

    /** BEP 20's Azureus style: `-KA0001-` and twelve random bytes. */
    private fun randomPeerId(): PeerId {
        val bytes = ByteArray(PeerId.SIZE)
        "-KA0001-".encodeToByteArray().copyInto(bytes)
        Random.Default.nextBytes(bytes, 8, PeerId.SIZE)
        return PeerId(bytes)
    }

    companion object {
        const val EXIT_OK = 0
        const val EXIT_FAILED = 1
        const val EXIT_USAGE = 2

        /**
         * Enough buffers to fetch a torrent and no more.
         *
         * A metadata fetch talks to a handful of peers about a few dozen kibibytes; sizing this
         * from the piece length would size it from a number the magnet does not carry yet.
         */
        private const val MAGNET_POOL = 64

        private const val PERCENT = 100
        private val RENDER_INTERVAL = 1.seconds

        /**
         * How long a clean stop may take before the process leaves anyway.
         *
         * Bounded because the alternative is a client that cannot be stopped: a peer that will not
         * close or a tracker that will not answer must not be able to hold the process open.
         */
        private val SHUTDOWN_TIMEOUT = 10.seconds
    }
}
