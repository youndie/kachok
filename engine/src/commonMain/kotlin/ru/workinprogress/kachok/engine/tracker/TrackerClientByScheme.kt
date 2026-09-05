package ru.workinprogress.kachok.engine.tracker

/**
 * One [TrackerClient] over several transports, chosen by the URL's scheme.
 *
 * A `.torrent` announce list mixes `http://`, `https://` and `udp://` freely — most public torrents
 * today are mostly the last — and the session announces to trackers in the order the file gives
 * them. Without this the first `udp://` entry reached `HttpRequest.newBuilder`, which throws
 * `IllegalArgumentException: invalid URI scheme udp`: not a [TrackerException], so not the
 * "this tracker refused, try the next" the session handles, but an exception out of the announce
 * loop entirely.
 */
public class TrackerClientByScheme(
    private val http: TrackerClient,
    private val udp: TrackerClient,
) : TrackerClient {
    override suspend fun announce(
        tracker: String,
        request: AnnounceRequest,
    ): AnnounceResponse {
        val scheme = tracker.substringBefore("://", missingDelimiterValue = "").lowercase()
        return when (scheme) {
            "http", "https" -> http.announce(tracker, request)

            "udp" -> udp.announce(tracker, request)

            // A scheme this client does not speak is one tracker's problem, not the torrent's, so
            // it is refused the way a tracker refusing is: the session moves to the next one.
            "" -> throw TrackerException("`$tracker` is not a tracker URL")

            else -> throw TrackerException("tracker scheme `$scheme` is not supported")
        }
    }
}
