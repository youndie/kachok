package io.github.youndie.kachok.engine.dht

import io.github.youndie.kachok.engine.peer.PeerAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The half of B-35 that is arithmetic: distance, buckets, and who gets thrown out. */
class RoutingTableTest {
    private fun id(vararg leading: Int): NodeId =
        NodeId(ByteArray(NodeId.SIZE) { if (it < leading.size) leading[it].toByte() else 0 })

    private fun node(
        id: NodeId,
        port: Int = 6881,
    ) = DhtNode(id, PeerAddress("10.0.0.${port % 250 + 1}", port))

    @Test
    fun distanceIsXorAndClosenessIsItsMagnitude() {
        val target = id(0x00)
        val near = id(0x00, 0x01)
        val far = id(0x80)

        assertTrue((near distanceTo target) < (far distanceTo target))
        assertEquals(id(), target distanceTo target, "a node is at distance zero from itself")
        assertEquals(near distanceTo far, far distanceTo near, "xor is symmetric")
    }

    @Test
    fun theCommonPrefixIsWhichBucketANodeBelongsIn() {
        assertEquals(0, id(0x00) commonPrefixWith id(0x80), "they differ in the first bit")
        assertEquals(7, id(0x00) commonPrefixWith id(0x01), "and here in the eighth")
        assertEquals(8, id(0x00) commonPrefixWith id(0x00, 0x80))
        assertEquals(NodeId.SIZE * 8, id(0x12, 0x34) commonPrefixWith id(0x12, 0x34), "an id shares all of itself")
    }

    @Test
    fun aBucketHoldsEightAndAFullOneOfGoodNodesRefusesTheNinth() {
        val table = RoutingTable(id(0x00))
        // All in bucket 0: every one of them differs from us in the first bit.
        val admitted = (0 until 9).map { table.seen(node(id(0x80, it))) }

        assertEquals(List(8) { true } + false, admitted, "the ninth good node has nowhere to go")
        assertEquals(8, table.size)
    }

    @Test
    fun anUnresponsiveNodeIsEvictedAndAGoodOneNeverIs() {
        // The acceptance criterion's second half. A known-good node is worth more than an unknown
        // one, so eviction is by failure and never by making room.
        val table = RoutingTable(id(0x00))
        (0 until 8).forEach { table.seen(node(id(0x80, it))) }
        val doomed = id(0x80, 0)

        table.failed(doomed)
        assertTrue(table.isGood(doomed), "one lost datagram is normal on UDP")
        assertTrue(!table.seen(node(id(0x80, 100))), "and the bucket is still full of good nodes")

        table.failed(doomed)
        assertTrue(!table.isGood(doomed))
        assertTrue(table.seen(node(id(0x80, 100))), "now there is room, made by the bad node")
        assertEquals(8, table.size)
        assertTrue(table.all().none { it.id == doomed }, "the bad node was the one replaced")
    }

    @Test
    fun answeringAgainForgivesAPastFailure() {
        val table = RoutingTable(id(0x00))
        val flaky = node(id(0x80, 1))
        table.seen(flaky)
        table.failed(flaky.id)

        table.seen(flaky)

        assertTrue(table.isGood(flaky.id))
        assertEquals(1, table.size, "and it is still one node, not two")
    }

    @Test
    fun closestOrdersByDistanceAndLeavesOutTheBadOnes() {
        val table = RoutingTable(id(0x00))
        listOf(id(0x01), id(0x40), id(0x80)).forEach { table.seen(node(it)) }
        table.failed(id(0x01))
        table.failed(id(0x01))

        val closest = table.closest(id(0x00), count = 3)

        assertEquals(listOf(id(0x40), id(0x80)), closest.map { it.id }, "nearest first, and no bad node")
    }

    @Test
    fun thisNodeIsNotInItsOwnTable() {
        val table = RoutingTable(id(0x11))

        assertTrue(!table.seen(node(id(0x11))))
        assertEquals(0, table.size)
    }
}
