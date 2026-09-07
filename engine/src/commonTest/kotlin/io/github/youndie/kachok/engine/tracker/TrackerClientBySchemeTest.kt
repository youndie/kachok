package io.github.youndie.kachok.engine.tracker

import kotlinx.coroutines.test.runTest
import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.PeerId
import io.github.youndie.kachok.engine.peer.PeerAddress
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A torrent's announce list mixes schemes, and the session walks it in order. Sending a `udp://`
 * URL to the HTTP client is not a wrong answer but an `IllegalArgumentException` out of the JDK,
 * which the session's `catch (TrackerException)` does not see — so the whole announce loop ends on
 * the first UDP tracker in the file.
 */
class TrackerClientBySchemeTest {
    private class Recording(
        private val name: String,
    ) : TrackerClient {
        val asked: MutableList<String> = mutableListOf()

        override suspend fun announce(
            tracker: String,
            request: AnnounceRequest,
        ): AnnounceResponse {
            asked += tracker
            return AnnounceResponse(interval = 1, peers = listOf(PeerAddress(name, 1)))
        }
    }

    private val request =
        AnnounceRequest(
            infoHash = InfoHash(ByteArray(20)),
            peerId = PeerId(ByteArray(20)),
            port = 6881,
            uploaded = 0,
            downloaded = 0,
            left = 1,
        )

    @Test
    fun eachSchemeReachesItsOwnTransport() =
        runTest {
            val http = Recording("http")
            val udp = Recording("udp")
            val client = TrackerClientByScheme(http, udp)

            listOf(
                "http://tracker.example/announce",
                "HTTPS://tracker.example/announce",
                "udp://tracker.example:1337/announce",
                "UDP://tracker.example:1337",
            ).forEach { client.announce(it, request) }

            assertEquals(2, http.asked.size, "http and https both go to the HTTP client, whatever the case")
            assertEquals(2, udp.asked.size)
            assertTrue(udp.asked.all { it.lowercase().startsWith("udp://") })
        }

    @Test
    fun anUnknownSchemeIsOneTrackersProblemAndNotTheTorrentsEnd() =
        runTest {
            val client = TrackerClientByScheme(Recording("http"), Recording("udp"))

            // A TrackerException and not something else: the session catches this one and moves to the
            // next tracker, which is exactly what a torrent with one exotic entry needs.
            listOf("wss://tracker.example/announce", "tracker.example/announce").forEach { url ->
                val thrown =
                    assertFailsWith<TrackerException>("`$url` should be refused") {
                        client.announce(url, request)
                    }
                assertContains(thrown.message ?: "", "tracker")
            }
        }
}
