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
import kotlinx.coroutines.delay
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
    /** How many dials may be open at once. A dial that ends frees its place for the next peer. */
    private val maxPeers: Int = DEFAULT_MAX_PEERS,
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val blocking: CoroutineDispatcher? = null,
    /** Peers from somewhere other than the link's trackers — a caller that knows one. */
    private val extraPeers: List<PeerAddress> = emptyList(),
    /**
     * Asks the DHT for the info hash, or null when there is no DHT to ask
     * ([B-135](../../../../../../../../docs/backlog/B-135-the-magnet-fetch-gives-up-too-early.md)).
     */
    private val dhtPeers: (suspend () -> List<PeerAddress>)? = null,
    /**
     * Trackers somebody chose to add to every magnet, off unless given. Announced to while the
     * metadata is fetched, and kept on the torrent only when it turns out not to be private: BEP 27
     * says a private torrent talks to its own trackers and nobody else, and whether it is private
     * is in the metadata this is fetching.
     */
    private val extraTrackers: List<String> = emptyList(),
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

        /** A peer reached that does not serve metadata: no extension protocol, or no `ut_metadata`. */
        class NoMetadata(
            val address: PeerAddress,
        ) : Event

        /** A dial is over, however it went, and its place can go to the next peer. */
        class Ended(
            val address: PeerAddress,
        ) : Event

        /** Peers from a tracker or the DHT; [source] is null for the ones the caller gave. */
        class Found(
            val peers: List<PeerAddress>,
            val source: Source?,
        ) : Event

        /** A tracker or the DHT has finished answering, with or without peers. */
        class SourceDone(
            val source: Source,
            val answered: Boolean,
            /** Why it could not be asked at all, when that is what happened. */
            val failure: String? = null,
        ) : Event
    }

    private enum class Source { TRACKER, DHT }

    /**
     * Why the last dial failed, for the message a user reads when nothing worked.
     *
     * Written only by the collecting loop, which is the one coroutine that owns this object's
     * state. "No peer answered" on its own tells nobody whether the swarm is asleep or the network
     * is in the way.
     */
    private var lastDialFailure: String? = null

    // The rest of what the message says when nothing worked, owned by the same loop.
    private var dialled = 0
    private var unreachable = 0
    private var withoutMetadata = 0
    private var trackersAnswered = 0
    private var dhtFound = 0
    private var dhtFailure: String? = null

    /**
     * Fetches, or throws [MetainfoException] saying which of the two ways it failed.
     *
     * "No peer had it" and "what a peer sent was not it" are different problems for whoever reads
     * the message: the first is a swarm that has not woken up, the second is a peer that lied.
     */
    public suspend fun fetch(scope: CoroutineScope): Metainfo {
        val trackers = (link.trackers + extraTrackers).distinct()
        if (trackers.isEmpty() && dhtPeers == null && extraPeers.isEmpty()) {
            throw MetainfoException("no peers to ask for the metadata: the magnet names no tracker and the DHT is off")
        }
        val events = Channel<Event>(Channel.UNLIMITED)
        val connections = mutableListOf<PeerConnection>()
        val jobs = mutableListOf<Job>()
        try {
            // Every source at once, and the peers dialled as they come in. The first version asked
            // the trackers one by one and stopped at the first that answered, then dialled twenty
            // of its peers once: a swarm of hundreds was given up on after twenty dials that
            // happened to be behind NAT (B-135).
            if (extraPeers.isNotEmpty()) events.send(Event.Found(extraPeers, source = null))
            trackers.forEach { tracker -> jobs += scope.launch { announce(tracker, events) } }
            dhtPeers?.let { lookup -> jobs += scope.launch { askDht(lookup, events) } }
            // Once more halfway through, for a swarm whose tracker answered with the dead first:
            // a second answer is a different sample of the same swarm.
            if (trackers.isNotEmpty()) {
                jobs +=
                    scope.launch {
                        delay(timeout / 2)
                        trackers.forEach { tracker -> launch { announce(tracker, events, again = true) } }
                    }
            }
            val sources = trackers.size + (if (dhtPeers != null) 1 else 0)
            val bytes =
                withTimeoutOrNull(timeout) { collect(scope, events, jobs, connections, trackers.size, sources) }
                    ?: throw MetainfoException(
                        "no peer answered with the metadata within $timeout: ${effort(trackers.size)}",
                    )
            val metainfo = bytes.finish(link.trackers, link.displayName)
            val extra = extraTrackers.filter { it !in link.trackers }
            return if (extra.isEmpty() || metainfo.isPrivate) {
                metainfo
            } else {
                bytes.finish(link.trackers + extra, link.displayName)
            }
        } finally {
            jobs.forEach { it.cancel() }
            connections.forEach { it.close() }
            events.close()
        }
    }

    /** What was tried, for the sentence a person reads when it did not work. */
    private fun effort(trackers: Int): String =
        buildString {
            append("$trackersAnswered of $trackers tracker(s) answered")
            if (dhtPeers !=
                null
            ) {
                append(
                    dhtFailure?.let { ", the DHT could not be asked ($it)" } ?: ", the DHT found $dhtFound peer(s)",
                )
            }
            append("; $dialled peer(s) dialled, $unreachable unreachable")
            if (withoutMetadata > 0) append(", $withoutMetadata without metadata to give")
            lastDialFailure?.let { append("; the last failed dial said: $it") }
        }

    /**
     * The one loop that owns the assembly, and the queue of peers not yet dialled.
     *
     * The assembly cannot exist before the first peer says how big the metadata is, which is why
     * `metadata_size` is a handshake field and not something this client can ask for.
     */
    private suspend fun collect(
        scope: CoroutineScope,
        events: Channel<Event>,
        jobs: MutableList<Job>,
        connections: MutableList<PeerConnection>,
        trackers: Int,
        sources: Int,
    ): MetadataAssembly {
        var assembly: MetadataAssembly? = null
        val ready = mutableListOf<Peer>()
        var next = 0
        val queue = ArrayDeque<PeerAddress>()
        val seen = HashSet<PeerAddress>()
        var open = 0
        var sourcesLeft = sources

        fun dialMore() {
            while (open < maxPeers && queue.isNotEmpty()) {
                val address = queue.removeFirst()
                open++
                dialled++
                jobs += scope.launch { talk(address, events, connections) }
            }
        }

        for (event in events) {
            when (event) {
                is Event.Found -> {
                    if (event.source == Source.DHT) dhtFound += event.peers.size
                    event.peers.forEach { if (seen.add(it)) queue += it }
                    dialMore()
                }

                is Event.SourceDone -> {
                    if (event.source == Source.TRACKER && event.answered) trackersAnswered++
                    if (event.source == Source.DHT) dhtFailure = event.failure
                    sourcesLeft--
                    // Every tracker and the DHT have had their say and none knew a single peer:
                    // that is its own failure, and waiting out the clock would only delay saying so.
                    if (sourcesLeft == 0 && seen.isEmpty()) {
                        throw MetainfoException("no peers to ask for the metadata: ${effort(trackers)}")
                    }
                }

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
                    unreachable++
                    lastDialFailure = "${event.address}: ${event.reason}"
                }

                is Event.NoMetadata -> {
                    withoutMetadata++
                }

                is Event.Ended -> {
                    open--
                    ready.removeAll { it.connection.address == event.address }
                    dialMore()
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
        try {
            converse(address, events, connections)
        } finally {
            // `trySend`, not `send`: this runs on cancellation too, where suspending is not allowed.
            events.trySend(Event.Ended(address))
        }
    }

    private suspend fun converse(
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
        if (!connection.handshake.supportsExtensionProtocol) {
            events.send(Event.NoMetadata(address))
            return
        }
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
                peer = readHandshake(connection, message)
                if (peer == null) {
                    events.send(Event.NoMetadata(address))
                    return
                }
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
     * One tracker, asked for peers.
     *
     * `left` is a number this client cannot know: the torrent's length is in the metadata it is
     * trying to fetch. One block's worth is the conventional placeholder — nonzero, so the tracker
     * does not take this client for a seed and answer with leechers only.
     *
     * Every tracker in the link, each on its own coroutine: a dead tracker is not a dead swarm, and
     * a live one is not the whole of it either.
     */
    private suspend fun announce(
        tracker: String,
        events: Channel<Event>,
        again: Boolean = false,
    ) {
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
        val peers =
            try {
                trackerClient.announce(tracker, request).peers
            } catch (refused: TrackerException) {
                null
            }
        peers?.takeIf { it.isNotEmpty() }?.let { events.send(Event.Found(it, Source.TRACKER)) }
        // The second round was not counted as a source, so it is not uncounted either.
        if (!again) events.send(Event.SourceDone(Source.TRACKER, answered = peers != null))
    }

    private suspend fun askDht(
        lookup: suspend () -> List<PeerAddress>,
        events: Channel<Event>,
    ) {
        val peers =
            try {
                lookup()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                // A DHT that could not be asked is one source fewer, not a failed fetch — and the
                // reason goes into the sentence a person reads if nothing else works either.
                events.send(
                    Event.SourceDone(
                        Source.DHT,
                        answered = false,
                        failure = failed.message ?: failed::class.simpleName,
                    ),
                )
                return
            }
        if (peers.isNotEmpty()) events.send(Event.Found(peers, Source.DHT))
        events.send(Event.SourceDone(Source.DHT, answered = peers.isNotEmpty()))
    }

    public companion object {
        /** The id this client asks peers to send `ut_metadata` under, shared with the session. */
        public const val METADATA_ID: Int = ExtensionHandshake.ID_UT_METADATA

        /** Dials open at once — not dials in all, which the queue decides (B-135). */
        public const val DEFAULT_MAX_PEERS: Int = 20

        /** Long enough for a swarm to answer, short enough that a user sees the failure. */
        public val DEFAULT_TIMEOUT: Duration = 60.seconds
    }
}
