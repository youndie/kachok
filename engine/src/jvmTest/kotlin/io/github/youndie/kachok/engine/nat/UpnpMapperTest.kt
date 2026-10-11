package io.github.youndie.kachok.engine.nat

import com.sun.net.httpserver.HttpServer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Finding the router when the multicast search cannot reach it
 * ([B-139](../../../../../../../../docs/backlog/B-139-upnp-searches-every-interface.md)).
 *
 * The router is a fake on the loopback: a UDP socket that answers an `M-SEARCH` with a `LOCATION`,
 * and an HTTP server that serves the description and takes `AddPortMapping`. The multicast group
 * the mapper is pointed at has nobody in it, which is the machine this was found on: a search that
 * leaves through the wrong adapter is a search nobody hears.
 */
class UpnpMapperTest {
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()
    private val ssdp = DatagramSocket(0, loopback)
    private val http: HttpServer = HttpServer.create(InetSocketAddress(loopback, 0), 0)
    private val soap = mutableListOf<String>()

    /** How the fake answers `AddPortMapping`: as most routers do, or as the netis did (B-139). */
    @Volatile private var refuseButMap = false

    /** Whether the mapping exists afterwards, which is what `GetSpecificPortMappingEntry` reports. */
    @Volatile private var mapped = false

    /** Whether a refused `AddPortMapping` still made the mapping, as the netis's did. */
    @Volatile private var reallyMaps = true

    init {
        val base = "http://${loopback.hostAddress}:${http.address.port}"
        http.createContext("/rootDesc.xml") { exchange ->
            val body = DESCRIPTION.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        http.createContext("/ctl/IPConn") { exchange ->
            val request = exchange.requestBody.readBytes().decodeToString()
            val action = exchange.requestHeaders.getFirst("SOAPAction").orEmpty()
            val (status, reply) =
                when {
                    "#AddPortMapping" in action -> {
                        synchronized(soap) { soap += request }
                        mapped = mapped || !refuseButMap || reallyMaps
                        if (refuseButMap) {
                            500 to fault(501)
                        } else {
                            200 to
                                "<s:Envelope><s:Body><u:AddPortMappingResponse/></s:Body></s:Envelope>"
                        }
                    }

                    "#GetSpecificPortMappingEntry" in action -> {
                        if (mapped) {
                            200 to "<s:Envelope><s:Body><u:GetSpecificPortMappingEntryResponse>" +
                                "<NewInternalPort>6881</NewInternalPort>" +
                                "<NewInternalClient>${loopback.hostAddress}</NewInternalClient>" +
                                "</u:GetSpecificPortMappingEntryResponse></s:Body></s:Envelope>"
                        } else {
                            500 to fault(714)
                        }
                    }

                    else -> {
                        500 to fault(401)
                    }
                }
            val body = reply.toByteArray()
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        http.start()
        Thread.ofVirtual().start {
            while (!ssdp.isClosed) {
                try {
                    val buffer = ByteArray(1024)
                    val packet = DatagramPacket(buffer, buffer.size)
                    ssdp.receive(packet)
                    if (!String(buffer, 0, packet.length).startsWith("M-SEARCH")) continue
                    val reply =
                        "HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n" +
                            "LOCATION: $base/rootDesc.xml\r\n\r\n"
                    ssdp.send(DatagramPacket(reply.toByteArray(), reply.length, packet.socketAddress))
                } catch (closed: java.io.IOException) {
                    return@start
                }
            }
        }
    }

    @AfterTest
    fun close() {
        ssdp.close()
        http.stop(0)
    }

    /** Nobody in the group, but the gateway answers a search sent to it by name. */
    @Test
    fun theGatewayAskedDirectlyIsFoundWhenTheGroupIsSilent() {
        val mapper =
            UpnpMapper(
                discoveryTimeout = 2.seconds,
                searchAddress = SILENT_GROUP,
                searchPort = ssdp.localPort,
                gateway = loopback,
                localAddresses = { emptyList() },
            )

        val mapping = mapper.map(6881)

        assertIs<PortMapping.Mapped>(mapping, "the gateway answered and the mapper did not map: $mapping")
        assertEquals(6881, mapping.externalPort)
        val request = synchronized(soap) { soap.single() }
        assertContains(request, "<NewInternalPort>6881</NewInternalPort>")
        assertContains(request, "<NewInternalClient>${loopback.hostAddress}</NewInternalClient>")
    }

    /**
     * The netis: 501 ActionFailed to the first `AddPortMapping`, and the mapping made anyway. The
     * router's own table is asked, and the mapping is reported as made (B-139).
     */
    @Test
    fun aRefusalTheRoutersTableContradictsIsAMapping() {
        refuseButMap = true
        assertIs<PortMapping.Mapped>(
            gatewayMapper().map(6881),
            "the router mapped the port and the client believed its 501",
        )
    }

    /** A refusal the table confirms stays a refusal, with the router's words. */
    @Test
    fun aRefusalTheTableConfirmsIsReported() {
        refuseButMap = true
        reallyMaps = false
        val mapping = gatewayMapper().map(6881)
        assertIs<PortMapping.NotMapped>(mapping)
        assertContains(mapping.because, "501")
    }

    /** The DHT's port is mapped for UDP, and given back for UDP (B-143). */
    @Test
    fun aUdpMappingAsksForUdp() {
        val mapper = gatewayMapper()

        assertIs<PortMapping.Mapped>(mapper.map(6881, tcp = false))

        assertContains(synchronized(soap) { soap.single() }, "<NewProtocol>UDP</NewProtocol>")
    }

    private fun gatewayMapper() =
        UpnpMapper(
            discoveryTimeout = 2.seconds,
            searchAddress = SILENT_GROUP,
            searchPort = ssdp.localPort,
            gateway = loopback,
            localAddresses = { emptyList() },
        )

    /** The search goes out from each listed local address, and a reply to any of them is taken. */
    @Test
    fun aSearchFromAListedAddressIsAnswered() {
        val mapper =
            UpnpMapper(
                discoveryTimeout = 2.seconds,
                searchAddress = loopback.hostAddress,
                searchPort = ssdp.localPort,
                gateway = null,
                localAddresses = { listOf(loopback) },
            )

        assertIs<PortMapping.Mapped>(mapper.map(6881))
    }

    /** No router anywhere: the mapper says so within its window and does not hang. */
    @Test
    fun nobodyAnsweringEndsWithinTheWindow() {
        ssdp.close()
        val mapper =
            UpnpMapper(
                discoveryTimeout = 1.seconds,
                searchAddress = SILENT_GROUP,
                searchPort = 1,
                gateway = null,
                localAddresses = { emptyList() },
            )
        val started = System.nanoTime()

        val mapping = mapper.map(6881)

        assertIs<PortMapping.NotMapped>(mapping)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000, "the search outlived its window")
        assertFalse(soap.isNotEmpty(), "a mapping was asked of nobody")
    }

    private fun fault(code: Int) =
        "<s:Envelope><s:Body><s:Fault><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">" +
            "<errorCode>$code</errorCode><errorDescription>x</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"

    private companion object {
        /** A multicast group nothing listens on: the wrong adapter's view of the network. */
        const val SILENT_GROUP = "239.255.255.251"

        val DESCRIPTION =
            """
            <root><device><serviceList><service>
            <serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>
            <controlURL>/ctl/IPConn</controlURL>
            </service></serviceList></device></root>
            """.trimIndent()
    }
}
