package io.github.youndie.kachok.engine.nat

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.peer.PeerAddress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One socket on the local segment: announcing what this client holds, and hearing what others do.
 *
 * **Loopback is left on, which is the opposite of what a multicast socket usually wants.** Two
 * copies of this client on one machine is not a contrived case — it is how the acceptance for
 * [B-102](../../../../../../../../docs/backlog/B-102-local-service-discovery.md) is run, and it is
 * what a person testing a seedbox does. With loopback off they would never hear each other. Hearing
 * *ourselves* is handled where it belongs, by [Lsd]'s cookie, rather than by switching off a whole
 * class of peer.
 *
 * The socket joins on every interface that will have it. A machine with a wired and a wireless
 * interface on the same segment is ordinary, and joining on the one the operating system picks by
 * default finds nobody roughly half the time — a failure that looks exactly like "nobody else is
 * here".
 */
internal class LsdSocket(
    private val dispatchers: CoroutineDispatcher,
    /** This client's own value, to recognise its announces coming back. Random per process. */
    private val cookie: String = "kachok-" + Random.nextLong().toString(16),
    private val interval: Duration = Lsd.INTERVAL_SECONDS.seconds,
    /**
     * The group and port, which are BEP 14's and are parameters only so a test can use its own.
     *
     * **The default is not always available**, and that is a fact about machines rather than about
     * this code: 6771 is held by whichever BitTorrent client started first, and on the build machine
     * here it is held through WSL's port mirroring by the reference client running on Windows. A
     * test bound to it would be a test that reports "unavailable" for ever, which is a check that
     * never runs pretending to be one that passes.
     */
    private val groupAddress: String = Lsd.GROUP_V4,
    /**
     * Named `groupPort` and not `port`, which is not a style choice.
     *
     * Inside the `apply` on the socket below, a bare `port` resolves to `MulticastSocket.getPort()`
     * — which is −1 while the socket is unbound — rather than to this parameter. The result was a
     * bind to port −1, and the only reason it was not shipped is that the acceptance test binds two
     * sockets and looked at the exception.
     */
    private val groupPort: Int = Lsd.PORT,
) : AutoCloseable {
    private val group = InetAddress.getByName(groupAddress)
    private var socket: MulticastSocket? = null
    private var jobs = mutableListOf<Job>()

    /**
     * Starts announcing and listening, or returns the reason it could not.
     *
     * A refusal is a sentence rather than an exception: a machine with multicast switched off, or a
     * container without a route for it, is a normal thing to run on and not a reason for a client
     * to fail to start.
     */
    fun start(
        scope: CoroutineScope,
        listenPort: Int,
        held: () -> List<InfoHash>,
        onPeer: (InfoHash, PeerAddress) -> Unit,
    ): String? {
        val opened =
            try {
                // **Unbound first, then `SO_REUSEADDR`, then bound — and the order is the whole
                // trick.** `MulticastSocket(port)` binds inside the constructor, so setting the
                // option afterwards is too late and the second client on a machine gets "address
                // already in use". Two clients on one machine sharing 6771 is not an edge case: it
                // is a seedbox, and it is this item's own acceptance.
                MulticastSocket(null as java.net.SocketAddress?).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(groupPort))
                    // **`IP_MULTICAST_LOOP` and not the deprecated `loopbackMode`, and the two are
                    // inverted with respect to each other.** The old property took *true* to mean
                    // "disable loopback"; the socket option takes true to mean "enable it". Porting
                    // one to the other by keeping the value is how a client stops hearing the copy
                    // of itself on the same machine — which is precisely the case this is for.
                    setOption(java.net.StandardSocketOptions.IP_MULTICAST_LOOP, true)
                    setOption(java.net.StandardSocketOptions.IP_MULTICAST_TTL, TTL)
                    joinAll(this)
                }
            } catch (unavailable: IOException) {
                return unavailable.message ?: "the multicast socket could not be opened"
            }
        socket = opened

        jobs +=
            scope.launch(dispatchers) {
                while (isActive) {
                    held().forEach { infoHash ->
                        val datagram = Lsd.announce(infoHash, listenPort, cookie)
                        try {
                            opened.send(DatagramPacket(datagram, datagram.size, InetSocketAddress(group, groupPort)))
                        } catch (gone: IOException) {
                            return@launch
                        }
                    }
                    delay(interval)
                }
            }

        jobs +=
            scope.launch(dispatchers) {
                val buffer = ByteArray(RECEIVE_BUFFER)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        opened.receive(packet)
                    } catch (closed: IOException) {
                        return@launch
                    }
                    val text = String(buffer, 0, packet.length, Charsets.ISO_8859_1)
                    val heard = Lsd.parse(text, cookie) ?: continue
                    // The address is the datagram's sender and the port is the one it named. They
                    // are different things: the sender's port is whatever the socket bound, and a
                    // peer that dialled us from it is not a peer anybody can dial back.
                    onPeer(heard.infoHash, PeerAddress(packet.address.hostAddress, heard.port))
                }
            }
        return null
    }

    override fun close() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        socket?.close()
        socket = null
    }

    /**
     * Joins on every interface that will take it, and succeeds if any one does.
     *
     * A machine with a wired and a wireless interface on one segment is ordinary; joining only on
     * the default one finds nobody about half the time, and the symptom is indistinguishable from
     * an empty segment.
     */
    private fun joinAll(socket: MulticastSocket) {
        val address = InetSocketAddress(group, groupPort)
        var joined = false
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { candidate ->
            val usable =
                runCatching { candidate.isUp && candidate.supportsMulticast() && !candidate.isLoopback }
                    .getOrDefault(false)
            if (!usable) return@forEach
            runCatching { socket.joinGroup(address, candidate) }.onSuccess { joined = true }
        }
        if (!joined) {
            // Loopback last: on a machine with no usable interface it is the only way two copies of
            // this client can still find each other, which is exactly the acceptance case.
            NetworkInterface
                .getNetworkInterfaces()
                ?.toList()
                ?.firstOrNull { runCatching { it.isLoopback && it.supportsMulticast() }.getOrDefault(false) }
                ?.let { runCatching { socket.joinGroup(address, it) } }
        }
    }

    private companion object {
        /** One hop: BEP 14 is for the segment, and a wider TTL is somebody else's network. */
        const val TTL = 1
        const val RECEIVE_BUFFER = 1500
    }
}
