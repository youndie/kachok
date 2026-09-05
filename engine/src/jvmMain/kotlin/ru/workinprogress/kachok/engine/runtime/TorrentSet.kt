package ru.workinprogress.kachok.engine.runtime

import kotlinx.coroutines.CoroutineScope
import ru.workinprogress.kachok.engine.dht.Dht
import ru.workinprogress.kachok.engine.dht.NodeId
import ru.workinprogress.kachok.engine.io.DatagramKrpcTransport
import ru.workinprogress.kachok.engine.io.EngineDispatchers
import ru.workinprogress.kachok.engine.io.PeerListener
import ru.workinprogress.kachok.engine.io.SocketPeerConnection
import ru.workinprogress.kachok.engine.metainfo.Metainfo
import ru.workinprogress.kachok.engine.session.Command
import ru.workinprogress.kachok.engine.tracker.TrackerProtocol
import java.io.IOException
import java.net.BindException
import java.util.concurrent.ConcurrentHashMap

/** What the whole process chooses, as opposed to what one torrent does. */
public class SetOptions(
    public val port: Int? = null,
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
        val key = metainfo.infoHash.bytes.toHex()
        require(!byInfoHash.containsKey(key)) { "this set already has ${metainfo.name}" }
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

    /** Stops one torrent and forgets it. The set stays open; the others keep running. */
    public suspend fun remove(runtime: TorrentRuntime) {
        byInfoHash.remove(
            runtime.metainfo.infoHash.bytes
                .toHex(),
        ) ?: return
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
            val runtime = byInfoHash[handshake.infoHash.bytes.toHex()]
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
        listener?.close()
        dhtTransport?.close()
        byInfoHash.values.forEach { it.close() }
        byInfoHash.clear()
    }

    private companion object {
        private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

        private fun AutoCloseable.closeQuietly() {
            try {
                close()
            } catch (ignored: IOException) {
                // A socket we are refusing anyway; there is nothing a caller could do with this.
            }
        }
    }
}
