package io.github.youndie.kachok.cli.serve

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import io.github.youndie.kachok.engine.hex
import io.github.youndie.kachok.engine.metainfo.MetainfoParser
import io.github.youndie.kachok.engine.runtime.RuntimeOptions
import io.github.youndie.kachok.engine.runtime.TorrentRuntime
import io.github.youndie.kachok.engine.runtime.TorrentSet
import io.github.youndie.kachok.engine.session.SessionState
import io.github.youndie.kachok.wire.FileState
import io.github.youndie.kachok.wire.PeerState
import io.github.youndie.kachok.wire.Reply
import io.github.youndie.kachok.wire.Request
import io.github.youndie.kachok.wire.Snapshot
import io.github.youndie.kachok.wire.TorrentState
import io.github.youndie.kachok.wire.TrackerState
import java.nio.file.Path
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The headless client with a socket on it: one engine, and a client that is not in this process.
 *
 * Every connected client is sent the whole state once a second and may send requests back. That is
 * the same shape the desktop window uses in-process — sample the sessions on a tick, turn each into
 * rows — so the two surfaces are the same program with a socket in the middle of one of them
 * ([B-40](../../../../../../../../docs/backlog/B-40-wasmjs-ui-is-a-client-of-the-headless-engine.md)).
 *
 * **The whole state and not a delta.** A snapshot of sixteen torrents is a few kilobytes of JSON
 * once a second on loopback; a delta protocol is a second thing that can be wrong, and its failure
 * mode is a client whose numbers drift instead of one that is briefly behind.
 */
