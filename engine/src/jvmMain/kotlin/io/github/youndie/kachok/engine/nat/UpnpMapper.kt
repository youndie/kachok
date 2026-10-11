package io.github.youndie.kachok.engine.nat

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * The UPnP IGD half of [PortMapper]: a datagram to find the router, then two HTTP calls.
 *
 * `java.net.http` and not a socket, because the client already carries that module for tracker
 * announces (research §D8) and this is one request; and because a hand-written HTTP client for a
 * device whose replies are as varied as these is a second parser to get wrong.
 *
 * **Everything it decides is in [Upnp] and everything it does is here**, so the deciding is
 * testable without a router — which matters when there is no router on the network that answers.
 */
internal class UpnpMapper(
    private val discoveryTimeout: Duration = DISCOVERY_TIMEOUT,
    private val httpTimeout: Duration = HTTP_TIMEOUT,
    private val searchAddress: String = Upnp.SSDP_ADDRESS,
    private val searchPort: Int = Upnp.SSDP_PORT,
    /**
     * The default gateway, asked directly as well as through the multicast group
     * ([B-139](../../../../../../../../docs/backlog/B-139-upnp-searches-every-interface.md)). Most
     * routers answer a unicast `M-SEARCH`, and a datagram to one address needs no interface chosen.
     */
    private val gateway: InetAddress? = null,
    /** The local addresses the multicast search goes out from; one socket each. */
    private val localAddresses: () -> List<InetAddress> = ::routableAddresses,
) {
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(httpTimeout.toJavaDuration())
            .build()

    /** The device this found, kept so that a release does not repeat the discovery. */
    private var device: Device? = null

    private class Device(
        val controlUrl: String,
        val service: String,
        val internalHost: String,
    )

    fun map(
        internalPort: Int,
        tcp: Boolean = true,
    ): PortMapping {
        val protocol = if (tcp) Upnp.TCP else Upnp.UDP
        val found = device ?: discover() ?: return PortMapping.NotMapped("no UPnP gateway answered")
        device = found
        val body =
            Upnp.addPortMapping(
                service = found.service,
                internalHost = found.internalHost,
                internalPort = internalPort,
                externalPort = internalPort,
                description = DESCRIPTION,
                protocol = protocol,
            )
        val reply =
            soap(found, "AddPortMapping", body) ?: return PortMapping.NotMapped("the UPnP gateway did not reply")
        Upnp.fault(reply)?.let { refusal ->
            // **A refusal is checked, not believed.** The netis router this was found on answers
            // the first `AddPortMapping` for a port with 501 ActionFailed *and makes the mapping*:
            // `GetSpecificPortMappingEntry` straight after shows it, pointed at this machine, and
            // a second identical request is answered OK. Taking the 501 at its word left a mapped
            // client telling its owner the router had refused (B-139).
            if (!mappedTo(found, internalPort, protocol)) return PortMapping.NotMapped(refusal)
        }
        // **The external port is the one asked for and not one the router chose.** `AddPortMapping`
        // has no way to say otherwise: it either maps what it was given or refuses with 718, which
        // the fault above has already turned into words. A router that quietly mapped something
        // else would have to be found with `GetSpecificPortMappingEntry`, and no router does that.
        return PortMapping.Mapped(externalPort = internalPort, lifetimeSeconds = Upnp.LEASE_SECONDS)
    }

    /** Whether the router's own table forwards [port] to this machine's [Device.internalHost]. */
    private fun mappedTo(
        device: Device,
        port: Int,
        protocol: String,
    ): Boolean {
        val entry =
            soap(
                device,
                "GetSpecificPortMappingEntry",
                Upnp.getSpecificPortMappingEntry(device.service, port, protocol),
            ) ?: return false
        if (Upnp.fault(entry) != null) return false
        return Upnp.mappingTarget(entry) == (device.internalHost to port)
    }

    fun release(
        internalPort: Int,
        tcp: Boolean = true,
    ) {
        val found = device ?: return
        val protocol = if (tcp) Upnp.TCP else Upnp.UDP
        soap(found, "DeletePortMapping", Upnp.deletePortMapping(found.service, internalPort, protocol))
    }

    /**
     * The `M-SEARCH`, from every routable local address and straight to the gateway, then whatever
     * answers on any of them before the deadline.
     *
     * **One unbound socket used to send it, and the operating system chose the interface.** On a
     * Windows machine with a Wi-Fi adapter on the router's network and four more holding link-local
     * `169.254` addresses, the search left through one of those and nothing ever answered, while
     * the same search sent from the Wi-Fi address drew eleven replies from the router. So the group
     * is asked once per address, and the gateway once more by name (B-139).
     *
     * Replies are scattered across the `MX` window by design, so this reads until the deadline
     * rather than taking the first datagram, but it stops at the first reply that turns out to be
     * a gateway that can map, because a segment with two of those is one where either will do.
     */
    private fun discover(): Device? {
        val search = ByteBuffer.wrap(Upnp.search())
        val group = InetSocketAddress(InetAddress.getByName(searchAddress), searchPort)
        Selector.open().use { selector ->
            val channels = mutableListOf<DatagramChannel>()
            try {
                val sources: List<InetAddress?> = localAddresses().ifEmpty { listOf(null) }
                sources.forEach { local ->
                    open(local, group, search.duplicate())?.let { channels += it }
                }
                gateway?.let { router ->
                    open(null, InetSocketAddress(router, searchPort), search.duplicate())?.let { channels += it }
                }
                if (channels.isEmpty()) return null
                channels.forEach { it.register(selector, SelectionKey.OP_READ) }
                val deadline = System.nanoTime() + discoveryTimeout.inWholeNanoseconds
                val buffer = ByteBuffer.allocate(RECEIVE_BUFFER)
                while (true) {
                    val left = (deadline - System.nanoTime()) / NANOS_PER_MILLI
                    if (left <= 0) return null
                    selector.select(left)
                    val ready = selector.selectedKeys().toList()
                    selector.selectedKeys().clear()
                    for (key in ready) {
                        val channel = key.channel() as DatagramChannel
                        buffer.clear()
                        val from = channel.receive(buffer) as? InetSocketAddress ?: continue
                        buffer.flip()
                        val reply = Charsets.ISO_8859_1.decode(buffer).toString()
                        val location = Upnp.location(reply) ?: continue
                        describe(location, from.address)?.let { return it }
                    }
                }
            } finally {
                channels.forEach { closeQuietly(it) }
            }
        }
    }

    /**
     * A channel bound to [local] (or to any address), with the search already sent to [target].
     * Null when this address cannot send: an adapter that went down between the listing and now.
     */
    private fun open(
        local: InetAddress?,
        target: InetSocketAddress,
        search: ByteBuffer,
    ): DatagramChannel? {
        val channel = DatagramChannel.open(StandardProtocolFamily.INET)
        return try {
            channel.bind(InetSocketAddress(local, 0))
            if (local != null && target.address.isMulticastAddress) {
                NetworkInterface.getByInetAddress(local)?.let {
                    channel.setOption(StandardSocketOptions.IP_MULTICAST_IF, it)
                }
            }
            channel.configureBlocking(false)
            channel.send(search, target)
            channel
        } catch (unusable: java.io.IOException) {
            closeQuietly(channel)
            null
        }
    }

    /** Fetches the device description and reads the control URL out of it. */
    private fun describe(
        location: String,
        from: InetAddress,
    ): Device? {
        val description = get(location) ?: return null
        val control = Upnp.controlUrl(description) ?: return null
        val service = Upnp.service(description) ?: return null
        // **The address the router sees us at, not `InetAddress.getLocalHost()`.** On a machine with
        // several interfaces the latter is a coin toss, and a mapping pointed at the wrong one is a
        // router forwarding to nothing while telling this client it succeeded.
        val internalHost =
            runCatching {
                DatagramSocket().use {
                    it.connect(from, Upnp.SSDP_PORT)
                    it.localAddress.hostAddress
                }
            }.getOrNull() ?: return null
        return Device(Upnp.absolute(location, control), service, internalHost)
    }

    private fun closeQuietly(channel: DatagramChannel) {
        try {
            channel.close()
        } catch (alreadyGone: java.io.IOException) {
            // A socket that cannot even be closed has nothing left to give back.
        }
    }

    private fun get(url: String): String? =
        send(
            HttpRequest
                .newBuilder(URI.create(url))
                .timeout(httpTimeout.toJavaDuration())
                .GET()
                .build(),
        )

    private fun soap(
        device: Device,
        action: String,
        body: String,
    ): String? =
        send(
            HttpRequest
                .newBuilder(URI.create(device.controlUrl))
                .timeout(httpTimeout.toJavaDuration())
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                // Routers check this one and refuse without it, which is the kind of failure that
                // looks like the body being wrong.
                .header("SOAPAction", Upnp.soapAction(device.service, action))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
        )

    /**
     * Sends a request and returns the body **whatever the status**.
     *
     * A UPnP refusal arrives as HTTP 500 carrying the fault this client needs to read; treating a
     * non-2xx as nothing would throw away the only explanation the router gives.
     */
    private fun send(request: HttpRequest): String? =
        runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()).body() }
            .getOrNull()

    internal companion object {
        /** What the mapping is called in the router's own table, where a person may go looking. */
        const val DESCRIPTION = "kachok"

        private val DISCOVERY_TIMEOUT = 3.seconds
        private val HTTP_TIMEOUT = 5.seconds
        private const val RECEIVE_BUFFER = 2048
        private const val NANOS_PER_MILLI = 1_000_000L

        /**
         * Every IPv4 address this machine could reach a router from: on an interface that is up,
         * not the loopback, able to send multicast, and not link-local. A `169.254` address is an
         * adapter that never got one from anybody, the kind that swallowed the search.
         */
        fun routableAddresses(): List<InetAddress> =
            try {
                NetworkInterface
                    .networkInterfaces()
                    .toList()
                    .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
                    .flatMap { it.inetAddresses().toList() }
                    .filter { it is Inet4Address && Upnp.routable(it.address) }
            } catch (unlistable: java.net.SocketException) {
                emptyList()
            }
    }
}
