package io.github.youndie.kachok.engine.dht

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.io.DatagramKrpcTransport
import io.github.youndie.kachok.engine.io.EngineDispatchers
import io.github.youndie.kachok.engine.peer.PeerAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * Join the real DHT the way a torrent does and say how big the routing table gets, and what a
 * lookup for one info hash finds.
 *
 * ```
 * ./gradlew :engine:dhtProbe -PinfoHash=<40 hex>
 * ```
 */
public object DhtProbe {
    @JvmStatic
    public fun main(arguments: Array<String>) {
        val hex = System.getProperty("infoHash")?.takeIf { it.isNotBlank() } ?: arguments.first()
        val hash = InfoHash(ByteArray(20) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
        val dispatchers = EngineDispatchers()
        val scope = CoroutineScope(dispatchers.io + SupervisorJob())
        val transport = DatagramKrpcTransport(dispatchers.io)
        val dht = Dht(NodeId.random(), transport)
        transport.onQuery = dht::answer
        transport.start(scope)
        runBlocking(dispatchers.io) {
            fun timed(
                label: String,
                block: suspend () -> String,
            ) {
                val started = System.nanoTime()
                val said = kotlinx.coroutines.runBlocking { block() }
                println("$label: $said, table ${dht.table.size}, ${(System.nanoTime() - started) / 1_000_000} ms")
            }
            timed("bootstrap") { "${dht.bootstrap(scope, BOOTSTRAP)} nodes" }
            timed("walk towards own id") { "${dht.refresh(scope)} nodes" }
            repeat(3) { round ->
                timed("lookup ${round + 1}") {
                    val found = dht.lookup(scope, hash)
                    "${found.peers.size} peers, ${found.tokens.size} tokens"
                }
            }
            timed("lookup after") {
                val found = Dht(dht.self, transport, dht.table, rounds = 20).lookup(scope, hash)
                "${found.peers.size} peers with 20 rounds"
            }
            println("queries from other nodes, answered: ${transport.queriesSeen}")
        }
        transport.close()
        scope.cancel()
        dispatchers.close()
    }

    private val BOOTSTRAP =
        listOf(
            PeerAddress("router.bittorrent.com", 6881),
            PeerAddress("dht.transmissionbt.com", 6881),
            PeerAddress("router.utorrent.com", 6881),
            PeerAddress("dht.libtorrent.org", 25401),
            PeerAddress("dht.aelitis.com", 6881),
        )
}
