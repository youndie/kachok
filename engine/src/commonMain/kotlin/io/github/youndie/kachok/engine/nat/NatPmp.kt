package io.github.youndie.kachok.engine.nat

/**
 * NAT-PMP (RFC 6886): twelve bytes out, sixteen back, and a router that either forwards a port or
 * says why not.
 *
 * **Chosen over UPnP as the first thing to try, and the reason is size.** UPnP IGD is an SSDP
 * datagram, an HTTP fetch of a device description, an XML parse and a SOAP call; NAT-PMP is this
 * file. Most routers made this decade answer it, and the ones that do not get the UPnP fallback —
 * which is a bigger piece of work and is why it is the fallback rather than the first attempt.
 *
 * Deliberately not PCP (RFC 6887), which supersedes this: PCP's MAP opcode carries a nonce, an
 * address family and options, and its first version byte is what a NAT-PMP-only router rejects with
 * `UNSUPPORTED_VERSION` — a rejection this client would then have to fall back from anyway. The
 * pair to have is NAT-PMP and UPnP; PCP earns its place when a router turns up that speaks it and
 * not the other two.
 *
 * ```
 * request    version(1) opcode(1) reserved(2) internal(2) external(2) lifetime(4)
 * response   version(1) opcode(1) result(2)   epoch(4)    internal(2) external(2) lifetime(4)
 * ```
 */
internal object NatPmp {
    const val VERSION: Int = 0

    /** The port the router listens on. Not this client's choice and not configurable. */
    const val PORT: Int = 5351

    const val OPCODE_MAP_UDP: Int = 1
    const val OPCODE_MAP_TCP: Int = 2

    /** A response's opcode is the request's with the top bit set. */
    const val RESPONSE_FLAG: Int = 128

    const val REQUEST_SIZE: Int = 12
    const val RESPONSE_SIZE: Int = 16

    /**
     * How long a mapping is asked for.
     *
     * Two hours, renewed at half of it, which is RFC 6886's own advice. Long enough that a renewal
     * failure is not immediately fatal, short enough that a client which dies without releasing
     * leaves the hole open for an afternoon rather than for ever — and leaving holes open is the
     * part of this that is somebody else's router.
     */
    const val LIFETIME_SECONDS: Int = 7200

    /**
     * Asking for lifetime zero is how a mapping is given back.
     *
     * The external port must be 0 and the lifetime 0; the router then drops every mapping for this
     * internal port. A client that maps and never does this leaves a hole in a router it does not
     * own, which is worse than never having mapped.
     */
    const val RELEASE_LIFETIME: Int = 0

    fun mapRequest(
        internalPort: Int,
        suggestedExternalPort: Int = internalPort,
        lifetimeSeconds: Int = LIFETIME_SECONDS,
        tcp: Boolean = true,
    ): ByteArray {
        val packet = ByteArray(REQUEST_SIZE)
        packet[0] = VERSION.toByte()
        packet[1] = (if (tcp) OPCODE_MAP_TCP else OPCODE_MAP_UDP).toByte()
        // Bytes 2 and 3 are reserved and must be zero, which they already are.
        writeShort(packet, 4, internalPort)
        // Zero when releasing: RFC 6886 says the external port is ignored then, and sending the
        // real one is how an implementation accidentally asks for a mapping while meaning to drop
        // one.
        writeShort(packet, 6, if (lifetimeSeconds == RELEASE_LIFETIME) 0 else suggestedExternalPort)
        writeInt(packet, 8, lifetimeSeconds)
        return packet
    }

    /**
     * Reads a router's answer, or `null` when the datagram is not one.
     *
     * Null rather than an exception for the same reason the DHT's transport returns null: a UDP
     * socket hands over whatever reaches the port, and a stray datagram is a non-event rather than
     * a failure. What *is* a failure — a router that answers with a result code — comes back as a
     * [Mapping] carrying it, because "the router refused and said why" is information a person can
     * act on and silence is not.
     */
    fun parseResponse(
        datagram: ByteArray,
        length: Int = datagram.size,
    ): Mapping? {
        if (length < RESPONSE_SIZE) return null
        if (datagram[0].toInt() != VERSION) return null
        val opcode = datagram[1].toInt() and 0xFF
        if (opcode != OPCODE_MAP_TCP + RESPONSE_FLAG && opcode != OPCODE_MAP_UDP + RESPONSE_FLAG) return null
        return Mapping(
            result = readShort(datagram, 2),
            epochSeconds = readInt(datagram, 4),
            internalPort = readShort(datagram, 8),
            externalPort = readShort(datagram, 10),
            lifetimeSeconds = readInt(datagram, 12),
            tcp = opcode == OPCODE_MAP_TCP + RESPONSE_FLAG,
        )
    }

    private fun writeShort(
        into: ByteArray,
        at: Int,
        value: Int,
    ) {
        into[at] = (value ushr 8).toByte()
        into[at + 1] = value.toByte()
    }

    private fun writeInt(
        into: ByteArray,
        at: Int,
        value: Int,
    ) {
        into[at] = (value ushr 24).toByte()
        into[at + 1] = (value ushr 16).toByte()
        into[at + 2] = (value ushr 8).toByte()
        into[at + 3] = value.toByte()
    }

    private fun readShort(
        bytes: ByteArray,
        at: Int,
    ): Int = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    private fun readInt(
        bytes: ByteArray,
        at: Int,
    ): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
}

/** What a router said about one mapping. */
internal class Mapping(
    val result: Int,
    val epochSeconds: Int,
    val internalPort: Int,
    val externalPort: Int,
    val lifetimeSeconds: Int,
    val tcp: Boolean,
) {
    val succeeded: Boolean get() = result == RESULT_SUCCESS

    /**
     * The refusal in words, or null when there was none.
     *
     * Written out rather than left as a number because this is what reaches a person: "the router
     * refused" is not something anybody can act on, and "the router does not do this" and "the
     * router is out of addresses" call for different things.
     */
    val refusal: String?
        get() =
            when (result) {
                RESULT_SUCCESS -> null
                RESULT_UNSUPPORTED_VERSION -> "the router does not speak NAT-PMP"
                RESULT_NOT_AUTHORISED -> "the router has port mapping switched off"
                RESULT_NETWORK_FAILURE -> "the router has no upstream address yet"
                RESULT_OUT_OF_RESOURCES -> "the router has no mappings left to give"
                RESULT_UNSUPPORTED_OPCODE -> "the router does not map this protocol"
                else -> "the router refused with code $result"
            }

    internal companion object {
        const val RESULT_SUCCESS = 0
        const val RESULT_UNSUPPORTED_VERSION = 1
        const val RESULT_NOT_AUTHORISED = 2
        const val RESULT_NETWORK_FAILURE = 3
        const val RESULT_OUT_OF_RESOURCES = 4
        const val RESULT_UNSUPPORTED_OPCODE = 5
    }
}
