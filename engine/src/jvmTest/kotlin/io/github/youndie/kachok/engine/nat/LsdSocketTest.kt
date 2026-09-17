package io.github.youndie.kachok.engine.nat

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.io.EngineDispatchers
import io.github.youndie.kachok.engine.peer.PeerAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * B-102's acceptance, run as two sockets on one machine.
 *
 * That is not a weaker version of "two instances on one network" — it is the *harder* half of it.
 * Two copies on one machine only hear each other if multicast loopback is on, and the property
 * whose default is wrong for this case is exactly the one the JDK deprecated and inverted. Two
 * machines would not have exercised it at all.
 */
class LsdSocketTest {
    private val dispatchers = EngineDispatchers()
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val sockets = mutableListOf<LsdSocket>()
    private val infoHash = InfoHash(ByteArray(20) { (it * 7).toByte() })

    @AfterTest
    fun close() {
        sockets.forEach { it.close() }
        scope.cancel()
        dispatchers.close()
    }

    /**
     * A group and port of this test's own, not BEP 14's.
     *
     * 6771 belongs to whichever BitTorrent client started first on the machine — on the build
     * machine here it is held, through WSL's port mirroring, by the reference client running on
     * Windows. Binding it would make this a check that reports "unavailable" for ever, which is a
     * check that never runs wearing the face of one that passes.
     */
    private val testGroup = "239.192.152.199"
    private val testPort = 16_771

    private fun socket(cookie: String): LsdSocket =
        LsdSocket(
            dispatchers.io,
            cookie = cookie,
            interval = 200.milliseconds,
            groupAddress = testGroup,
            groupPort = testPort,
        ).also { sockets += it }

    /**
     * One announces, the other hears it, and neither hears itself.
     *
     * The port asserted is the one the announcement *named*, not the one the datagram came from:
     * the sender's port is whatever its socket bound, and dialling that reaches nothing.
     */
    @Test
    fun twoClientsOnOneMachineFindEachOther() {
        val heard = ConcurrentLinkedQueue<Pair<InfoHash, PeerAddress>>()
        val found = CountDownLatch(1)

        val listener = socket("listener-cookie")
        val listenerFailure =
            listener.start(scope, listenPort = 7001, held = { emptyList() }) { hash, address ->
                heard += hash to address
                found.countDown()
            }

        val announcer = socket("announcer-cookie")
        val announcerFailure = announcer.start(scope, listenPort = 7002, held = { listOf(infoHash) }) { _, _ -> }

        if (listenerFailure != null || announcerFailure != null) {
            // A machine or container with no multicast is a thing to run on, not a thing to fail
            // over — but the test must say so out loud rather than pass in silence, which is how a
            // check that never runs is mistaken for a check that passes.
            println("LSD unavailable here: listener=$listenerFailure announcer=$announcerFailure")
            return
        }

        assertTrue(found.await(10, TimeUnit.SECONDS), "neither socket heard the other in ten seconds")
        val (hash, address) = heard.first()
        assertEquals(infoHash.bytes.toList(), hash.bytes.toList())
        assertEquals(7002, address.port, "the port announced, not the port the datagram came from")

        // And the announcer heard nothing: its own announce came back and the cookie caught it.
        assertTrue(heard.none { it.second.port == 7001 }, "a socket found itself")
    }

    /** A private torrent is not announced, because BEP 27 puts local discovery on its list. */
    @Test
    fun aHeldListThatIsEmptyAnnouncesNothing() {
        val heard = ConcurrentLinkedQueue<InfoHash>()
        val listener = socket("quiet-listener")
        val failure = listener.start(scope, listenPort = 7003, held = { emptyList() }) { hash, _ -> heard += hash }
        val silent = socket("announces-nothing")
        silent.start(scope, listenPort = 7004, held = { emptyList() }) { _, _ -> }

        if (failure != null) {
            println("LSD unavailable here: $failure")
            return
        }
        Thread.sleep(1_000)
        assertTrue(heard.isEmpty(), "something was announced for a client holding nothing: $heard")
    }
}
