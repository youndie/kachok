package io.github.youndie.kachok.engine.nat

import io.github.youndie.kachok.engine.InfoHash

/**
 * Local service discovery (BEP 14): the one peer source that never leaves the segment.
 *
 * A datagram to a multicast group saying "I have this torrent, on this port". It is the cheapest
 * source there is — one packet every few minutes against a conversation with strangers — and the
 * only one with nothing to argue about on privacy, which is why it can be on when
 * [the DHT](../session/SessionConfig) is a decision.
 *
 * ```
 * BT-SEARCH * HTTP/1.1
 * Host: 239.192.152.143:6771
 * Port: 6881
 * Infohash: <40 hex characters>
 * cookie: <opaque, this client's own>
 * ```
 *
 * **The cookie is the only thing stopping a client from finding itself.** A multicast announce
 * comes back on the same socket that sent it; without a value to recognise, this client reads its
 * own packet, dials its own listening port and connects to itself — which works, looks like a peer,
 * and transfers nothing.
 */
internal object Lsd {
    const val GROUP_V4: String = "239.192.152.143"
    const val GROUP_V6: String = "ff15::efc0:988f"
    const val PORT: Int = 6771

    /**
     * BEP 14's floor: no more often than this, per torrent.
     *
     * Five minutes because that is what the specification says and because the thing being
     * announced does not change — a segment where nobody is listening hears nothing useful from a
     * faster announce, and a segment where somebody is heard the first one.
     */
    const val INTERVAL_SECONDS: Int = 300

    /** What this client announces. [cookie] is its own and is what [parse] uses to ignore it. */
    fun announce(
        infoHash: InfoHash,
        port: Int,
        cookie: String,
        group: String = GROUP_V4,
    ): ByteArray =
        (
            "BT-SEARCH * HTTP/1.1\r\n" +
                "Host: $group:$PORT\r\n" +
                "Port: $port\r\n" +
                "Infohash: ${infoHash.bytes.toHex()}\r\n" +
                "cookie: $cookie\r\n" +
                "\r\n\r\n"
        ).encodeToByteArray()

    /**
     * What somebody else announced, or null.
     *
     * Null covers three things that are all non-events rather than failures: a datagram that is not
     * a `BT-SEARCH` at all, one this client sent itself, and one naming a torrent in a form this
     * cannot read. A multicast group carries whatever anybody puts on it.
     */
    fun parse(
        datagram: String,
        ownCookie: String,
    ): Announcement? {
        if (!datagram.startsWith("BT-SEARCH", ignoreCase = true)) return null
        val headers = headersOf(datagram)

        // Our own announce, arriving back on the socket that sent it. Reading it would have this
        // client dial its own listening port.
        if (headers["cookie"] == ownCookie) return null

        val port = headers["port"]?.toIntOrNull()?.takeIf { it in 1..MAX_PORT } ?: return null
        val hash = headers["infohash"]?.trim()?.takeIf { it.length == HEX_LENGTH } ?: return null
        val bytes = hash.hexToBytesOrNull() ?: return null
        return Announcement(InfoHash(bytes), port)
    }

    /**
     * The headers, lower-cased by name and left alone by value.
     *
     * Names are case-insensitive and clients disagree about which case they send; values are not,
     * and a cookie compared case-insensitively would have two clients that chose the same letters
     * in different cases each ignoring the other as itself.
     */
    private fun headersOf(datagram: String): Map<String, String> =
        datagram
            .lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) return@mapNotNull null
                line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
            }.toMap()

    private const val HEX_LENGTH = 40
    private const val MAX_PORT = 65_535

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun String.hexToBytesOrNull(): ByteArray? {
        val out = ByteArray(length / 2)
        for (index in out.indices) {
            val high = this[index * 2].digitToIntOrNull(16) ?: return null
            val low = this[index * 2 + 1].digitToIntOrNull(16) ?: return null
            out[index] = ((high shl 4) or low).toByte()
        }
        return out
    }
}

/** Somebody on this segment has a torrent, and says where. */
internal class Announcement(
    val infoHash: InfoHash,
    val port: Int,
)
