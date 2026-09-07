package io.github.youndie.kachok.engine.runtime

import io.github.youndie.kachok.engine.PeerId
import io.github.youndie.kachok.engine.io.BufferPool
import io.github.youndie.kachok.engine.io.EngineDispatchers
import io.github.youndie.kachok.engine.io.SocketPeerDialer
import io.github.youndie.kachok.engine.metainfo.MagnetLink
import io.github.youndie.kachok.engine.metainfo.MetadataFetcher
import io.github.youndie.kachok.engine.metainfo.Metainfo
import io.github.youndie.kachok.engine.tracker.HttpTrackerClient
import io.github.youndie.kachok.engine.tracker.TrackerClientByScheme
import io.github.youndie.kachok.engine.tracker.UdpTrackerClient
import io.github.youndie.kachok.engine.wire.Handshake
import kotlinx.coroutines.CoroutineScope
import kotlin.random.Random

/**
 * BEP 9: a magnet link names a torrent and carries none of it.
 *
 * The peers this asks are the link's trackers; a magnet with none has nowhere to look unless the
 * DHT is on. Lifted out of the headless client when the window needed the same thing — the same
 * argument as `TorrentSet`: two copies of a wiring are two clients, of which the second is always
 * the one that is wrong.
 *
 * Deliberately *not* a method on [TorrentSet]. A fetch has no session, no files and no port of its
 * own; it is a thing that happens before a torrent exists, and giving it a home inside the set
 * would make the set hold half-torrents.
 */
public suspend fun fetchMetainfo(
    link: MagnetLink,
    scope: CoroutineScope,
    dispatchers: EngineDispatchers,
    listenPort: Int,
): Metainfo {
    val identity = randomPeerId()
    // Enough buffers to talk to a handful of peers about a few dozen kibibytes. Sizing this from
    // the piece length would size it from a number the magnet does not carry yet.
    val pool = BufferPool(capacity = FETCH_POOL)
    return MetadataFetcher(
        link = link,
        peerId = identity,
        listenPort = listenPort,
        dialer =
            SocketPeerDialer(
                scope,
                link.infoHash,
                identity,
                pool,
                Handshake.reservedBits(extensionProtocol = true, fastExtension = true),
            ),
        trackerClient =
            TrackerClientByScheme(
                http = HttpTrackerClient(dispatchers.io),
                udp = UdpTrackerClient(dispatchers.io),
            ),
        blocking = dispatchers.io,
    ).fetch(scope)
}

/**
 * BEP 20's Azureus style, generated once for this fetch.
 *
 * Not the session's: the session does not exist yet, and a fetch that borrowed an id a torrent
 * would later announce would have told a tracker about a peer that was never there.
 */
private fun randomPeerId(): PeerId {
    val bytes = ByteArray(PeerId.SIZE)
    "-KA0100-".encodeToByteArray().copyInto(bytes)
    Random.Default.nextBytes(bytes, PREFIX, PeerId.SIZE)
    return PeerId(bytes)
}

private const val FETCH_POOL = 64
private const val PREFIX = 8
