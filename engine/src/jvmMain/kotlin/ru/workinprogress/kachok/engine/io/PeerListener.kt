package ru.workinprogress.kachok.engine.io

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.workinprogress.kachok.engine.tracker.TrackerProtocol
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/**
 * The port other peers dial.
 *
 * Half a swarm's connections are incoming, so a client that only dials meets half the peers it
 * could — and is invisible to anyone behind a tracker that hands out its address. BEP 3 names the
 * range: "try to listen on port 6881 and if that port is taken try 6882, then 6883, etc. and give
 * up after 6889".
 *
 * Whether to accept at all, and on which port, is the caller's decision; this class implements the
 * probe and nothing else.
 */
public class PeerListener private constructor(
    private val server: ServerSocketChannel,
    /** The port that was free. This is the one the tracker must be told about, not the one asked for. */
    public val port: Int,
) : AutoCloseable {
    /**
     * Accepts until cancelled, handing each socket to [handle] on its own coroutine.
     *
     * One coroutine per accepted socket for the same reason as one per dialled peer: the handshake
     * that follows is blocking, and a peer that connects and then says nothing must not hold up
     * the peer behind it in the queue.
     */
    public fun start(
        scope: CoroutineScope,
        handle: suspend (SocketChannel) -> Unit,
    ): Job =
        scope.launch {
            while (server.isOpen) {
                val socket =
                    try {
                        server.accept()
                    } catch (closed: IOException) {
                        return@launch
                    }
                launch { handle(socket) }
            }
        }

    override fun close() {
        try {
            server.close()
        } catch (ignored: IOException) {
            // Shutting down; a listener that fails to close has nothing left to report.
        }
    }

    public companion object {
        private const val BACKLOG = 128

        /**
         * Binds the first free port of [ports], on **both** address families where the platform
         * has one stack for them — or fails naming the range.
         *
         * Failing is the right answer rather than picking an ephemeral port: the port is announced
         * to the tracker, and a client quietly listening somewhere nobody was told about is a
         * client that believes it is reachable and is not.
         *
         * `host = null` means the wildcard address, which on a dual-stack JVM is `::` and accepts
         * IPv4 connections as v4-mapped addresses. Binding `0.0.0.0`, which this did, is a client
         * that announces a port no IPv6 peer can reach (BEP 7).
         */
        public fun bind(
            ports: IntRange = TrackerProtocol.PORT_RANGE,
            host: String? = null,
        ): PeerListener {
            ports.forEach { port ->
                val server = ServerSocketChannel.open()
                try {
                    // Without this a port this client used a minute ago cannot be taken again:
                    // its old connections sit in TIME_WAIT and `bind` answers "address already in
                    // use". A restarted client would then announce a different port every time,
                    // and peers holding the old address would find nobody there.
                    server.setOption(StandardSocketOptions.SO_REUSEADDR, true)
                    val address = if (host == null) InetSocketAddress(port) else InetSocketAddress(host, port)
                    server.bind(address, BACKLOG)
                    return PeerListener(server, port)
                } catch (taken: BindException) {
                    server.close()
                }
            }
            throw BindException("every port in $ports is taken")
        }
    }
}
