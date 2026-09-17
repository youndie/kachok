package io.github.youndie.kachok.engine.nat

/**
 * UPnP IGD, the parts of it that are text.
 *
 * Four steps, of which three are parsing: an SSDP datagram finds a device, its `LOCATION` header
 * names a description, the description names a control URL, and a SOAP call to that URL maps the
 * port. Everything here is the text; the datagram and the two HTTP requests are the platform's.
 *
 * **It is the fallback and not the first attempt** ([NatPmp] is), and it earns the position by
 * being four times the work for the same result.
 *
 * **The description is scanned rather than parsed as XML, and that is a trade with a name.** A real
 * parser means `java.xml` in the run-time image, which is measured in megabytes against a 32 MB
 * image (research §1.3b) for one document read once per start. The scan below is narrow — it looks
 * for a service block and takes the control URL out of it — and it is written to fail by finding
 * nothing rather than by finding something wrong. If a router turns up whose description defeats
 * it, the answer is the module and not a cleverer expression.
 */
internal object Upnp {
    const val SSDP_ADDRESS: String = "239.255.255.250"
    const val SSDP_PORT: Int = 1900

    /** The two services that can map a port; a router offers one or the other, not both. */
    val SERVICES: List<String> =
        listOf(
            "urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANPPPConnection:1",
        )

    /** How long a mapping is asked for, and why it matches NAT-PMP's: see [NatPmp.LIFETIME_SECONDS]. */
    const val LEASE_SECONDS: Int = NatPmp.LIFETIME_SECONDS

    /**
     * The search datagram.
     *
     * `MX` is how long a device may wait before answering, and it is a ceiling rather than a delay:
     * devices scatter their replies across it so that a segment full of them does not answer at
     * once. Two seconds is what every client uses.
     */
    fun search(seconds: Int = 2): ByteArray =
        (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: $seconds\r\n" +
                "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n"
        ).encodeToByteArray()

    /**
     * The `LOCATION` header of an SSDP reply, or null if this is not one.
     *
     * Case-insensitive because the header is, and devices disagree about it — `LOCATION`,
     * `Location` and `location` all turn up in the wild, and a client that matches one of the three
     * works against a third of the routers it meets.
     */
    fun location(reply: String): String? =
        reply
            .lineSequence()
            .firstOrNull { it.trimStart().startsWith("location:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.startsWith("http", ignoreCase = true) }

    /**
     * The control URL for whichever mapping service the description offers.
     *
     * Returned as it appears, which may be absolute or a path; making it absolute needs the
     * description's own URL and is [absolute]'s job.
     */
    fun controlUrl(description: String): String? {
        SERVICES.forEach { service ->
            val at = description.indexOf(service, ignoreCase = true)
            if (at < 0) return@forEach
            // The control URL of *this* service, which is the next one after the service type
            // inside the same block. Taking the document's first `<controlURL>` instead picks
            // whichever service the router happened to list first — often a layer-3 forwarding
            // service that maps nothing.
            val open = description.indexOf("<controlURL>", at, ignoreCase = true)
            if (open < 0) return@forEach
            val close = description.indexOf("</controlURL>", open, ignoreCase = true)
            if (close < 0) return@forEach
            return description.substring(open + "<controlURL>".length, close).trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    /** Which of [SERVICES] a description offers, needed for the SOAP action's namespace. */
    fun service(description: String): String? = SERVICES.firstOrNull { description.contains(it, ignoreCase = true) }

    /**
     * A control URL made absolute against the description's own address.
     *
     * Routers send all three forms — a full URL, an absolute path, and occasionally a relative one
     * — and a client that assumes any single one of them silently fails against the rest.
     */
    fun absolute(
        descriptionUrl: String,
        controlUrl: String,
    ): String {
        if (controlUrl.startsWith("http", ignoreCase = true)) return controlUrl
        val schemeEnd = descriptionUrl.indexOf("://")
        if (schemeEnd < 0) return controlUrl
        val afterScheme = descriptionUrl.indexOf('/', schemeEnd + 3)
        val root = if (afterScheme < 0) descriptionUrl else descriptionUrl.substring(0, afterScheme)
        return if (controlUrl.startsWith('/')) root + controlUrl else "$root/$controlUrl"
    }

    /** The SOAP envelope for `AddPortMapping`. */
    fun addPortMapping(
        service: String,
        internalHost: String,
        internalPort: Int,
        externalPort: Int,
        description: String,
        leaseSeconds: Int = LEASE_SECONDS,
    ): String =
        envelope(
            service,
            "AddPortMapping",
            """
            <NewRemoteHost></NewRemoteHost>
            <NewExternalPort>$externalPort</NewExternalPort>
            <NewProtocol>TCP</NewProtocol>
            <NewInternalPort>$internalPort</NewInternalPort>
            <NewInternalClient>$internalHost</NewInternalClient>
            <NewEnabled>1</NewEnabled>
            <NewPortMappingDescription>$description</NewPortMappingDescription>
            <NewLeaseDuration>$leaseSeconds</NewLeaseDuration>
            """.trimIndent(),
        )

    /** The SOAP envelope for `DeletePortMapping`, which is how a mapping is given back. */
    fun deletePortMapping(
        service: String,
        externalPort: Int,
    ): String =
        envelope(
            service,
            "DeletePortMapping",
            """
            <NewRemoteHost></NewRemoteHost>
            <NewExternalPort>$externalPort</NewExternalPort>
            <NewProtocol>TCP</NewProtocol>
            """.trimIndent(),
        )

    /** The `SOAPAction` header, which routers check and which is not optional. */
    fun soapAction(
        service: String,
        action: String,
    ): String = "\"$service#$action\""

    /**
     * Whether a SOAP reply refused, and what it said.
     *
     * UPnP answers a refusal with HTTP 500 and a fault body carrying a numeric code; the codes that
     * matter are 718, the port is already mapped to somebody else, and 725, the router only does
     * permanent leases. Null means it did not refuse.
     */
    fun fault(body: String): String? {
        if (!body.contains("UPnPError", ignoreCase = true)) return null
        val code =
            Regex(
                "<errorCode>\\s*(\\d+)\\s*</errorCode>",
                RegexOption.IGNORE_CASE,
            ).find(body)?.groupValues?.get(1)
        return when (code) {
            null -> "the router refused and did not say why"
            "718" -> "that port is already mapped to another machine"
            "725" -> "the router only makes permanent mappings"
            "727" -> "the router will not map to a different external port"
            else -> "the router refused with UPnP error $code"
        }
    }

    private fun envelope(
        service: String,
        action: String,
        arguments: String,
    ): String =
        """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:$action xmlns:u="$service">
$arguments
</u:$action>
</s:Body>
</s:Envelope>"""
}
