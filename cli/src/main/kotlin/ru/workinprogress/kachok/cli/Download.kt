package ru.workinprogress.kachok.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.workinprogress.kachok.engine.PeerId
import ru.workinprogress.kachok.engine.hash.MessageDigestPieceHasher
import ru.workinprogress.kachok.engine.io.BufferPool
import ru.workinprogress.kachok.engine.io.EngineDispatchers
import ru.workinprogress.kachok.engine.io.SocketPeerDialer
import ru.workinprogress.kachok.engine.metainfo.Metainfo
import ru.workinprogress.kachok.engine.metainfo.MetainfoParser
import ru.workinprogress.kachok.engine.session.Command
import ru.workinprogress.kachok.engine.session.Session
import ru.workinprogress.kachok.engine.session.SessionConfig
import ru.workinprogress.kachok.engine.session.SessionState
import ru.workinprogress.kachok.engine.storage.FileSet
import ru.workinprogress.kachok.engine.storage.FileStorage
import ru.workinprogress.kachok.engine.storage.PieceLayout
import ru.workinprogress.kachok.engine.tracker.HttpTrackerClient
import ru.workinprogress.kachok.engine.tracker.TrackerProtocol
import ru.workinprogress.kachok.engine.wire.PeerWire
import java.nio.file.Files
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * The one place in phase 1 that names concrete JVM classes.
 *
 * Everything the engine needs is an interface, and this is where those interfaces meet their
 * implementations: a buffer pool, a virtual-thread dialer, `FileChannel` storage, a `MessageDigest`
 * hasher, a `java.net.http` tracker client. There is no dependency-injection container; a factory
 * that reads top to bottom is the entire wiring, and phase 2's UI writes its own rather than
 * inheriting a framework.
 */
class Download(
    private val options: DownloadOptions,
    private val out: Appendable,
    private val err: Appendable,
) {
    suspend fun run(scope: CoroutineScope): Int {
        val metainfo =
            try {
                MetainfoParser.parse(Files.readAllBytes(options.torrent))
            } catch (unreadable: java.io.IOException) {
                err.appendLine("kachok: cannot read ${options.torrent}: ${unreadable.message}")
                return EXIT_FAILED
            } catch (malformed: IllegalArgumentException) {
                err.appendLine("kachok: ${options.torrent} is not a usable torrent: ${malformed.message}")
                return EXIT_FAILED
            }

        Files.createDirectories(options.directory)
        val files = FileSet.open(options.directory, metainfo)
        val dispatchers = EngineDispatchers()
        val sessionJob = SupervisorJob(scope.coroutineContext[Job])
        val sessionScope = CoroutineScope(scope.coroutineContext + dispatchers.io + sessionJob)

        try {
            return download(metainfo, files, dispatchers, sessionScope)
        } finally {
            sessionJob.cancelAndJoin()
            files.close()
            dispatchers.close()
        }
    }

    private suspend fun download(
        metainfo: Metainfo,
        files: FileSet,
        dispatchers: EngineDispatchers,
        sessionScope: CoroutineScope,
    ): Int {
        val pool = BufferPool(capacity = poolCapacity(metainfo))
        val port = options.port ?: TrackerProtocol.PORT_RANGE.first
        // One identity, announced to the tracker and offered in every handshake. Generating it
        // twice would have told the tracker about a peer no swarm member ever meets.
        val identity = randomPeerId()
        val session =
            Session(
                metainfo = metainfo,
                peerId = identity,
                listenPort = port,
                dialer = SocketPeerDialer(sessionScope, metainfo.infoHash, identity, pool),
                trackerClient = HttpTrackerClient(dispatchers.io),
                hasher = MessageDigestPieceHasher(dispatchers.io),
                storage = FileStorage(PieceLayout(metainfo), files),
                config =
                    SessionConfig(
                        maxStartedPieces = STARTED_PIECES,
                        pipelineDepth = options.pipelineDepth,
                        maxPeers = options.maxPeers,
                    ),
            )

        out.appendLine("${metainfo.name}: ${metainfo.totalLength} bytes in ${metainfo.pieceCount} pieces")
        session.start(sessionScope)
        val renderer = sessionScope.launch { render(session) }

        val finished =
            session.state.first { state ->
                state.isComplete || hopeless(state)
            }
        renderer.cancel()

        return when {
            finished.isComplete -> {
                out.appendLine(progress(finished))
                out.appendLine("${metainfo.name}: complete")
                if (options.seedAfterCompletion) {
                    out.appendLine("seeding; stop with Ctrl-C")
                    session.state.first { false }
                }
                session.send(Command.Stop)
                EXIT_OK
            }

            else -> {
                err.appendLine("kachok: ${finished.trackerError ?: finished.lastPeerError ?: "no peers"}")
                session.send(Command.Stop)
                EXIT_FAILED
            }
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
                .append(" peers")
            if (state.hashFailures > 0) append(", ").append(state.hashFailures).append(" hash failures")
            state.trackerError?.let { append(", tracker: ").append(it) }
        }
    }

    /**
     * Buffers enough for every piece the picker may start at once, plus slack for blocks in flight
     * that belong to none of them yet. A pool smaller than the picker's working set deadlocks the
     * writer, so the two numbers are chosen together (research Risk 2).
     */
    private fun poolCapacity(metainfo: Metainfo): Int {
        val blocksPerPiece = (metainfo.pieceLength + PeerWire.BLOCK_SIZE - 1) / PeerWire.BLOCK_SIZE
        return (STARTED_PIECES * blocksPerPiece + options.pipelineDepth).coerceAtLeast(MIN_POOL)
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

        private const val STARTED_PIECES = 8
        private const val MIN_POOL = 64
        private const val PERCENT = 100
        private val RENDER_INTERVAL = 1.seconds
    }
}
