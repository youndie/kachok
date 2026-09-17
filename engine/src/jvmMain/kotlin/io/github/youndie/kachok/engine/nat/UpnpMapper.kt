package io.github.youndie.kachok.engine.nat

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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

    fun map(internalPort: Int): PortMapping {
        val found = device ?: discover() ?: return PortMapping.NotMapped("no UPnP gateway answered")
        device = found
        val body =
            Upnp.addPortMapping(
                service = found.service,
                internalHost = found.internalHost,
                internalPort = internalPort,
                externalPort = internalPort,
                description = DESCRIPTION,
            )
        val reply =
            soap(found, "AddPortMapping", body) ?: return PortMapping.NotMapped("the UPnP gateway did not reply")
        Upnp.fault(reply)?.let { return PortMapping.NotMapped(it) }
        // **The external port is the one asked for and not one the router chose.** `AddPortMapping`
        // has no way to say otherwise: it either maps what it was given or refuses with 718, which
        // the fault above has already turned into words. A router that quietly mapped something
        // else would have to be found with `GetSpecificPortMappingEntry`, and no router does that.
        return PortMapping.Mapped(externalPort = internalPort, lifetimeSeconds = Upnp.LEASE_SECONDS)
    }

    fun release(internalPort: Int) {
        val found = device ?: return
        soap(found, "DeletePortMapping", Upnp.deletePortMapping(found.service, internalPort))
    }

    /**
     * One `M-SEARCH`, then whatever answers before the deadline.
     *
     * Replies are scattered across the `MX` window by design, so this reads until the socket times
     * out rather than taking the first datagram and running — but it stops at the first reply that
     * turns out to be a gateway that can map, because a segment with two of those is a segment
     * where either will do.
     */
    private fun discover(): Device? {
        val search = Upnp.search()
        DatagramSocket().use { socket ->
            socket.soTimeout = discoveryTimeout.inWholeMilliseconds.toInt()
            try {
                socket.send(
                    DatagramPacket(
                        search,
                        search.size,
                        InetSocketAddress(InetAddress.getByName(searchAddress), searchPort),
                    ),
                )
            } catch (unreachable: java.io.IOException) {
                return null
            }
            val deadline = System.nanoTime() + discoveryTimeout.inWholeNanoseconds
            while (System.nanoTime() < deadline) {
                val buffer = ByteArray(RECEIVE_BUFFER)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (silence: SocketTimeoutException) {
                    return null
                } catch (broken: java.io.IOException) {
                    return null
                }
                val reply = String(buffer, 0, packet.length, Charsets.ISO_8859_1)
                val location = Upnp.location(reply) ?: continue
                describe(location, packet.address)?.let { return it }
            }
        }
        return null
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
    }
}