internal class Backend(
    private val set: TorrentSet,
    private val scope: CoroutineScope,
    /** Where a torrent added over the wire is saved when the request names nowhere. */
    private val directory: Path,
    private val tick: Duration = 1.seconds,
    port: Int = DEFAULT_PORT,
    allowedOrigins: Set<String> = emptySet(),
) : AutoCloseable {
    /**
     * **`encodeDefaults`, and it is not a preference.**
     *
     * Without it a torrent with no peers has no `peers` key at all and an empty list is *absent*
     * rather than empty — so a JavaScript client reads `undefined` and throws on `.length`, which
     * is exactly what the first browser to connect to this did. Every client would have to know
     * every default in this protocol to defend against it; a few dozen bytes a second on loopback
     * is the cheaper half of that trade.
     *
     * `ignoreUnknownKeys` for the other direction: a newer client sending a field this backend does
     * not know is not an error, and refusing it would make the two halves impossible to upgrade
     * separately.
     */
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val clients = java.util.concurrent.CopyOnWriteArrayList<WebSocketServer.Connection>()

    /** Counts snapshots so a client can order them. Atomic: a new client is served off its own thread. */
    private val sampled =
        java.util.concurrent.atomic
            .AtomicLong()

    private val server =
        WebSocketServer(port = port, allowedOrigins = allowedOrigins) { connection ->
            connection.onMessage = { text -> handle(connection, text) }
            connection.onClose = { clients.remove(connection) }
            clients += connection
            // Straight away, rather than up to a tick later: a client that has just connected and
            // sees an empty window for a second cannot tell that from a backend with nothing in it.
            connection.sendText(json.encodeToString<Reply>(Reply.State(snapshot())))
        }

    val port: Int get() = server.port

    fun start() {
        server.start()
        scope.launch {
            while (scope.isActive) {
                delay(tick)
                if (clients.isEmpty()) continue
                val text = json.encodeToString<Reply>(Reply.State(snapshot()))
                clients.toList().forEach { connection ->
                    try {
                        if (connection.isOpen) connection.sendText(text) else clients.remove(connection)
                    } catch (gone: java.io.IOException) {
                        clients.remove(connection)
                    }
                }
            }
        }
    }

    private fun handle(
        connection: WebSocketServer.Connection,
        text: String,
    ) {
        val request =
            try {
                json.decodeFromString<Request>(text)
            } catch (unreadable: Exception) {
                // Deliberately broad: `kotlinx.serialization` throws several unrelated types for a
                // malformed document, and a client's bad JSON must not take the backend down.
                refuse(connection, "?", "not a request this backend understands: ${unreadable.message}")
                return
            }
        scope.launch { carryOut(connection, request) }
    }

    private suspend fun carryOut(
        connection: WebSocketServer.Connection,
        request: Request,
    ) {
        when (request) {
            is Request.Pause -> {
                onTorrent(connection, request, request.infoHash) { it.pause() }
            }

            is Request.Resume -> {
                onTorrent(connection, request, request.infoHash) { it.resume() }
            }

            is Request.Recheck -> {
                onTorrent(connection, request, request.infoHash) { it.recheck() }
            }

            is Request.Announce -> {
                onTorrent(connection, request, request.infoHash) { it.announce() }
            }

            is Request.Remove -> {
                onTorrent(connection, request, request.infoHash) { set.remove(it) }
            }

            is Request.RemoveWithData -> {
                onTorrent(connection, request, request.infoHash) { runtime ->
                    // Read before the remove: `remove` closes the files, and a closed `FileSet` is
                    // not somewhere to ask what it was writing.
                    val paths = runtime.paths
                    set.remove(runtime)
                    paths.forEach { path ->
                        try {
                            java.nio.file.Files
                                .deleteIfExists(path)
                        } catch (undeletable: java.io.IOException) {
                            refuse(connection, "removeWithData", "$path was not deleted: ${undeletable.message}")
                        }
                    }
                }
            }

            is Request.AddTorrent -> {
                val bytes =
                    try {
                        Base64.getDecoder().decode(request.base64)
                    } catch (notBase64: IllegalArgumentException) {
                        refuse(connection, "addTorrent", "the torrent is not base64: ${notBase64.message}")
                        return
                    }
                add(connection, "addTorrent", request.directory) { MetainfoParser.parse(bytes) }
            }

            is Request.AddMagnet -> {
                refuse(
                    connection,
                    "addMagnet",
                    "a magnet has to be fetched from the swarm before it is a torrent, and this " +
                        "backend does not do that yet",
                )
            }
        }
    }

    private suspend fun add(
        connection: WebSocketServer.Connection,
        name: String,
        directory: String?,
        parse: () -> io.github.youndie.kachok.engine.metainfo.Metainfo,
    ) {
        val metainfo =
            try {
                parse()
            } catch (malformed: IllegalArgumentException) {
                refuse(connection, name, "not a usable torrent: ${malformed.message}")
                return
            }
        val runtime =
            try {
                set.add(metainfo, RuntimeOptions(directory = directory?.let { Path.of(it) } ?: this.directory))
            } catch (refused: IllegalArgumentException) {
                // The set refuses a torrent it already has, and one whose files another one owns.
                refuse(connection, name, refused.message.orEmpty())
                return
            }
        runtime.restore()
        runtime.start(scope)
    }

    private suspend fun onTorrent(
        connection: WebSocketServer.Connection,
        request: Request,
        infoHash: String,
        act: suspend (TorrentRuntime) -> Unit,
    ) {
        val runtime = set.torrents.firstOrNull { it.metainfo.infoHash.hex() == infoHash }
        if (runtime == null) {
            refuse(connection, request::class.simpleName.orEmpty(), "no torrent here with info hash $infoHash")
            return
        }
        act(runtime)
    }

    private fun refuse(
        connection: WebSocketServer.Connection,
        request: String,
        why: String,
    ) {
        try {
            connection.sendText(json.encodeToString<Reply>(Reply.Refused(request, why)))
        } catch (gone: java.io.IOException) {
            // The only way this fails is a client that went away between asking and being told no,
            // and there is nobody left to tell. Not silence for its own sake: a line on stderr per
            // refusal to a departed client is a log that fills when somebody reloads a page.
            clients.remove(connection)
        }
    }

    private fun snapshot(): Snapshot =
        Snapshot(
            torrents = set.torrents.map { it.state.value.onTheWire() },
            listenPort = set.listenPort,
            dhtNodes =
                if (set.dhtEnabled) {
                    set.torrents
                        .firstOrNull()
                        ?.state
                        ?.value
                        ?.dhtNodes ?: 0
                } else {
                    null
                },
            sequence = sampled.incrementAndGet(),
        )

    override fun close() {
        server.close()
    }

    internal companion object {
        /** Zero by default in tests; a fixed port is a decision the command line takes. */
        const val DEFAULT_PORT: Int = 0
    }
}

/**
 * The engine's state as the wire's.
 *
 * Not a mechanical copy: the rates a surface draws are per peer in the engine and summed here,
 * because a client that summed them would be a second implementation of a figure and this one
 * already exists in the desktop window.
 */
internal fun SessionState.onTheWire(): TorrentState =
    TorrentState(
        infoHash = infoHash.hex(),
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
        verifiedPieces = verifiedPieces,
        verifyingOf = verifyingOf,
        downBytesPerSecond = peers.sumOf { it.downBytesPerSecond },
        upBytesPerSecond = peers.sumOf { it.upBytesPerSecond },
        paused = paused,
        isComplete = isComplete,
        trackerError = trackerError,
        lastPeerError = lastPeerError,
        sessionError = sessionError,
        files = files.map { FileState(it.path, it.length, it.verifiedBytes, it.wanted) },
        peers =
            peers.map {
                PeerState(it.address, it.client, it.choking, it.interested, it.downBytesPerSecond)
            },
        trackers = trackers.map { TrackerState(it.url, it.status.name, it.message) },
    )
