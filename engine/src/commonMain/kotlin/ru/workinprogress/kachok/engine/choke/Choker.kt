package ru.workinprogress.kachok.engine.choke

import ru.workinprogress.kachok.engine.peer.PeerAddress
import kotlin.random.Random

/** What the choker knows about one connected peer. */
public class PeerRates(
    public val peer: PeerAddress,
    /** Whether the peer said it wants something from us. */
    public val interested: Boolean,
    /** Bytes a second this peer is giving us. */
    public val downloadRate: Long,
    /** Bytes a second we are giving this peer. */
    public val uploadRate: Long,
)

/** Who to serve until the next pass. */
public class ChokeDecision(
    public val unchoked: Set<PeerAddress>,
    /** The peer unchoked regardless of what it gives back, or null when there is nobody to pick. */
    public val optimistic: PeerAddress?,
)

/**
 * BEP 3's choking algorithm, as a pure function of the peer table.
 *
 * > "The currently deployed choking algorithm avoids fibrillation by only changing who's choked
 * > once every ten seconds. It does reciprocation and number of uploads capping by unchoking the
 * > four peers which it has the best download rates from and are interested. […] If a downloader
 * > has a complete file, it uses its upload rate rather than its download rate to decide who to
 * > unchoke."
 *
 * The reference algorithm rather than a cleverer one, on purpose: it is what the swarm expects,
 * and it is what makes this client's numbers comparable to any other's.
 *
 * **Nothing here knows the time.** The caller decides when a pass happens and when the optimistic
 * choice is due, because those are the session's one timer's business — which is also what makes
 * every rule below testable without waiting for anything.
 */
public class Choker(
    /** BEP 3's four. The optimistic peer takes one of these when it is interested. */
    private val regularSlots: Int = DEFAULT_SLOTS,
    private val random: Random = Random.Default,
) {
    private var optimistic: PeerAddress? = null

    /**
     * Decides who is unchoked until the next pass.
     *
     * [rotateOptimistic] is the caller saying that thirty seconds have gone by; the optimistic
     * peer is otherwise kept, which is the point of it — a peer given a chance needs long enough
     * to show what it can do. BEP 3: "To give them a decent chance of getting a complete piece to
     * upload, new connections are three times as likely to start as the current optimistic unchoke
     * as anywhere else in the rotation" — that weighting is not implemented, and its absence is
     * the one place this differs from the reference.
     */
    public fun pass(
        peers: List<PeerRates>,
        seeding: Boolean,
        rotateOptimistic: Boolean,
    ): ChokeDecision {
        if (peers.isEmpty()) {
            optimistic = null
            return ChokeDecision(emptySet(), null)
        }
        val known = peers.associateBy { it.peer }
        if (rotateOptimistic || optimistic == null || optimistic !in known) {
            optimistic = peers[random.nextInt(peers.size)].peer
        }
        val chosen = optimistic

        // A seed has nothing to reciprocate for, so it ranks by what it can push (BEP 3).
        val ranked =
            peers
                .filter { it.interested && it.peer != chosen }
                .sortedByDescending { if (seeding) it.uploadRate else it.downloadRate }

        val optimisticIsInterested = chosen != null && known.getValue(chosen).interested
        val room = if (optimisticIsInterested) regularSlots - 1 else regularSlots

        val unchoked = LinkedHashSet<PeerAddress>()
        chosen?.let { unchoked += it }
        ranked.take(room.coerceAtLeast(0)).forEach { unchoked += it.peer }
        return ChokeDecision(unchoked, chosen)
    }

    private companion object {
        const val DEFAULT_SLOTS = 4
    }
}
