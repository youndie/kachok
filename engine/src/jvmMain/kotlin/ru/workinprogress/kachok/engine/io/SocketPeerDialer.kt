package ru.workinprogress.kachok.engine.io

import kotlinx.coroutines.CoroutineScope
import ru.workinprogress.kachok.engine.InfoHash
import ru.workinprogress.kachok.engine.PeerId
import ru.workinprogress.kachok.engine.peer.PeerAddress
import ru.workinprogress.kachok.engine.peer.PeerConnection
import ru.workinprogress.kachok.engine.peer.PeerDialer
import ru.workinprogress.kachok.engine.wire.Handshake

/**
 * The JVM's answer to "connect me to this peer": a blocking `SocketChannel` and a virtual thread.
 *
 * It holds the scope the connection's reader and writer are launched into. That scope is the
 * session's, so a cancelled session cancels the coroutines of every connection it dialled — which
 * is why the dialer takes one rather than making its own.
 */
public class SocketPeerDialer(
    private val scope: CoroutineScope,
    private val infoHash: InfoHash,
    private val peerId: PeerId,
    private val pool: BufferPool,
    private val reserved: ByteArray = Handshake.reservedBits(),
) : PeerDialer {
    override suspend fun connect(address: PeerAddress): PeerConnection =
        SocketPeerConnection.connect(scope, address, infoHash, peerId, pool, reserved)
}
