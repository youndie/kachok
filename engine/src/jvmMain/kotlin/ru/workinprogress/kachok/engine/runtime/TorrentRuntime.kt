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
    /**
     * Files this client will not fetch, by their index in the metainfo.
     *
     * Decided when the torrent is added and never after: changing it while a torrent runs needs the
     * picker to give back pieces it has started, which is the item's own not-covered case.
     */
    public val unwantedFiles: Set<Int> = emptySet(),
    /** Ask for pieces in order rather than rarest first. Slower, and a worse swarm member. */
    public val sequential: Boolean = false,
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
    /**
     * Where this torrent saves, which since B-81 is not always where the settings say.
     *
     * A restored torrent keeps the folder it was added with, and the add dialog could always send
     * one elsewhere — so a caller that wants to show or resolve a path has to ask the torrent
     * rather than the preferences. The window showed the settings' default under *Save to* for
     * every torrent, including the ones that are not there.
     */
    public val directory: Path,
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
     *
     * [paused] starts it stopped rather than starting it and pausing it: a torrent that was paused
     * when the window closed must not announce and dial on the way to being paused again.
     */
    public fun start(
        scope: CoroutineScope,
        paused: Boolean = false,
    ): Job = session.start(scope, paused).also { running = it }

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

    /**
     * Read the whole torrent off the disk again and hash it, trusting no record.
     *
     * Transfers stop for the length of the pass and start again after it; the tracker is not told,
     * and a torrent that was paused stays paused.
     */
    public suspend fun recheck(): Unit = session.send(Command.Recheck)

    /**
     * Ask for pieces in order, or stop.
     *
     * Its own call rather than a field on [reconfigure]'s options: those are the *settings*, sent
     * to every torrent at once when somebody edits them, and this is a decision about one film
     * ([B-89](../../../../../../../../docs/backlog/B-89-sequential-on-a-running-torrent.md)).
     * Sending it through the settings would turn one torrent's order into all of them.
     */
    public suspend fun sequential(inOrder: Boolean): Unit = session.send(Command.Reconfigure(sequential = inOrder))

    /**
     * New values for the settings that can change under a running torrent.
     *
     * Everything else in [RuntimeOptions] is decided when the torrent is opened: the directory is
     * where the files are, the port is the set's, the pipeline depth is the buffer pool's working
     * set, and which files to skip is what the picker was told once.
     */
    public suspend fun reconfigure(options: RuntimeOptions): Unit =
        session.send(
            Command.Reconfigure(
                maxPeers = options.maxPeers,
                uploadLimitBytesPerSecond = options.uploadLimitBytesPerSecond,
                downloadLimitBytesPerSecond = options.downloadLimitBytesPerSecond,
            ),
        )

    /** Ask the trackers again, out of turn. Does not reset the interval they asked for. */
    public suspend fun announce(): Unit = session.send(Command.Announce)

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
                            // The info hash in the name, not just the torrent's name. Two different
                            // torrents can be called `payload.bin`, and both wrote to
                            // `payload.bin.resume`: whichever saved last won, and the loser's record
                            // was then refused on the next start for the piece count — the resume
                            // path doing the right thing with a file it should never have been
                            // handed (B-60).
                            path = options.directory.resolve(resumeName(metainfo)),
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
                    unwantedFiles = options.unwantedFiles,
                    sequential = options.sequential,
                )
            return TorrentRuntime(
                directory = options.directory,
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

        /**
         * `payload.bin.2b3a91c4.resume` — the torrent's name and eight hex of its info hash.
         *
         * The name is kept because a directory of records nobody can read is its own problem; the
         * hash is what makes a record one torrent's. Two different torrents can be called
         * `payload.bin` and both wrote to `payload.bin.resume` (B-60).
         */
        internal fun resumeName(metainfo: Metainfo): String {
            val hash =
                metainfo.infoHash.bytes
                    .take(RESUME_HASH_BYTES)
                    .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            return "${metainfo.name}.$hash.resume"
        }

        private const val RESUME_HASH_BYTES = 4

        /**
         * BEP 20's Azureus style: `-KA0100-` and twelve random bytes.
         *
         * The four digits are the version — 0.1.0.0 — and they were `0001` until the *Peers* tab
         * made this client visible to itself, announcing 0.0.0.1 to every swarm it joined.
         */
        private fun randomPeerId(): PeerId {
            val bytes = ByteArray(PeerId.SIZE)
            "-KA0100-".encodeToByteArray().copyInto(bytes)
            Random.Default.nextBytes(bytes, 8, PeerId.SIZE)
            return PeerId(bytes)
        }
    }
}
