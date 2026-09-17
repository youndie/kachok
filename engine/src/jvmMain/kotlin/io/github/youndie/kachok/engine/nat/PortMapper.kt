package io.github.youndie.kachok.engine.nat

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What the client knows about its own reachability from outside.
 *
 * **Three states and not two**, because "we did not manage to map a port" and "we did not try" call
 * for different things from a person, and lumping them together is how a status line stops being
 * read. [NotMapped] carries the router's own words where there were any.
 */
internal sealed interface PortMapping {
    /** Nobody has asked yet. */
    data object NotTried : PortMapping

    data class Mapped(
        val externalPort: Int,
        val lifetimeSeconds: Int,
    ) : PortMapping

    data class NotMapped(
        val because: String,
    ) : PortMapping
}

/**
 * Asks the router to forward this client's listening port.
 *
 * **What it is worth, measured rather than assumed.** In the twenty-minute run of B-98 this client
 * made 4 423 dials and 2 856 of them ended in `connect timed out` — peers behind a NAT that an
 * outgoing connection cannot reach. Those peers can reach *us*, but only if something forwards a
 * port, and `PeerListener`'s own header has said so since it was written: half a swarm's
 * connections are incoming.
 *
 * **And what it was worth on the network it was written on: nothing.** Neither the NAT-PMP request
 * nor a UPnP `M-SEARCH` drew any answer from the gateway there, and the reference client's log says
 * the same in its own words — `could not map port using UPnP: no router found`. That is not a
 * reason to skip this; it is the reason the *failing* path is the one this class is careful about.
 * A client on such a network must say "not mapped" quickly and clearly and get on with dialling,
 * rather than stalling at start-up for a router that is never going to answer.
 */
internal class PortMapper(
    private val gateway: InetAddress? = defaultGateway(),
    private val timeout: Duration = REQUEST_TIMEOUT,
    private val attempts: Int = ATTEMPTS,
    /**
     * The port the router listens on, which is 5351 and is a parameter only so that a test can put
     * a fake router on the loopback. Nothing in the client ever passes anything else.
     */
    private val routerPort: Int = NatPmp.PORT,
) {
    /**
     * Asks for [internalPort] to be forwarded, and answers within [timeout] × [attempts] whatever
     * happens.
     *
     * RFC 6886 asks for exponential back-off over a much longer total; this does not, and the
     * reason is that the client is starting up. A router that answers, answers in milliseconds;
     * one that has not answered in a few seconds is one this client should stop waiting for and
     * tell its owner about.
     */
    fun map(
        internalPort: Int,
        tcp: Boolean = true,
    ): PortMapping {
        val router = gateway ?: return PortMapping.NotMapped("no default gateway was found")
        return exchange(NatPmp.mapRequest(internalPort, internalPort, tcp = tcp), router)
            ?.let { mapping ->
                when {
                    !mapping.succeeded -> PortMapping.NotMapped(mapping.refusal ?: "the router refused")
                    mapping.externalPort == 0 -> PortMapping.NotMapped("the router mapped port 0, which is no port")
                    else -> PortMapping.Mapped(mapping.externalPort, mapping.lifetimeSeconds)
                }
            } ?: PortMapping.NotMapped("the router at ${router.hostAddress} does not answer NAT-PMP")
    }

    /**
     * Gives the mapping back.
     *
     * **Best effort, and it still has to be tried.** A client that maps a port and exits without
     * releasing leaves a hole in a router it does not own; the router will drop it when the lease
     * expires, which is up to two hours of somebody else's network being open for no reason.
     */
    fun release(
        internalPort: Int,
        tcp: Boolean = true,
    ) {
        val router = gateway ?: return
        exchange(
            NatPmp.mapRequest(internalPort, lifetimeSeconds = NatPmp.RELEASE_LIFETIME, tcp = tcp),
            router,
        )
    }

    /** When to renew: half the lease, which is RFC 6886's advice and leaves room for one failure. */
    fun renewAfter(mapping: PortMapping.Mapped): Duration = (mapping.lifetimeSeconds / 2).seconds

    private fun exchange(
        request: ByteArray,
        router: InetAddress,
    ): Mapping? {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeout.inWholeMilliseconds.toInt()
            repeat(attempts) {
                try {
                    socket.send(DatagramPacket(request, request.size, InetSocketAddress(router, routerPort)))
                    val buffer = ByteArray(RECEIVE_BUFFER)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    // A datagram that is not an answer to this is a non-event, not a failure: the
                    // socket hands over whatever reaches the port, and something else on the
                    // segment may be mapping ports too. Keep waiting rather than giving up.
                    NatPmp.parseResponse(buffer, packet.length)?.let { return it }
                } catch (silence: SocketTimeoutException) {
                    // The ordinary case on a router that does not speak this. Try again, then stop.
                } catch (unreachable: java.io.IOException) {
                    return null
                }
            }
        }
        return null
    }

    internal companion object {
        private val REQUEST_TIMEOUT = 2.seconds
        private const val ATTEMPTS = 2
        private const val RECEIVE_BUFFER = 64

        /**
         * The default gateway, by asking the operating system the only way a JVM can.
         *
         * **There is no route-table API in Java**, so this reads the platform's own command. That
         * is ugly and it is the honest ugly: the alternative is guessing, and a client that maps a
         * port on the wrong address has told its owner it is reachable when it is not.
         *
         * The fallback when the command is missing or unreadable is the first address of this
         * machine's own IPv4 subnet, which is where a home router is nine times in ten — and which
         * is a guess, so a mapping made through it is worth exactly what the router's answer says
         * and nothing more.
         */
        fun defaultGateway(): InetAddress? = fromRouteCommand() ?: subnetFirstAddress()

        private fun fromRouteCommand(): InetAddress? {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            val command =
                when {
                    os.contains("mac") || os.contains("darwin") -> listOf("route", "-n", "get", "default")
                    os.contains("win") -> listOf("cmd", "/c", "route print -4 0.0.0.0")
                    else -> listOf("ip", "route", "show", "default")
                }
            val output =
                try {
                    val process = ProcessBuilder(command).redirectErrorStream(true).start()
                    val text = process.inputStream.bufferedReader().readText()
                    process.waitFor()
                    text
                } catch (missing: java.io.IOException) {
                    // The command is not on this machine — a container without `iproute2`, say.
                    // Not an error: the subnet guess below is what that case is for.
                    return null
                } catch (interrupted: InterruptedException) {
                    // Somebody is shutting this client down while it looks for a gateway. Restore
                    // the flag and let the caller see no gateway rather than swallowing the signal.
                    Thread.currentThread().interrupt()
                    return null
                }
            return IPV4
                .findAll(output)
                .map { it.value }
                .firstOrNull { candidate ->
                    candidate != "0.0.0.0" && candidate != "127.0.0.1" && !candidate.endsWith(".255")
                }?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
        }

        private fun subnetFirstAddress(): InetAddress? =
            NetworkInterface
                .getNetworkInterfaces()
                ?.toList()
                ?.asSequence()
                ?.filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                ?.flatMap { it.interfaceAddresses.asSequence() }
                ?.mapNotNull { it.address as? Inet4Address }
                ?.firstOrNull { it.isSiteLocalAddress }
                ?.let { address ->
                    val octets = address.address
                    octets[3] = 1
                    runCatching { InetAddress.getByAddress(octets) }.getOrNull()
                }

        private val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    }
}
