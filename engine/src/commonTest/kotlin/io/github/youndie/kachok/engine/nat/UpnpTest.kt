package io.github.youndie.kachok.engine.nat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B-103: the text half of UPnP, which is where it goes wrong.
 *
 * The device description is scanned rather than parsed, and the two ways that can silently pick the
 * wrong thing — a header spelled differently, and the *first* control URL in a document with
 * several — each have a test whose fixture contains the trap.
 */
class UpnpTest {
    @Test
    fun theSearchDatagramIsAnMSearchForTheGatewayDevice() {
        val text = Upnp.search().decodeToString()
        assertTrue(text.startsWith("M-SEARCH * HTTP/1.1\r\n"), text)
        assertTrue(text.contains("HOST: ${Upnp.SSDP_ADDRESS}:${Upnp.SSDP_PORT}"), text)
        assertTrue(text.contains("""MAN: "ssdp:discover""""), "MAN must be quoted or devices ignore it")
        assertTrue(text.contains("ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1"), text)
        assertTrue(text.endsWith("\r\n\r\n"), "an SSDP request ends with a blank line")
    }

    /**
     * The header is case-insensitive and devices disagree about it.
     *
     * `LOCATION`, `Location` and `location` all turn up, and a client that matches one spelling
     * works against a third of the routers it meets — a failure that looks like "this router has no
     * UPnP".
     */
    @Test
    fun theLocationHeaderIsFoundWhateverItsCase() {
        listOf("LOCATION", "Location", "location").forEach { spelling ->
            val reply =
                "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=120\r\n" +
                    "$spelling: http://192.168.1.1:5000/rootDesc.xml\r\n\r\n"
            assertEquals("http://192.168.1.1:5000/rootDesc.xml", Upnp.location(reply), spelling)
        }
    }

    @Test
    fun somethingThatIsNotAnSsdpReplyHasNoLocation() {
        assertNull(Upnp.location("HTTP/1.1 200 OK\r\nSERVER: something\r\n\r\n"))
        assertNull(Upnp.location("LOCATION: not-a-url\r\n"), "a header that is not a URL is not a location")
    }

    /**
     * **The trap this fixture carries**: a description whose *first* control URL belongs to a
     * service that maps nothing.
     *
     * Taking the document's first `<controlURL>` is the obvious implementation and it picks
     * `/ctl/L3F` here — a layer-3 forwarding service that accepts the SOAP call and forwards no
     * port. The client then reports a mapping it does not have, which is worse than reporting none.
     */
    @Test
    fun theControlUrlIsTheMappingServicesRatherThanTheFirstInTheDocument() {
        val control = assertNotNull(Upnp.controlUrl(DESCRIPTION))
        assertEquals("/ctl/IPConn", control)
        assertEquals("urn:schemas-upnp-org:service:WANIPConnection:1", Upnp.service(DESCRIPTION))
    }

    @Test
    fun aDescriptionWithNoMappingServiceOffersNoControlUrl() {
        val useless =
            """
            <root><device><serviceList><service>
            <serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType>
            <controlURL>/ctl/L3F</controlURL>
            </service></serviceList></device></root>
            """.trimIndent()
        assertNull(Upnp.controlUrl(useless))
        assertNull(Upnp.service(useless))
    }

    /** Routers send all three forms of control URL and a client must take all three. */
    @Test
    fun aControlUrlIsMadeAbsoluteWhicheverFormItArrivedIn() {
        val description = "http://192.168.1.1:5000/rootDesc.xml"
        assertEquals("http://192.168.1.1:5000/ctl/IPConn", Upnp.absolute(description, "/ctl/IPConn"))
        assertEquals("http://192.168.1.1:5000/ctl/IPConn", Upnp.absolute(description, "ctl/IPConn"))
        assertEquals("http://10.0.0.1:80/other", Upnp.absolute(description, "http://10.0.0.1:80/other"))
    }

    @Test
    fun theSoapCallNamesTheActionTheServiceAndThePorts() {
        val service = "urn:schemas-upnp-org:service:WANIPConnection:1"
        val body =
            Upnp.addPortMapping(
                service,
                "192.168.1.105",
                internalPort = 6881,
                externalPort = 6881,
                description = "kachok",
            )

        assertTrue(body.contains("<u:AddPortMapping xmlns:u=\"$service\">"), body)
        assertTrue(body.contains("<NewInternalPort>6881</NewInternalPort>"), body)
        assertTrue(body.contains("<NewExternalPort>6881</NewExternalPort>"), body)
        assertTrue(body.contains("<NewInternalClient>192.168.1.105</NewInternalClient>"), body)
        assertTrue(body.contains("<NewProtocol>TCP</NewProtocol>"), "peers dial a stream")
        assertTrue(body.contains("<NewLeaseDuration>${Upnp.LEASE_SECONDS}</NewLeaseDuration>"), body)
        assertEquals("\"$service#AddPortMapping\"", Upnp.soapAction(service, "AddPortMapping"))
    }

    /** Giving a mapping back names the external port, because that is what the router keyed it by. */
    @Test
    fun deletingNamesTheExternalPort() {
        val body = Upnp.deletePortMapping("urn:schemas-upnp-org:service:WANIPConnection:1", externalPort = 49_152)
        assertTrue(body.contains("<u:DeletePortMapping"), body)
        assertTrue(body.contains("<NewExternalPort>49152</NewExternalPort>"), body)
    }

    /**
     * A refusal arrives as HTTP 500 with a numeric code, and the codes mean different things.
     *
     * 718 is somebody else already has that port — try another. 725 is the router only makes
     * permanent mappings — a different decision entirely, because accepting one means leaving a
     * hole that no lease will close.
     */
    @Test
    fun eachUpnpFaultCodeSaysSomethingDifferent() {
        assertNull(
            Upnp.fault("<s:Envelope><s:Body><u:AddPortMappingResponse/></s:Body></s:Envelope>"),
            "a success is not a fault",
        )

        val reasons = listOf("718", "725", "727", "401").map { code -> assertNotNull(Upnp.fault(fault(code)), code) }
        assertEquals(reasons.size, reasons.toSet().size, "two codes gave the same sentence: $reasons")
        assertTrue(assertNotNull(Upnp.fault(fault("718"))).contains("already mapped"))
        assertTrue(assertNotNull(Upnp.fault("<UPnPError>no code here</UPnPError>")).contains("did not say why"))
    }

    private fun fault(code: String): String =
        """
        <s:Envelope><s:Body><s:Fault><detail>
        <UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
        <errorCode>$code</errorCode>
        </UPnPError>
        </detail></s:Fault></s:Body></s:Envelope>
        """.trimIndent()

    private companion object {
        /** A real-shaped description: the mapping service is second, behind one that maps nothing. */
        val DESCRIPTION =
            """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
              <deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>
              <serviceList>
                <service>
                  <serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType>
                  <controlURL>/ctl/L3F</controlURL>
                </service>
              </serviceList>
              <deviceList><device><deviceList><device>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>
                    <controlURL>/ctl/IPConn</controlURL>
                  </service>
                </serviceList>
              </device></deviceList></device></deviceList>
            </device></root>
            """.trimIndent()
    }
}
