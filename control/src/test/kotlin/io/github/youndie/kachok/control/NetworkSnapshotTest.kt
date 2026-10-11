package io.github.youndie.kachok.control

import io.github.youndie.kachok.engine.dht.DhtNode
import io.github.youndie.kachok.engine.dht.NodeId
import io.github.youndie.kachok.engine.io.EngineDispatchers
import io.github.youndie.kachok.engine.peer.PeerAddress
import io.github.youndie.kachok.engine.runtime.SetOptions
import io.github.youndie.kachok.engine.runtime.TorrentSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The snapshot says what the network is, from the set's own state
 * ([B-140](../../../../../../../../docs/backlog/B-140-the-snapshot-reports-the-network.md)).
 *
 * `dhtNodes` used to be the first torrent's last report of the routing table, which is never
 * updated while that torrent is paused, and is 0 when there is no torrent at all. A diagnosis read
 * `dhtNodes: 0` off a client whose DHT was working.
 */
class NetworkSnapshotTest {
    private val dispatchers = EngineDispatchers()
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    @AfterTest
    fun close() {
        scope.cancel()
        dispatchers.close()
    }

    @Test
    fun dhtNodesIsTheRoutingTablesSizeWithNoTorrentToReportIt() {
        TorrentSet(dispatchers, scope, SetOptions(port = 0, dht = true)).use { set ->
            val table = assertNotNull(set.dhtForLookups).table
            (1..3).forEach { table.seen(DhtNode(NodeId.random(), PeerAddress("10.0.0.$it", 6881))) }

            val snapshot = set.snapshot(1)

            assertEquals(table.size, snapshot.dhtNodes, "the snapshot did not read the routing table")
            assertTrue(snapshot.dhtNodes!! > 0)
        }
    }

    @Test
    fun aClientWithoutTheDhtSaysSoAndReportsItsMapping() {
        TorrentSet(dispatchers, scope, SetOptions(port = 0, dht = false)).use { set ->
            val snapshot = set.snapshot(1)

            assertNull(snapshot.dhtNodes, "a DHT that was never asked for counted nodes")
            assertEquals(set.portMapping, snapshot.portMapping)
            assertEquals(set.mappedExternalPort, snapshot.mappedPort)
        }
    }
}
