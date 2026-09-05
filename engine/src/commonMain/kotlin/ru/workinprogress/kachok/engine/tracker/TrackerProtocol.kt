package ru.workinprogress.kachok.engine.tracker

import ru.workinprogress.kachok.engine.bencode.BDictionary
import ru.workinprogress.kachok.engine.bencode.BInteger
import ru.workinprogress.kachok.engine.bencode.BList
import ru.workinprogress.kachok.engine.bencode.BString
import ru.workinprogress.kachok.engine.bencode.Bencode
import ru.workinprogress.kachok.engine.bencode.BencodeException
import ru.workinprogress.kachok.engine.peer.CompactPeers
import ru.workinprogress.kachok.engine.peer.PeerAddress

/**
 * The tracker HTTP protocol without the HTTP: the query string a request becomes and the response
 * a body parses into (BEP 3, BEP 23).
 *
 * Kept out of the transport so that both are testable without a socket, and so that the second
 * platform to need an announce inherits the fiddly half rather than rewriting it.
 */
public object TrackerProtocol {
    /** BEP 3: try 6881, then 6882 … and give up after 6889. */
    public val PORT_RANGE: IntRange = 6881..6889

    /**
     * The full URL to GET.
     *
     * The info hash and the peer id are twenty raw bytes, not text: every byte outside the
     * unreserved set is percent-encoded one byte at a time. Running them through a UTF-8 encoder
     * first — which is what a string-based URL builder does — corrupts roughly half of them.
     */
    public fun announceUrl(
        tracker: String,
        request: AnnounceRequest,
    ): String {
        val query =
            buildString {
                append("info_hash=").append(percentEncode(request.infoHash.bytes))
                append("&peer_id=").append(percentEncode(request.peerId.bytes))
                append("&port=").append(request.port)
                append("&uploaded=").append(request.uploaded)
                append("&downloaded=").append(request.downloaded)
                append("&left=").append(request.left)
                append("&compact=1")
                request.event?.let { append("&event=").append(it.wireName) }
                request.numWant?.let { append("&numwant=").append(it) }
            }
        val separator = if (tracker.contains('?')) "&" else "?"
        return tracker + separator + query
    }

    /** Parses a tracker's bencoded body, or throws [TrackerException] with the tracker's own words. */
    public fun parseResponse(body: ByteArray): AnnounceResponse {
        val root =
            try {
                Bencode.decode(body) as? BDictionary
                    ?: throw TrackerException("the tracker's answer is not a dictionary")
            } catch (malformed: BencodeException) {
                throw TrackerException("the tracker's answer is not bencode: ${malformed.message}")
            }

        (root["failure reason"] as? BString)?.let { throw TrackerException(it.asString()) }

        val interval =
            (root["interval"] as? BInteger)?.value?.toInt()
                ?: throw TrackerException("the tracker's answer has no `interval`")

        return AnnounceResponse(
            interval = interval,
            peers = parsePeers(root["peers"]),
            minInterval = (root["min interval"] as? BInteger)?.value?.toInt(),
            seeders = (root["complete"] as? BInteger)?.value?.toInt(),
            leechers = (root["incomplete"] as? BInteger)?.value?.toInt(),
        )
    }

    /**
     * Both shapes a tracker may answer with: BEP 23's packed string of six bytes per peer, and
     * BEP 3's original list of dictionaries. `compact=1` asks for the first and does not guarantee
     * it, and a client that reads only one of them finds no peers on the trackers that prefer the
     * other.
     */
    private fun parsePeers(value: Any?): List<PeerAddress> =
        when (value) {
            is BString -> {
                // Refused rather than truncated: a `peers` string that is not a whole number of
                // peers means the tracker and this client disagree about the format, and reading
                // the part that fits would be reading somebody else's idea of an address.
                if (value.bytes.size % CompactPeers.SIZE != 0) {
                    throw TrackerException(
                        "compact `peers` is ${value.bytes.size} bytes, not a multiple of ${CompactPeers.SIZE}",
                    )
                }
                CompactPeers.decode(value.bytes)
            }

            is BList -> {
                value.items.mapNotNull { entry ->
                    val peer = entry as? BDictionary ?: return@mapNotNull null
                    val host = (peer["ip"] as? BString)?.asString() ?: return@mapNotNull null
                    val port = (peer["port"] as? BInteger)?.value?.toInt() ?: return@mapNotNull null
                    PeerAddress(host, port)
                }
            }

            else -> {
                emptyList()
            }
        }

    private fun percentEncode(bytes: ByteArray): String =
        buildString(bytes.size * 3) {
            bytes.forEach { byte ->
                val code = byte.toInt() and 0xFF
                val character = code.toChar()
                if (character in UNRESERVED) {
                    append(character)
                } else {
                    append('%').append(HEX[code shr 4]).append(HEX[code and 0x0F])
                }
            }
        }

    private const val HEX = "0123456789ABCDEF"
    private val UNRESERVED =
        ('a'..'z').toSet() + ('A'..'Z').toSet() + ('0'..'9').toSet() + setOf('-', '_', '.', '~')
}
