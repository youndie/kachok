package ru.workinprogress.kachok.engine.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
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
import ru.workinprogress.kachok.engine.metainfo.Metainfo
import ru.workinprogress.kachok.engine.peer.PeerAddress
import ru.workinprogress.kachok.engine.resume.FileResumeStore
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
import java.net.BindException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

/** Everything a caller may choose about one running torrent. */
public class RuntimeOptions(
    public val directory: Path,
    public val port: Int? = null,
    public val maxPeers: Int = DEFAULT_MAX_PEERS,
    public val pipelineDepth: Int = DEFAULT_PIPELINE,
    public val dht: Boolean = false,
    public val uploadLimitBytesPerSecond: Long = NO_LIMIT,
    public val downloadLimitBytesPerSecond: Long = NO_LIMIT,
) {
    public companion object {
        public const val DEFAULT_MAX_PEERS: Int = 50
        public const val DEFAULT_PIPELINE: Int = 16

        /** What `SessionConfig` means by a rate limit of nothing. Not a limit of zero bytes. */
        public const val NO_LIMIT: Long = 0
    }
}

/**
 * One torrent, wired up and running: the single place where the engine's interfaces meet their
 * JVM implementations.
 *
 * **There is one of these and not one per surface.** The headless client and the desktop window
 * both need a buffer pool, a virtual-thread dialer, `FileChannel` storage, a `MessageDigest`
 * hasher and a peer id generated exactly once — and two copies of that list mean two clients, of
 * which the second is always the one that is wrong. What each surface writes for itself is what it
 * does with [state], not how the engine is built.
 *
 * **The listener and the DHT belong to [TorrentSet] and not here.** There is one port for the
 * process and one routing table, and a torrent cannot own either without being the only one. Open
 * a runtime through a set, even when the set holds one.
 *
 * Still a hand-written factory that reads top to bottom rather than a container. It lives in
 * `jvmMain` because every class it names does; nothing in `commonMain` can see it, which is the
 * point.
 */
