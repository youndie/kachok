package io.github.youndie.kachok.engine.nat

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * B-103: the mapper against a router that answers, one that refuses, and one that is not there.
 *
 * **The third is the case this network actually has** — neither NAT-PMP nor UPnP drew an answer
 * from the gateway here, and the reference client's log says the same — so it is the one with the
 * timing assertion on it. A client that stalls at start-up waiting for a router that will never
 * answer is worse than one that never asked.
 */
class PortMapperTest {
    private val routers = mutableListOf<FakeRouter>()

    @AfterTest
    fun close() {
        routers.forEach { it.close() }
    }

    /** A router on the loopback that answers NAT-PMP however the test tells it to. */
    private inner class FakeRouter(
        private val answer: (ByteArray) -> ByteArray?,
    ) {
        private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val port: Int get() = socket.localPort
        val received = mutableListOf<ByteArray>()

        init {
            routers += this
            Thread
                .ofVirtual()
                .start {
                    while (!socket.isClosed) {
                        try {
                            val buffer = ByteArray(64)
                            val packet = DatagramPacket(buffer, buffer.size)
                            socket.receive(packet)
                            val request = buffer.copyOf(packet.length)
                            synchronized(received) { received += request }
                            answer(request)?.let {
                                socket.send(DatagramPacket(it, it.size, packet.socketAddress))
                            }
                        } catch (closed: Exception) {
                            return@start
                        }
                    }
                }
        }

        fun close() = socket.close()
    }

    /** A mapper pointed at a fake router, by talking to the loopback on the router's own port. */
    private fun mapperFor(
        router: FakeRouter,
        attempts: Int = 2,
    ): PortMapper =
        PortMapper(
            gateway = InetAddress.getLoopbackAddress(),
            timeout = 300.milliseconds,
            attempts = attempts,
            routerPort = router.port,
        )

    @Test
    fun aRouterThatForwardsTheportIsReportedAsMapped() {
        val router = FakeRouter { request -> reply(request, result = 0, external = 49_152, lifetime = 7200) }
        val mapped = mapperFor(router).map(6881) as PortMapping.Mapped

        assertEquals(49_152, mapped.externalPort, "the router's port, not the one asked for")
        assertEquals(7200, mapped.lifetimeSeconds)
        assertEquals(6881, requestedInternalPort(router), "the port asked for is the one that was bound")
    }

    /** A refusal reaches the caller in the router's own terms rather than as a silence. */
    @Test
    fun aRouterThatRefusesSaysWhy() {
        val router = FakeRouter { request -> reply(request, result = 2, external = 0, lifetime = 0) }
        val refused = mapperFor(router).map(6881) as PortMapping.NotMapped
        assertTrue(refused.because.contains("switched off"), refused.because)
    }

    /**
     * The case this network has, and the assertion is on the clock.
     *
     * Two attempts of three hundred milliseconds is under a second; the real settings are two of
     * two seconds. What must not happen is a start-up that waits on a router which is never going
     * to answer.
     */
    @Test
    fun aRouterThatNeverAnswersGivesUpQuicklyAndSaysSo() {
        val silent = FakeRouter { null }
        val started = System.nanoTime()
        val result = mapperFor(silent).map(6881) as PortMapping.NotMapped
        val elapsed = (System.nanoTime() - started) / 1_000_000

        assertTrue(result.because.contains("does not answer"), result.because)
        assertTrue(elapsed < 2_000, "gave up after ${elapsed}ms, which is a start-up somebody waits through")
        assertEquals(2, silent.received.size, "it must retry once before giving up, and not more")
    }

    /** Somebody else's traffic on the socket is not an answer and not a failure. */
    @Test
    fun anUnrelatedDatagramIsIgnoredRatherThanTakenAsAnAnswer() {
        val seen =
            java.util.concurrent.atomic
                .AtomicInteger()
        val noisy =
            FakeRouter { request ->
                // First a NAT-PMP *request* from another host on the segment — a datagram that
                // reaches our socket and is not a reply to anything — and only then the real answer.
                if (seen.incrementAndGet() == 1) ByteArray(NatPmp.REQUEST_SIZE) else reply(request, 0, 49_152, 7200)
            }
        val result = mapperFor(noisy, attempts = 3).map(6881)
        assertTrue(result is PortMapping.Mapped, "the noise was taken as the answer: $result")
    }

    /** Releasing names the port being dropped and asks for no time at all. */
    @Test
    fun releasingSendsTheReleasePacket() {
        val router = FakeRouter { request -> reply(request, 0, 0, 0) }
        mapperFor(router).release(6881)

        val sent = synchronized(router.received) { router.received.last() }
        assertEquals(6881, (sent[4].toInt() and 0xFF shl 8) or (sent[5].toInt() and 0xFF))
        assertEquals(
            0,
            (sent[6].toInt() and 0xFF shl 8) or (sent[7].toInt() and 0xFF),
            "a release names no external port",
        )
        assertEquals(0, sent.copyOfRange(8, 12).fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) })
    }

    /** Renewal is at half the lease, which leaves room for one failure before the hole closes. */
    @Test
    fun renewalIsHalfTheLease() {
        val router = FakeRouter { request -> reply(request, 0, 49_152, 7200) }
        val mapper = mapperFor(router)
        val mapped = mapper.map(6881) as PortMapping.Mapped
        assertEquals(3600, mapper.renewAfter(mapped).inWholeSeconds)
    }

    /** There is always a gateway guess, even when the platform's route command says nothing. */
    @Test
    fun aGatewayIsFoundOrGuessedRatherThanLeftNull() {
        val gateway = PortMapper.defaultGateway()
        assertTrue(gateway != null, "no gateway and no fallback: the mapper would never try at all")
    }

    private fun requestedInternalPort(router: FakeRouter): Int {
        val request = synchronized(router.received) { router.received.first() }
        return ((request[4].toInt() and 0xFF) shl 8) or (request[5].toInt() and 0xFF)
    }

    private fun reply(
        request: ByteArray,
        result: Int,
        external: Int,
        lifetime: Int,
    ): ByteArray {
        val packet = ByteArray(NatPmp.RESPONSE_SIZE)
        packet[0] = 0
        packet[1] = ((request[1].toInt() and 0xFF) + NatPmp.RESPONSE_FLAG).toByte()
        packet[2] = (result ushr 8).toByte()
        packet[3] = result.toByte()
        packet[8] = request[4]
        packet[9] = request[5]
        packet[10] = (external ushr 8).toByte()
        packet[11] = external.toByte()
        packet[12] = (lifetime ushr 24).toByte()
        packet[13] = (lifetime ushr 16).toByte()
        packet[14] = (lifetime ushr 8).toByte()
        packet[15] = lifetime.toByte()
        return packet
    }
}
