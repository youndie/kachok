package io.github.youndie.kachok.engine.tracker

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.PeerId
import io.github.youndie.kachok.engine.io.EngineDispatchers
import io.github.youndie.kachok.engine.peer.PeerAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The acceptance criteria of B-15's transport half, against a real HTTP server on localhost.
 *
 * A local server rather than a mocked client: what is being checked is the query string a tracker
 * actually receives, and a mock would only confirm that this code calls the code beside it.
 */
class HttpTrackerClientTest {
    private val dispatchers = EngineDispatchers()
    private val infoHash = InfoHash(ByteArray(20) { (it * 11).toByte() })
    private val peerId = PeerId("-KA0001-0123456789AB".encodeToByteArray())
    private val requests = ConcurrentLinkedQueue<String>()

    private var server: HttpServer? = null

    @AfterTest
    fun shutDown() {
        server?.stop(0)
        dispatchers.close()
    }

    private fun serve(
        status: Int = 200,
        body: ByteArray,
    ): String {
        val started = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        started.createContext("/annc") { exchange: HttpExchange ->
            requests += exchange.requestURI.rawQuery ?: ""
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        started.start()
        server = started
        return "http://127.0.0.1:${started.address.port}/annc"
    }

    private fun request(event: AnnounceEvent? = null) =
        AnnounceRequest(
            infoHash = infoHash,
            peerId = peerId,
            port = 6882,
            uploaded = 0,
            downloaded = 1024,
            left = 2048,
            event = event,
        )

    @Test
    fun theTrackerSeesThePercentEncodedHashAndTheBoundPort(): Unit =
        runBlocking {
            val packed = byteArrayOf(127, 0, 0, 1, 0x1A, 0xE1.toByte(), 10, 0, 0, 5, 0xC8.toByte(), 0xD5.toByte())
            val body = "d8:intervali1800e5:peers12:".encodeToByteArray() + packed + "e".encodeToByteArray()
            val url = serve(body = body)

            val response = HttpTrackerClient(dispatchers.io).announce(url, request(AnnounceEvent.STARTED))

            val query = requests.single()
            assertContains(query, "info_hash=%00%0B%16%21%2C7BMX")
            assertContains(query, "port=6882")
            assertContains(query, "compact=1")
            assertContains(query, "event=started")
            assertEquals(1800, response.interval)
            assertEquals(
                listOf(PeerAddress("127.0.0.1", 6881), PeerAddress("10.0.0.5", 51413)),
                response.peers,
            )
        }

    @Test
    fun theClientSpeaksHttpOneOne() {
        // Not a style preference. The default client offers an h2c upgrade on every cleartext
        // request, and bttracker.debian.org answers that with something the JDK cannot parse:
        // `chunked transfer encoding, state: READING_LENGTH`, against a tracker that returns a
        // correct Content-Length to a plain HTTP/1.1 request. No local server reproduces it —
        // one that understands the upgrade handles it correctly — so this is the only place the
        // decision can be pinned.
        assertEquals(java.net.http.HttpClient.Version.HTTP_1_1, HttpTrackerClient.defaultClient().version())
    }

    @Test
    fun aStoppedAnnounceSaysSo(): Unit =
        runBlocking {
            val url = serve(body = "d8:intervali1800e5:peers0:e".encodeToByteArray())
            HttpTrackerClient(dispatchers.io).announce(url, request(AnnounceEvent.STOPPED))
            assertContains(requests.single(), "event=stopped")
        }

    @Test
    fun aFailureReasonReachesTheCallerAsTheTrackersOwnWords(): Unit =
        runBlocking {
            val url = serve(body = "d14:failure reason9:forbiddene".encodeToByteArray())
            val thrown =
                assertFailsWith<TrackerException> {
                    HttpTrackerClient(dispatchers.io).announce(url, request())
                }
            assertEquals("forbidden", thrown.message)
        }

    @Test
    fun anHttpErrorIsATrackerErrorNamingTheStatus(): Unit =
        runBlocking {
            val url = serve(status = 503, body = "busy".encodeToByteArray())
            val thrown =
                assertFailsWith<TrackerException> {
                    HttpTrackerClient(dispatchers.io).announce(url, request())
                }
            assertContains(thrown.message ?: "", "503")
        }

    @Test
    fun anUnreachableTrackerIsReportedNotThrownAsAnIoFailure(): Unit =
        runBlocking {
            // Port 1 on localhost refuses; a torrent with ten trackers and one dead one must still work,
            // so this arrives as the tracker layer's own exception.
            val thrown =
                assertFailsWith<TrackerException> {
                    HttpTrackerClient(dispatchers.io).announce("http://127.0.0.1:1/annc", request())
                }
            assertTrue(
                thrown.message?.contains("unreachable") == true,
                "expected an unreachable message, got ${thrown.message}",
            )
        }
}