public class TorrentRuntime internal constructor(
    public val metainfo: Metainfo,
    public val session: Session,
    public val pool: BufferPool,
    private val hasher: MessageDigestPieceHasher,
    private val files: FileSet,
    /** The identity this torrent announced and offers in every handshake. */
    public val peerId: PeerId,
    /** The bits its handshakes carry, which the set has to repeat when it answers one. */
    public val reserved: ByteArray,
    /** The port the tracker was told about, which is the one the set actually bound. */
    public val listenPort: Int,
) : AutoCloseable {
    public val state: StateFlow<SessionState> get() = session.state

    /**
     * Every file this torrent writes, as it actually opened them.
     *
     * Exposed so that removing a torrent *with* its data deletes what was written rather than what
     * a caller thinks was written: the layout of a multi-file torrent — the directory named after
     * it, a path per file — is the `FileSet`'s decision, and a second implementation of it in the
     * window would be a second chance to delete the wrong thing.
     */
    public val paths: List<Path> get() = files.paths

    /**
     * What is already on disk, before a single peer is dialled.
     *
     * A client that announced itself and then discovered it already had half the torrent would
     * have asked the swarm for it first.
     */
    public suspend fun restore(): Unit = session.restore(hasher)

    /**
     * Starts the session in [scope].
     *
     * The scope is the caller's rather than one held here, so a cancelled scope takes the torrent
     * with it and there is no second lifetime to get wrong. Incoming peers arrive through the
     * set's listener, which is already accepting.
     */
    public fun start(scope: CoroutineScope): Job = session.start(scope).also { running = it }

    private var running: Job? = null

    /**
     * Waits for the session's own job to finish, which is what a clean stop actually is.
     *
     * Not `state.first { … }`: `Command.Stop` is a request, and the announce, the closes, the
     * flush and the record all happen after it and after the last state a reader sees.
     */
    public suspend fun awaitStopped() {
        running?.join()
    }

    /**
     * Give up the peers and keep everything else.
     *
     * Not [stop] followed by a fresh [start]: that re-opens the files, re-reads the resume record
     * and re-verifies whatever the record does not vouch for. A pause keeps the session, so
     * resuming costs one announce.
     */
    public suspend fun pause(): Unit = session.send(Command.Pause)

    public suspend fun resume(): Unit = session.send(Command.Resume)

    /** Announce *stopped*, close the peers, flush, record. Bounded by the caller, not here. */
    public suspend fun stop(): Unit = session.send(Command.Stop)

    /** Closes the files. The sockets are the set's, and outlive one torrent. */
    override fun close() {
        files.close()
    }

    public companion object {
        /**
         * Opens the files and builds the session. Nothing runs until [start].
         *
         * [onResumeFailure] rather than an exception: a resume record that cannot be written is a
         * torrent that will re-verify on the next run, which is slow and not fatal, and the surface
         * decides whether that is a line on stderr or a banner.
         */
        internal fun open(
            metainfo: Metainfo,
            options: RuntimeOptions,
            dispatchers: EngineDispatchers,
            scope: CoroutineScope,
            listenPort: Int,
            dht: Dht?,
            onResumeFailure: (String) -> Unit = {},
        ): TorrentRuntime {
            Files.createDirectories(options.directory)
            val files = FileSet.open(options.directory, metainfo)
            val pool = BufferPool(capacity = poolCapacity(metainfo, options.maxPeers))
            val hasher = MessageDigestPieceHasher(dispatchers.io)
            val port = listenPort
            // One identity, announced to the tracker and offered in every handshake. Generating it
            // twice would have told the tracker about a peer no swarm member ever meets.
            val identity = randomPeerId()
            // The bits in every handshake this client sends and accepts, and the same array the
            // session is told about — both extensions are two-sided, and a second place recording
            // "we advertised this" is a second place for it to be wrong.
            val reserved = Handshake.reservedBits(extensionProtocol = true, fastExtension = true)
            val session =
                Session(
                    metainfo = metainfo,
                    peerId = identity,
                    listenPort = port,
                    dialer = SocketPeerDialer(scope, metainfo.infoHash, identity, pool, reserved),
                    // Most public torrents announce over UDP; the scheme in the URL decides,
                    // tracker by tracker, and an announce list may mix them.
                    trackerClient =
                        TrackerClientByScheme(
                            http = HttpTrackerClient(dispatchers.io),
                            udp = UdpTrackerClient(dispatchers.io),
                        ),
                    hasher = hasher,
                    storage = FileStorage(PieceLayout(metainfo), files, pool),
                    resume =
                        FileResumeStore(
                            path = options.directory.resolve("${metainfo.name}.resume"),
                            infoHash = metainfo.infoHash,
                            pieceCount = metainfo.pieceCount,
                            dispatcher = dispatchers.io,
                            onFailure = onResumeFailure,
                        ),
                    blocking = dispatchers.io,
                    dht = dht,
                    config =
                        SessionConfig(
                            maxStartedPieces = STARTED_PIECES,
                            pipelineDepth = options.pipelineDepth,
                            maxPeers = options.maxPeers,
                            reserved = reserved,
                            dhtBootstrap = if (dht != null) BOOTSTRAP_NODES else emptyList(),
                            uploadLimitBytesPerSecond = options.uploadLimitBytesPerSecond,
                            downloadLimitBytesPerSecond = options.downloadLimitBytesPerSecond,
                        ),
                )
            return TorrentRuntime(
                metainfo = metainfo,
                session = session,
                pool = pool,
                hasher = hasher,
                files = files,
                peerId = identity,
                reserved = reserved,
                listenPort = port,
            )
        }

        /**
         * BEP 5's public bootstrap nodes: where a client with an empty routing table starts.
         *
         * Three of them because any one may be down, and they are the addresses every mainstream
         * client ships. A DHT with no way in is a DHT that is off.
         */
        private val BOOTSTRAP_NODES =
            listOf(
                PeerAddress("router.bittorrent.com", 6881),
                PeerAddress("dht.transmissionbt.com", 6881),
                PeerAddress("router.utorrent.com", 6881),
            )

        private const val STARTED_PIECES = 8
        private const val MIN_POOL = 64

        /**
         * Buffers for every block of every piece in flight, plus one read in progress per peer.
         *
         * **The second term is the number of peers, not one peer's pipeline**, and getting that
         * wrong is measurable: a block occupies a buffer from the moment its read begins, so every
         * connected peer can hold one that belongs to no started piece yet. With the old formula
         * the pool peaked at 117 of 144 against a real swarm — 81 % of a cap a faster link would
         * have hit, and hitting it throttles the download silently rather than breaking anything
         * (research §1.2c).
         */
        private fun poolCapacity(
            metainfo: Metainfo,
            maxPeers: Int,
        ): Int {
            val blocksPerPiece = (metainfo.pieceLength + PeerWire.BLOCK_SIZE - 1) / PeerWire.BLOCK_SIZE
            return (STARTED_PIECES * blocksPerPiece + maxPeers).coerceAtLeast(MIN_POOL)
        }

        /** BEP 20's Azureus style: `-KA0001-` and twelve random bytes. */
        private fun randomPeerId(): PeerId {
            val bytes = ByteArray(PeerId.SIZE)
            "-KA0001-".encodeToByteArray().copyInto(bytes)
            Random.Default.nextBytes(bytes, 8, PeerId.SIZE)
            return PeerId(bytes)
        }
    }
}
