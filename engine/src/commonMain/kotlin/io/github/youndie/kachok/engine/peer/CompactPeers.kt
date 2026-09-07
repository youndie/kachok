package io.github.youndie.kachok.engine.peer

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

    /** BEP 7's IPv6 form: sixteen bytes of address and two of port. */
    public const val SIZE_V6: Int = 18

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
     * BEP 7's `peers6` / `added6`: the same idea at eighteen bytes.
     *
     * A separate function and not a size parameter, because the two are separate *fields* on the
     * wire — a tracker sends `peers` and `peers6`, never one string of both — and a reader that
     * took the size from somewhere else would decode one as the other and produce addresses made
     * of two peers' halves.
     */
    public fun decode6(
        bytes: ByteArray,
        from: Int = 0,
        until: Int = bytes.size,
    ): List<PeerAddress> {
        val peers = ArrayList<PeerAddress>((until - from) / SIZE_V6)
        var at = from
        while (at + SIZE_V6 <= until) {
            val port = ((bytes[at + 16].toInt() and 0xFF) shl 8) or (bytes[at + 17].toInt() and 0xFF)
            peers += PeerAddress(formatIpv6(bytes, at), port)
            at += SIZE_V6
        }
        return peers
    }

    /**
     * Sixteen bytes as text, with the longest run of zero groups collapsed (RFC 5952).
     *
     * Uncompressed would dial just as well; this is what ends up in a log line a person reads, and
     * `2001:db8::1` is a thing somebody can compare against what their client shows them while
     * `2001:0db8:0000:0000:0000:0000:0000:0001` is not.
     */
    private fun formatIpv6(
        bytes: ByteArray,
        at: Int,
    ): String {
        val groups =
            IntArray(8) { ((bytes[at + it * 2].toInt() and 0xFF) shl 8) or (bytes[at + it * 2 + 1].toInt() and 0xFF) }
        var bestStart = -1
        var bestLength = 0
        var start = -1
        var length = 0
        groups.forEachIndexed { index, group ->
            if (group == 0) {
                if (start < 0) start = index
                length++
                if (length > bestLength) {
                    bestLength = length
                    bestStart = start
                }
            } else {
                start = -1
                length = 0
            }
        }
        // A single zero group is written out: `::` for one group is legal and RFC 5952 forbids it.
        if (bestLength < 2) return groups.joinToString(":") { it.toString(16) }
        val head = (0 until bestStart).joinToString(":") { groups[it].toString(16) }
        val tail = ((bestStart + bestLength) until 8).joinToString(":") { groups[it].toString(16) }
        return "$head::$tail"
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
