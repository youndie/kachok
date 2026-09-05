package ru.workinprogress.kachok.engine.peer

/**
 * BEP 23's packed peer list: four bytes of IPv4 and two of port, repeated.
 *
 * One object because three different things speak it — the HTTP tracker's `peers` string
 * (BEP 23), the UDP tracker's announce reply (BEP 15) and peer exchange's `added` and `dropped`
 * (BEP 11) — and each of them had its own copy of "six" and its own loop over the same bytes.
 */
public object CompactPeers {
    /** Four bytes of address and two of port. */
    public const val SIZE: Int = 6

    /** Reads `[from, until)`, ignoring a trailing fragment: an address without a port is not one. */
    public fun decode(
        bytes: ByteArray,
        from: Int = 0,
        until: Int = bytes.size,
    ): List<PeerAddress> {
        val peers = ArrayList<PeerAddress>((until - from) / SIZE)
        var at = from
        while (at + SIZE <= until) {
            val host = (0 until 4).joinToString(".") { (bytes[at + it].toInt() and 0xFF).toString() }
            val port = ((bytes[at + 4].toInt() and 0xFF) shl 8) or (bytes[at + 5].toInt() and 0xFF)
            peers += PeerAddress(host, port)
            at += SIZE
        }
        return peers
    }

    /**
     * Packs the peers whose host is a dotted IPv4 literal, and silently leaves out the rest.
     *
     * A tracker may answer with names, and this format has no room for one. Sending four bytes of
     * something that was not an address would point every recipient at a peer that does not exist,
     * which is worse than telling them about one peer fewer. IPv6 has its own keys and its own
     * item ([B-38](../backlog/B-38-ipv6.md)).
     */
    public fun encode(peers: List<PeerAddress>): ByteArray {
        val packed = peers.mapNotNull { peer -> octets(peer.host)?.let { it to peer.port } }
        val bytes = ByteArray(packed.size * SIZE)
        packed.forEachIndexed { index, (octets, port) ->
            val at = index * SIZE
            octets.copyInto(bytes, at)
            bytes[at + 4] = (port ushr 8).toByte()
            bytes[at + 5] = port.toByte()
        }
        return bytes
    }

    /** Whether this host can be packed at all — which is what "IPv4 literal" means here. */
    public fun isPackable(host: String): Boolean = octets(host) != null

    private fun octets(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        parts.forEachIndexed { index, part ->
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            bytes[index] = value.toByte()
        }
        return bytes
    }
}
