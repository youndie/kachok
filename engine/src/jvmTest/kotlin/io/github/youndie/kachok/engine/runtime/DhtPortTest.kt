package io.github.youndie.kachok.engine.runtime

import io.github.youndie.kachok.engine.io.EngineDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.net.DatagramSocket
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B-143: the DHT listens on the peer listener's port, for UDP, and says whether that is forwarded.
 * It used to take an ephemeral port nothing forwarded and that changed at every start.
 */
class DhtPortTest {
    private val dispatchers = EngineDispatchers()
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val sets = mutableListOf<TorrentSet>()
    private val failures = mutableListOf<String>()

    @AfterTest
    fun clean() {
        sets.forEach { it.close() }
        scope.cancel()
        dispatchers.close()
    }

    private fun open(
        port: Int,
        dht: Boolean = true,
    ): TorrentSet =
        TorrentSet(
            dispatchers = dispatchers,
            scope = scope,
            options = SetOptions(port = port, dht = dht),
            onBindFailure = { synchronized(failures) { failures += it } },
        ).also { sets += it }

    /**
     * A port free for TCP **and** UDP, since the set binds both to it. A port the OS hands out for
     * a `ServerSocket` says nothing about the same number in UDP: on the Windows runner it was
     * taken there, and both tests failed — the DHT fell back as designed, and the
     * `DatagramSocket` the second test opens could not bind (B-188).
     */
    private fun freePort(): Int {
        repeat(20) {
            val port = ServerSocket(0).use { it.localPort }
            val udpFree = runCatching { DatagramSocket(port).close() }.isSuccess
            if (udpFree) return port
        }
        error("no port among 20 was free for both TCP and UDP")
    }

    @Test
    fun theDhtListensOnTheListenersPort() {
        val set = open(freePort())

        assertEquals(set.listenPort, set.dhtPort, "one port to forward, for TCP and UDP alike")
        assertNotNull(set.dhtPortMapping, "with the DHT on, the set says whether its port is forwarded")
    }

    @Test
    fun aTakenUdpPortFallsBackAndSaysSo() {
        val port = freePort()
        DatagramSocket(port).use {
            val set = open(port)

            assertNotEquals(port, set.dhtPort, "the DHT shared a socket somebody else holds")
            assertTrue(
                synchronized(failures) { failures.any { "UDP port $port" in it } },
                "the fallback went unreported: $failures",
            )
        }
    }

    @Test
    fun withTheDhtOffThereIsNothingToForward() {
        val set = open(freePort(), dht = false)
        assertNull(set.dhtPort)
        assertNull(set.dhtPortMapping)
    }
}
