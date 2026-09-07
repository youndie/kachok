package io.github.youndie.kachok.engine.tracker

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.PeerId
import io.github.youndie.kachok.engine.peer.PeerAddress

/** BEP 3's `event`. Absent means "one of the announcements done at regular intervals". */
public enum class AnnounceEvent {
    STARTED,
    COMPLETED,
    STOPPED,
    ;

    public val wireName: String get() = name.lowercase()
}

/**
 * What the client tells a tracker about itself (BEP 3, *trackers*).
 *
 * `left` is not `total - downloaded` and BEP 3 says so explicitly: after a resume with a failed
 * hash check the two differ, so the session computes it from the bitfield.
 */
public class AnnounceRequest(
    public val infoHash: InfoHash,
    public val peerId: PeerId,
    public val port: Int,
    public val uploaded: Long,
    public val downloaded: Long,
    public val left: Long,
    public val event: AnnounceEvent? = null,
    public val numWant: Int? = null,
)

/** What a tracker answers. A `failure reason` is a [TrackerException] instead. */
public class AnnounceResponse(
    public val interval: Int,
    public val peers: List<PeerAddress>,
    public val minInterval: Int? = null,
    public val seeders: Int? = null,
    public val leechers: Int? = null,
)

/**
 * A tracker that refused, or answered something this client cannot read.
 *
 * A tracker failure is not a swarm failure: a torrent with ten trackers and one dead one must
 * still work, so this is reported per tracker rather than thrown at the session.
 */
public class TrackerException(
    message: String,
) : RuntimeException(message)

/**
 * One announce.
 *
 * An interface because the transport is the platform's — `java.net.http` on the JVM (research D8)
 * — while the query string and the response parsing are the same everywhere and live in
 * [TrackerProtocol].
 */
public interface TrackerClient {
    public suspend fun announce(
        tracker: String,
        request: AnnounceRequest,
    ): AnnounceResponse
}
