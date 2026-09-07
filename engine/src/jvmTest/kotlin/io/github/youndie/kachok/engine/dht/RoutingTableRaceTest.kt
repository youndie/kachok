package io.github.youndie.kachok.engine.dht

import io.github.youndie.kachok.engine.peer.PeerAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The table is shared by every torrent, and nothing serialises the sharing.
 *
 * There is one `Dht` for a whole `TorrentSet` — one routing table, one socket — and every torrent
 * runs its own lookup loop on its own `limitedParallelism(1)` dispatcher. Confinement is *per
 * session*, so two torrents with the DHT on walk this table at the same time; a bucket that was a
 * `mutableListOf` read by one while the other appended to it threw a
 * `ConcurrentModificationException` out of `dht lookup` on somebody's machine
 * ([B-94](../../../../../../../../docs/backlog/B-94-a-degraded-session-cannot-recover.md)).
 *
 * **A JVM test, because the fault needs real threads.** Coroutines on one thread interleave only at
 * suspension points and these methods do not suspend; nothing but genuine parallelism reproduces
 * it, which is also why the engine's single-threaded test dispatcher never would.
 */
class RoutingTableRaceTest {
    // Its own seed, well away from the nodes': `seen` refuses a node whose id is the table's own,
    // and sharing a seed with one of them is how the first version of this test lost a node and
    // blamed the code.
    private val self = NodeId.random(Random(999_983))

    private fun node(seed: Int) =
        DhtNode(NodeId.random(Random(seed.toLong())), PeerAddress("10.0.0.${seed % 255}", 6881))

    @Test
    fun readingAndWritingAtTheSameTimeDoesNotThrow() {
        val table = RoutingTable(self)
        val failure = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)
        val writers = 3
        val readers = 3

        val threads =
            (0 until writers).map { w ->
                thread {
                    start.await()
                    try {
                        repeat(ROUNDS) { at -> table.seen(node(w * ROUNDS + at)) }
                        repeat(ROUNDS) { at -> table.failed(node(w * ROUNDS + at).id) }
                    } catch (broke: Throwable) {
                        failure.compareAndSet(null, broke)
                    }
                }
            } +
                (0 until readers).map {
                    thread {
                        start.await()
                        try {
                            repeat(ROUNDS) {
                                // The three a lookup does, on every round, for every answer.
                                table.closest(NodeId.random())
                                table.all()
                                table.size
                            }
                        } catch (broke: Throwable) {
                            failure.compareAndSet(null, broke)
                        }
                    }
                }

        start.countDown()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }

        assertEquals(null, failure.get(), "the table threw under two lookups at once: ${failure.get()}")
        assertTrue(table.size > 0, "nothing was learned, so nothing was raced over")
    }

    /**
     * And what the race can cost is a lost update, never a broken table.
     *
     * Replacing a whole bucket instead of editing it means two writers to one bucket both build
     * from what they read and the second wins — so an update can vanish, and that is the price this
     * design pays instead of a lock. What must *not* happen is the table ending up holding a node
     * twice or a bucket longer than its size, which is what an interleaved edit-in-place would
     * leave behind. A lost `seen` is a node met again within the minute; a corrupt bucket is
     * for ever.
     */
    @Test
    fun aRaceCanLoseAnUpdateAndCannotCorruptABucket() {
        val table = RoutingTable(self)
        val start = CountDownLatch(1)
        val threads =
            (0 until 4).map { w ->
                thread {
                    start.await()
                    // Deliberately overlapping ranges, so the writers fight over the same buckets
                    // rather than politely taking one each.
                    repeat(ROUNDS) { at -> table.seen(node(at + w)) }
                }
            }
        start.countDown()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }

        val all = table.all()
        assertEquals(all.size, all.map { it.id }.toSet().size, "the table holds a node twice")
        assertTrue(all.isNotEmpty(), "nothing was learned, so nothing was raced over")
    }

    private companion object {
        const val ROUNDS = 400
    }
}
