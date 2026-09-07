package io.github.youndie.kachok.engine.metainfo

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.PeerId
import io.github.youndie.kachok.engine.peer.PeerAddress
import io.github.youndie.kachok.engine.peer.PeerConnection
import io.github.youndie.kachok.engine.peer.PeerDialer
import io.github.youndie.kachok.engine.peer.PeerEvent
import io.github.youndie.kachok.engine.tracker.AnnounceEvent
import io.github.youndie.kachok.engine.tracker.AnnounceRequest
import io.github.youndie.kachok.engine.tracker.TrackerClient
import io.github.youndie.kachok.engine.tracker.TrackerException
import io.github.youndie.kachok.engine.wire.ExtensionHandshake
import io.github.youndie.kachok.engine.wire.Message
import io.github.youndie.kachok.engine.wire.MetadataMessage
import io.github.youndie.kachok.engine.wire.WireException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Turns a magnet link into a [Metainfo] by asking peers for the info dictionary (BEP 9).
 *
 * A session before the session. It cannot be the real one: [io.github.youndie.kachok.engine.session.Session]
 * needs a `Metainfo` to build a picker, a storage layout and a piece hasher, and none of those
 * exist yet — the whole point is that this client knows an info hash and nothing else. So this is
 * the smallest thing that can hold a wire conversation: announce, dial, handshake, ask, verify.
 *
 * **State is confined to one coroutine, by a channel rather than by a dispatcher.** Every peer
 * runs its own coroutine on whatever thread it lands on and only *sends*; one loop owns the
 * assembly and decides what to ask for next. The session buys the same guarantee with
 * `limitedParallelism(1)`; here the shape is small enough that the channel says it more plainly.
 */
