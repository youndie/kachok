package io.github.youndie.kachok.engine.io

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.PeerId
import io.github.youndie.kachok.engine.peer.PeerAddress
import io.github.youndie.kachok.engine.peer.PeerConnection
import io.github.youndie.kachok.engine.peer.PeerDialer
import io.github.youndie.kachok.engine.wire.Handshake
import kotlinx.coroutines.CoroutineScope

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
