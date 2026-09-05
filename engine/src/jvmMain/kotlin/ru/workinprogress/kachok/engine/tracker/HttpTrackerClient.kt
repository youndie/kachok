package ru.workinprogress.kachok.engine.tracker

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * An announce over HTTP, on the JDK's own client (research D8).
 *
 * One GET per interval per tracker does not justify a second HTTP stack in the run-time image, and
 * the interface above this class is what a second platform will implement instead of inheriting
 * this one.
 *
 * The send is **blocking**, on purpose: it runs on the engine's virtual-thread dispatcher, where a
 * blocking socket read parks the virtual thread and frees its carrier (research §1.1). The
 * asynchronous API would add a callback chain to reach the same place.
 */
public class HttpTrackerClient(
    private val dispatcher: CoroutineDispatcher,
    private val client: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build(),
    private val requestTimeout: Duration = Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS),
    private val userAgent: String = DEFAULT_USER_AGENT,
) : TrackerClient {
    override suspend fun announce(
        tracker: String,
        request: AnnounceRequest,
    ): AnnounceResponse =
        withContext(dispatcher) {
            val url = TrackerProtocol.announceUrl(tracker, request)
            val uri =
                try {
                    URI.create(url)
                } catch (malformed: IllegalArgumentException) {
                    throw TrackerException("tracker URL is not a URI: ${malformed.message}")
                }
            val response =
                try {
                    client.send(
                        HttpRequest
                            .newBuilder(uri)
                            .header("User-Agent", userAgent)
                            .timeout(requestTimeout)
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.ofByteArray(),
                    )
                } catch (failure: java.io.IOException) {
                    throw TrackerException("tracker $tracker is unreachable: ${failure.message}")
                }
            if (response.statusCode() !in 200..299) {
                throw TrackerException("tracker $tracker answered HTTP ${response.statusCode()}")
            }
            TrackerProtocol.parseResponse(response.body())
        }

    public companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val REQUEST_TIMEOUT_SECONDS = 30L

        /** BEP 20's Azureus style: `-KA` and a four-digit version. */
        public const val DEFAULT_USER_AGENT: String = "kachok/0.1"
    }
}