public class MetadataFetcher(
    private val link: MagnetLink,
    private val peerId: PeerId,
    private val listenPort: Int,
    private val dialer: PeerDialer,
    private val trackerClient: TrackerClient,
    private val maxPeers: Int = DEFAULT_MAX_PEERS,
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val blocking: CoroutineDispatcher? = null,
    /** Peers from somewhere other than the link's trackers — the DHT, or a caller that knows one. */
    private val extraPeers: List<PeerAddress> = emptyList(),
) {
    private class Peer(
        val connection: PeerConnection,
        val metadataId: Int,
        val size: Int,
    )

    private sealed interface Event {
        class Ready(
            val peer: Peer,
        ) : Event

        class Block(
            val piece: Int,
            val bytes: ByteArray,
        ) : Event

        class Refused(
            val peer: Peer,
            val piece: Int,
        ) : Event

        /** A peer that could not be reached at all, and what the dial said. */
        class Unreachable(
            val address: PeerAddress,
            val reason: String,
        ) : Event
    }

    /**
     * Why the last dial failed, for the message a user reads when nothing worked.
     *
     * Written only by the collecting loop, which is the one coroutine that owns this object's
     * state. "No peer answered" on its own tells nobody whether the swarm is asleep or the network
     * is in the way.
     */
    private var lastDialFailure: String? = null

    /**
     * Fetches, or throws [MetainfoException] saying which of the two ways it failed.
     *
     * "No peer had it" and "what a peer sent was not it" are different problems for whoever reads
     * the message: the first is a swarm that has not woken up, the second is a peer that lied.
     */
    public suspend fun fetch(scope: CoroutineScope): Metainfo {
        val peers = (announce() + extraPeers).distinct()
        if (peers.isEmpty()) {
            throw MetainfoException("no peers to ask for the metadata of ${link.infoHash.bytes.size}-byte hash")
        }
        val events = Channel<Event>(Channel.UNLIMITED)
        val connections = mutableListOf<PeerConnection>()
        val jobs = mutableListOf<Job>()
        try {
            peers.take(maxPeers).forEach { address ->
                jobs += scope.launch { talk(address, events, connections) }
            }
            val bytes =
                withTimeoutOrNull(timeout) { collect(events) }
                    ?: throw MetainfoException(
                        "no peer answered with the metadata within $timeout; ${peers.size} were asked" +
                            (lastDialFailure?.let { ", and the last dial said: $it" } ?: ""),
                    )
            return bytes.finish(link.trackers, link.displayName)
        } finally {
            jobs.forEach { it.cancel() }
            connections.forEach { it.close() }
            events.close()
        }
    }

    /**
     * The one loop that owns the assembly.
     *
     * The assembly cannot exist before the first peer says how big the metadata is, which is why
     * `metadata_size` is a handshake field and not something this client can ask for.
     */
    private suspend fun collect(events: Channel<Event>): MetadataAssembly {
        var assembly: MetadataAssembly? = null
        val ready = mutableListOf<Peer>()
        var next = 0
        for (event in events) {
            when (event) {
                is Event.Ready -> {
                    ready += event.peer
                    if (assembly == null) assembly = MetadataAssembly(link.infoHash, event.peer.size)
                    ask(assembly, ready, next++)
                }

                is Event.Block -> {
                    val current = assembly ?: continue
                    if (current.accept(event.piece, event.bytes) && current.isComplete) return current
                    ask(current, ready, next++)
                }

                is Event.Unreachable -> {
                    lastDialFailure = "${event.address}: ${event.reason}"
                }

                is Event.Refused -> {
                    // A peer that will not serve one block is asked for nothing else; somebody
                    // else has the same block and BEP 9 gives no reason to argue.
                    ready.remove(event.peer)
                    ask(assembly ?: continue, ready, next++)
                }
            }
        }
        throw MetainfoException("every peer went away before the metadata was complete")
    }

    /** Asks one peer for one missing block, choosing both round robin. */
    private suspend fun ask(
        assembly: MetadataAssembly,
        ready: List<Peer>,
        turn: Int,
    ) {
        if (ready.isEmpty()) return
        val missing = assembly.missing()
        if (missing.isEmpty()) return
        val peer = ready[turn % ready.size]
        val piece = missing[turn % missing.size]
        peer.connection.send(
            Message.Extended(peer.metadataId, MetadataMessage.request(piece).encode()),
        )
    }

    /** One peer, from the dial to the last message it sends. */
    private suspend fun talk(
        address: PeerAddress,
        events: Channel<Event>,
        connections: MutableList<PeerConnection>,
    ) {
        val connection =
            try {
                if (blocking != null) withContext(blocking) { dialer.connect(address) } else dialer.connect(address)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (refused: Exception) {
                // Kept rather than swallowed: half the addresses a tracker gives out are dead, so
                // one failed dial is not news — but "nothing worked and nobody said why" is the
                // message a user would otherwise be left with.
                events.send(
                    Event.Unreachable(address, refused.message ?: refused::class.simpleName ?: "refused"),
                )
                return
            }
        connections += connection
        if (!connection.handshake.supportsExtensionProtocol) return
        connection.send(
            Message.Extended(
                ExtensionHandshake.HANDSHAKE_ID,
                ExtensionHandshake(
                    extensions = mapOf(ExtensionHandshake.UT_METADATA to METADATA_ID),
                    listenPort = listenPort,
                ).encode(),
            ),
        )
        var peer: Peer? = null
        for (event in connection.events) {
            val message = (event as? PeerEvent.Received)?.message as? Message.Extended ?: continue
            if (message.extensionId == ExtensionHandshake.HANDSHAKE_ID) {
                if (peer != null) continue
                peer = readHandshake(connection, message) ?: continue
                events.send(Event.Ready(peer))
                continue
            }
            if (message.extensionId != METADATA_ID) continue
            val current = peer ?: continue
            val metadata =
                try {
                    MetadataMessage.decode(message.payload)
                } catch (malformed: WireException) {
                    continue
                }
            when (metadata.type) {
                MetadataMessage.DATA -> {
                    events.send(Event.Block(metadata.piece, metadata.data))
                }

                MetadataMessage.REJECT -> {
                    events.send(Event.Refused(current, metadata.piece))
                }

                else -> {}
            }
        }
    }

    private fun readHandshake(
        connection: PeerConnection,
        message: Message.Extended,
    ): Peer? {
        val handshake =
            try {
                ExtensionHandshake.decode(message.payload)
            } catch (malformed: WireException) {
                return null
            }
        val id = handshake.id(ExtensionHandshake.UT_METADATA) ?: return null
        // `metadata_size` arrives from a stranger and is what gets allocated.
        val size = handshake.metadataSize?.takeIf { MetadataAssembly.isPlausibleSize(it) } ?: return null
        return Peer(connection, id, size)
    }

    /**
     * The magnet's trackers, asked for peers.
     *
     * `left` is a number this client cannot know: the torrent's length is in the metadata it is
     * trying to fetch. One block's worth is the conventional placeholder — nonzero, so the tracker
     * does not take this client for a seed and answer with leechers only.
     */
    private suspend fun announce(): List<PeerAddress> {
        val request =
            AnnounceRequest(
                infoHash = link.infoHash,
                peerId = peerId,
                port = listenPort,
                uploaded = 0,
                downloaded = 0,
                left = MetadataMessage.BLOCK_SIZE.toLong(),
                event = AnnounceEvent.STARTED,
            )
        link.trackers.forEach { tracker ->
            try {
                return trackerClient.announce(tracker, request).peers
            } catch (refused: TrackerException) {
                // A dead tracker is not a dead swarm; the next one may answer.
            }
        }
        return emptyList()
    }

    public companion object {
        /** The id this client asks peers to send `ut_metadata` under, shared with the session. */
        public const val METADATA_ID: Int = ExtensionHandshake.ID_UT_METADATA

        public const val DEFAULT_MAX_PEERS: Int = 20

        /** Long enough for a swarm to answer, short enough that a user sees the failure. */
        public val DEFAULT_TIMEOUT: Duration = 60.seconds
    }
}
