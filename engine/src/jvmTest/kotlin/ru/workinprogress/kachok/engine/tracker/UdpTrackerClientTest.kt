package ru.workinprogress.kachok.engine.tracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import ru.workinprogress.kachok.engine.InfoHash
import ru.workinprogress.kachok.engine.PeerId
import ru.workinprogress.kachok.engine.peer.PeerAddress
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The acceptance criteria of B-32 that need a socket: the exchange against a tracker that drops,
 * refuses and answers somebody else's transaction.
 *
 * The timeouts here are milliseconds rather than BEP 15's fifteen seconds, and the schedule's real
 * numbers are asserted in `UdpTrackerProtocolTest`. What this file proves is that the transport
 * *uses* the schedule — that a dropped datagram is resent, and that the window doubles between
 * attempts — which a pure function cannot show.
 */
class UdpTrackerClientTest {
    private val infoHash = InfoHash(ByteArray(20) { (it * 3 + 1).toByte() })
    private val peerId = PeerId("-KA0001-zyxwvutsrqpo".encodeToByteArray())

    private val request =
        AnnounceRequest(
            infoHash = infoHash,
            peerId = peerId,
            port = 6883,
            uploaded = 4_294_967_296L,
            downloaded = 123_456,
            left = 7_890,
            event = AnnounceEvent.STARTED,
        )

    private fun client(
        firstTimeoutMillis: Long = 100,
        maxAttempts: Int = 4,
    ) = UdpTrackerClient(
        dispatcher = Dispatchers.Unconfined,
        firstTimeoutMillis = firstTimeoutMillis,
        maxAttempts = maxAttempts,
    )

    @Test
    fun anAnnounceConnectsThenAnnouncesAndComesBackWithPeers(): Unit =
        runBlocking {
            FakeUdpTracker().use { tracker ->
                val client = client()

                val response = client.announce("udp://127.0.0.1:${tracker.port}/announce", request)

                assertEquals(1800, response.interval)
                assertEquals(
                    listOf(PeerAddress("10.1.2.3", 6881), PeerAddress("10.1.2.4", 51413)).map { it.host to it.port },
                    response.peers.map { it.host to it.port },
                )
                assertEquals(2, response.leechers)
                assertEquals(4, response.seeders)
                assertEquals(2, client.datagramsSent, "a connect and an announce, and nothing resent")

                val seen = tracker.announces.single()
                assertTrue(infoHash.bytes.contentEquals(seen.infoHash), "the info hash the client announced")
                assertTrue(peerId.bytes.contentEquals(seen.peerId))
                assertEquals(6883, seen.port)
                assertEquals(2, seen.event, "BEP 15 numbers `started` 2")
                assertEquals(-1, seen.numWant)
                assertEquals(123_456, seen.downloaded)
                assertEquals(7_890, seen.left)
                assertEquals(
                    4_294_967_296L,
                    seen.uploaded,
                    "a 64-bit counter must survive the wire; four gigabytes is where a 32-bit one wraps",
                )
            }
        }

    @Test
    fun aLostAnnounceIsResentAndTheWindowDoubles(): Unit =
        runBlocking {
            // Two announces swallowed: the client waits 100 ms, resends, waits 200 ms, resends, and
            // is answered. A lower bound on the elapsed time is the evidence that the second window
            // was the doubled one and not the first again.
            FakeUdpTracker(dropAnnounces = 2).use { tracker ->
                val client = client(firstTimeoutMillis = 100)
                val started = TimeSource.Monotonic.markNow()

                val response = client.announce("udp://127.0.0.1:${tracker.port}/announce", request)

                val elapsed = started.elapsedNow()
                assertEquals(2, response.peers.size)
                assertEquals(4, client.datagramsSent, "one connect and three announces")
                assertTrue(
                    elapsed >= 300.milliseconds,
                    "the two windows before the answer were $elapsed, not the 100 + 200 ms the schedule asks for",
                )
            }
        }

    @Test
    fun aTrackerThatNeverAnswersGivesUpAfterItsAttempts(): Unit =
        runBlocking {
            FakeUdpTracker(silent = true).use { tracker ->
                val client = client(firstTimeoutMillis = 30, maxAttempts = 3)

                val thrown =
                    assertFailsWith<TrackerException> {
                        client.announce("udp://127.0.0.1:${tracker.port}/announce", request)
                    }

                assertContains(thrown.message ?: "", "did not answer")
                assertEquals(3, client.datagramsSent, "three connects, and no announce was ever sent")
                assertEquals(3, tracker.datagramsReceived.get())
            }
        }

    @Test
    fun aReplyBelongingToAnotherTransactionIsIgnoredAndTheWaitGoesOn(): Unit =
        runBlocking {
            // The junk reply is well formed and names a peer of its own; a client that took it
            // would come back with 1.1.1.1 and would look like it worked.
            FakeUdpTracker(junkBeforeReply = true).use { tracker ->
                val response = client().announce("udp://127.0.0.1:${tracker.port}/announce", request)

                assertEquals(
                    listOf("10.1.2.3", "10.1.2.4"),
                    response.peers.map { it.host },
                    "the answer came from the junk datagram, not from the tracker's real reply",
                )
            }
        }

    @Test
    fun aStaleConnectionIdIsRefusedInTheTrackersOwnWords(): Unit =
        runBlocking {
            // BEP 15 gives a connection id one minute. This tracker retires it immediately, which
            // is what a client that cached one across announces would eventually meet.
            FakeUdpTracker(rotateConnectionId = true).use { tracker ->
                val thrown =
                    assertFailsWith<TrackerException> {
                        client().announce("udp://127.0.0.1:${tracker.port}/announce", request)
                    }

                assertContains(thrown.message ?: "", "connection id mismatch")
            }
        }

    @Test
    fun aTrackerThatRefusesSaysWhyAndDoesNotLookLikeALostPacket(): Unit =
        runBlocking {
            FakeUdpTracker(refuseAnnounceWith = "torrent not registered").use { tracker ->
                val client = client(firstTimeoutMillis = 100, maxAttempts = 4)

                val thrown =
                    assertFailsWith<TrackerException> {
                        client.announce("udp://127.0.0.1:${tracker.port}/announce", request)
                    }

                assertContains(thrown.message ?: "", "torrent not registered")
                assertEquals(2, client.datagramsSent, "a refusal is an answer; nothing should be resent")
            }
        }

    @Test
    fun aConnectionIdIsEchoedBackExactlyIncludingItsSignBit(): Unit =
        runBlocking {
            FakeUdpTracker().use { tracker ->
                client().announce("udp://127.0.0.1:${tracker.port}/announce", request)

                assertTrue(
                    tracker.announces.single().connectionId < 0,
                    "the fake issues a connection id with the sign bit set, and the tracker checked it came back",
                )
            }
        }

    @Test
    fun aTrackerUrlThatIsNotUdpNeverOpensASocket(): Unit =
        runBlocking {
            val thrown =
                assertFailsWith<TrackerException> {
                    client().announce("udp://127.0.0.1/announce", request)
                }
            assertContains(thrown.message ?: "", "no port")
        }
}
