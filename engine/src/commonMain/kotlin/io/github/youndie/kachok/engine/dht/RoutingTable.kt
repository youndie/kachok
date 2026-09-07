package io.github.youndie.kachok.engine.dht

/**
 * Kademlia's routing table (BEP 5): the nodes this one knows, kept by distance from itself.
 *
 * **Buckets are indexed by the length of the common prefix with this node's own id**, which is the
 * standard way of saying "split only the bucket that contains us". A node sharing no leading bits
 * with us goes in bucket 0 — half the key space — and one sharing 159 goes in bucket 159, its
 * immediate neighbourhood. The table therefore knows the whole network roughly and its own
 * neighbourhood exactly, which is what makes a lookup converge in O(log n) hops.
 *
 * **Eviction is by failure, not by age.** BEP 5 lets a node be questionable after fifteen idle
 * minutes and bad after it fails to answer "multiple" queries; a table that dropped nodes on a
 * timer would throw away a perfectly good node that nobody happened to need. Here a node is bad
 * after [FAILURES_TO_EVICT] unanswered queries, and a full bucket admits a new node only by
 * replacing a bad one — never by dropping a good one, because a known-good node is worth more than
 * an unknown one.
 *
 * Not thread safe: it lives inside the DHT's own confined coroutine, like the peer table.
 */
public class RoutingTable(
    public val self: NodeId,
    private val bucketSize: Int = BUCKET_SIZE,
) {
    private class Entry(
        val node: DhtNode,
        val failures: Int = 0,
    ) {
        val isBad: Boolean get() = failures >= FAILURES_TO_EVICT
    }

    /**
     * **A bucket is replaced, never edited**, and that is what makes this table safe to share.
     *
     * There is one `Dht` for a whole `TorrentSet` — one routing table, one socket — while every
     * torrent runs its own lookup loop on its own confined dispatcher. Confinement is per session,
     * so two torrents with the DHT on run two lookups at once over *this*, and a
     * `mutableListOf` read by one while the other adds to it is a
     * `ConcurrentModificationException` — which is what happened, in `dht lookup`, on somebody's
     * machine.
     *
     * A lock would fix it and would be the third concurrency mechanism in an engine that has two.
     * Replacing the whole list instead means a reader always walks a list nobody can touch: the
     * worst a race can do is lose one update, because two writers to one bucket both build from
     * what they read and the second wins. A lost `seen` is a node this table forgets it met and
     * meets again within the minute — the cheapest possible thing to lose.
     *
     * `Entry.failures` is a `val` for the same reason: it used to be mutated in place through a
     * reference a reader might be holding.
     */
    private val buckets = Array<List<Entry>>(NodeId.SIZE * 8) { emptyList() }

    public val size: Int get() = buckets.sumOf { it.size }

    /**
     * A node answered, or was named by one that did.
     *
     * Returns whether the table kept it. "No" is the ordinary answer for a full bucket of good
     * nodes and is not a failure of anything.
     */
    public fun seen(node: DhtNode): Boolean {
        if (node.id == self) return false
        val at = bucketOf(node.id)
        val bucket = buckets[at]
        val existing = bucket.firstOrNull { it.node.id == node.id }
        if (existing != null) {
            // Most recently seen last: a bucket is also a queue, and the front of it is what gets
            // asked first when it needs pruning. Its failure count goes back to zero — it answered.
            buckets[at] = bucket.filter { it !== existing } + Entry(node)
            return true
        }
        if (bucket.size < bucketSize) {
            buckets[at] = bucket + Entry(node)
            return true
        }
        val bad = bucket.firstOrNull { it.isBad } ?: return false
        buckets[at] = bucket.filter { it !== bad } + Entry(node)
        return true
    }

    /** A node did not answer. Enough of these and it is replaceable. */
    public fun failed(id: NodeId) {
        val at = bucketOf(id)
        val bucket = buckets[at]
        val existing = bucket.firstOrNull { it.node.id == id } ?: return
        buckets[at] = bucket.map { if (it === existing) Entry(it.node, it.failures + 1) else it }
    }

    /** Whether this node is still worth asking. */
    public fun isGood(id: NodeId): Boolean = buckets[bucketOf(id)].firstOrNull { it.node.id == id }?.isBad == false

    /**
     * The [count] nodes nearest [target], nearest first.
     *
     * Scanning every bucket rather than walking outwards from the target's own: with 160 buckets
     * holding eight nodes each the whole table is smaller than one lookup's worth of network
     * traffic, and the clever version is a source of off-by-one errors in exchange for nothing.
     */
    public fun closest(
        target: NodeId,
        count: Int = bucketSize,
    ): List<DhtNode> =
        buckets
            .asSequence()
            .flatten()
            .filter { !it.isBad }
            .map { it.node }
            .sortedWith(closestTo(target))
            .take(count)
            .toList()

    /** Every node the table holds, bad ones included: what a test asks and a lookup does not. */
    public fun all(): List<DhtNode> = buckets.toList().flatten().map { entry -> entry.node }

    private fun bucketOf(id: NodeId): Int = (self commonPrefixWith id).coerceAtMost(buckets.size - 1)

    public companion object {
        /** BEP 5's `k`. */
        public const val BUCKET_SIZE: Int = 8

        /**
         * BEP 5 says "multiple" and leaves the number open. Two, because one lost datagram is
         * normal on UDP and throwing a node away for it would empty the table on a bad minute.
         */
        public const val FAILURES_TO_EVICT: Int = 2
    }
}
