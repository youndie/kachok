package io.github.youndie.kachok.engine.runtime

import io.github.youndie.kachok.engine.dht.Dht
import io.github.youndie.kachok.engine.dht.NodeId
import io.github.youndie.kachok.engine.hex
import io.github.youndie.kachok.engine.io.DatagramKrpcTransport
import io.github.youndie.kachok.engine.io.EngineDispatchers
import io.github.youndie.kachok.engine.io.PeerListener
import io.github.youndie.kachok.engine.io.SocketPeerConnection
import io.github.youndie.kachok.engine.metainfo.Metainfo
import io.github.youndie.kachok.engine.nat.PortMapper
import io.github.youndie.kachok.engine.nat.PortMapping
import io.github.youndie.kachok.engine.session.Command
import io.github.youndie.kachok.engine.storage.FileSet
import io.github.youndie.kachok.engine.tracker.TrackerProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.BindException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

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

    init {
        if (options.dht) useDht(true)
    }

    /** Whether a torrent added now would be given the DHT. */
    public val dhtEnabled: Boolean get() = dht != null

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
            val transport = DatagramKrpcTransport(dispatchers.io)
            dhtTransport = transport
            dht = Dht(self = NodeId.random(), transport = transport)
            transport.start(scope)
        } else {
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
        get() =
            when (val state = mapping) {
                is PortMapping.Mapped -> "mapped to ${state.externalPort}"
                is PortMapping.NotMapped -> "not mapped: ${state.because}"
                PortMapping.NotTried -> "not mapped: no listener to map"
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
    private val mappingJob: Job? =
        listener?.let { bound ->
            scope.launch(dispatchers.io) {
                while (isActive) {
                    val result = mapper.map(bound.port)
                    mapping = result
                    // A refusal is not retried on a timer. A router that does not speak NAT-PMP
                    // will not have learned it in an hour, and asking again every half hour is
                    // noise on somebody's network for no chance of a different answer.
                    val next = (result as? PortMapping.Mapped)?.let { mapper.renewAfter(it) } ?: break
                    delay(next)
                }
            }
        }

    /** Null when the DHT is off, which is what the status bar draws differently from zero nodes. */
    public val dhtPort: Int? get() = dhtTransport?.port

    public val torrents: List<TorrentRuntime> get() = byInfoHash.values.toList()

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
            )
        byInfoHash[key] = runtime
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

    /** Stops one torrent and forgets it. The set stays open; the others keep running. */
    public suspend fun remove(runtime: TorrentRuntime) {
        byInfoHash.remove(runtime.metainfo.infoHash.hex()) ?: return
        runtime.stop()
        runtime.close()
    }

    @Synchronized
    private fun startAccepting() {
        if (accepting) return
        accepting = true
        listener?.start(scope) { socket ->
            // Read first, route second: which torrent this peer wants is in its handshake, and
            // nothing before that says which session should answer.
            val handshake =
                try {
                    SocketPeerConnection.readHandshake(socket)
                } catch (refused: IOException) {
                    return@start
                }
            val runtime = byInfoHash[handshake.infoHash.hex()]
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
                    handshake,
                    runtime.metainfo.infoHash,
                    runtime.peerId,
                    runtime.pool,
                    runtime.reserved,
                )
            runtime.session.send(Command.AcceptPeer(connection))
        }
    }

    override fun close() {
        // **The mapping is given back before anything else goes**, and it is best effort by
        // design: a client that maps a port and exits without releasing leaves a hole in a router
        // it does not own for the rest of the lease. The cancel comes first so the renewal loop
        // cannot re-map what this is dropping.
        mappingJob?.cancel()
        if (mapping is PortMapping.Mapped) listener?.let { mapper.release(it.port) }
        listener?.close()
        dhtTransport?.close()
        byInfoHash.values.forEach { it.close() }
        byInfoHash.clear()
    }

    private companion object {
        private fun AutoCloseable.closeQuietly() {
            try {
                close()
            } catch (ignored: IOException) {
                // A socket we are refusing anyway; there is nothing a caller could do with this.
            }
        }
    }
}
