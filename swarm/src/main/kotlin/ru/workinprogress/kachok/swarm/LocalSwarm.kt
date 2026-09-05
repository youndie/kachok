package ru.workinprogress.kachok.swarm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import ru.workinprogress.kachok.engine.bencode.BDictionary
import ru.workinprogress.kachok.engine.bencode.BInteger
import ru.workinprogress.kachok.engine.bencode.BString
import ru.workinprogress.kachok.engine.bencode.Bencode
import ru.workinprogress.kachok.engine.metainfo.Metainfo
import ru.workinprogress.kachok.engine.metainfo.MetainfoParser
import ru.workinprogress.kachok.engine.wire.PeerWire
import java.net.InetSocketAddress
import java.security.MessageDigest

/**
 * A torrent, a tracker that names one peer, and that peer with the bytes.
 *
 * Everything below the client is real: bencode built with the engine's own encoder — never by
 * hand, three fixtures have had wrong length prefixes that way — an HTTP tracker on loopback, and
 * a seed speaking BEP 3 over a socket. A test that passes against this has proved the codec, the
 * transport, the picker, the hasher, the writer, the tracker client and the session agree with each
 * other *and* with the protocol.
 */
public class LocalSwarm private constructor(
    public val content: ByteArray,
    public val torrent: ByteArray,
    public val metainfo: Metainfo,
    private val tracker: HttpServer,
    private val seed: SeedingPeer?,
) : AutoCloseable {
    public val trackerUrl: String get() = "http://127.0.0.1:${tracker.address.port}/annc"

    /** What the seed was asked for, so a test can tell "it downloaded" from "it had it already". */
    public val served: Int get() = seed?.served?.size ?: 0

    override fun close() {
        tracker.stop(0)
        seed?.close()
    }

    public companion object {
        /** 40 000 bytes at a 16 KiB piece length: three pieces, the last one short. */
        public fun content(size: Int = DEFAULT_SIZE): ByteArray = ByteArray(size) { (it * SPREAD and BYTE).toByte() }

        /**
         * Starts the tracker and, unless [failure] is given, the seed behind it.
         *
         * [delayPerBlockMillis] slows the seed down so a test can watch a download that is
         * genuinely in progress rather than one that finished before the first sample.
         */
        public fun start(
            content: ByteArray = content(),
            failure: String? = null,
            delayPerBlockMillis: Long = 0,
        ): LocalSwarm {
            val pieceLength = PeerWire.BLOCK_SIZE
            val torrentBytes = torrentBytes(content, pieceLength, placeholderTracker())
            val metainfo = MetainfoParser.parse(torrentBytes)
            val seed =
                if (failure != null) {
                    null
                } else {
                    SeedingPeer(metainfo.infoHash, content, pieceLength, delayPerBlockMillis)
                }
            val tracker = startTracker(seed?.port, failure)
            val url = "http://127.0.0.1:${tracker.address.port}/annc"
            // Built twice on purpose: the info hash a peer is asked for has to be the one in the
            // torrent the client reads, and the announce URL is only known after the tracker binds.
            val finalTorrent = torrentBytes(content, pieceLength, url)
            return LocalSwarm(content, finalTorrent, MetainfoParser.parse(finalTorrent), tracker, seed)
        }

        /** The announce URL is not known until the tracker binds; the info hash must not depend on it. */
        private fun placeholderTracker(): String = "http://127.0.0.1:1/annc"

        private fun torrentBytes(
            content: ByteArray,
            pieceLength: Int,
            trackerUrl: String,
        ): ByteArray {
            val digest = MessageDigest.getInstance("SHA-1")
            val pieces = (content.size + pieceLength - 1) / pieceLength
            val hashes = ByteArray(pieces * Metainfo.HASH_SIZE)
            (0 until pieces).forEach { index ->
                val from = index * pieceLength
                val to = minOf(from + pieceLength, content.size)
                digest.reset()
                digest.update(content, from, to - from)
                digest.digest().copyInto(hashes, index * Metainfo.HASH_SIZE)
            }
            val info =
                BDictionary(
                    mapOf(
                        BString("length") to BInteger(content.size.toLong()),
                        BString("name") to BString("payload.bin"),
                        BString("piece length") to BInteger(pieceLength.toLong()),
                        BString("pieces") to BString(hashes),
                    ),
                )
            return Bencode.encode(
                BDictionary(mapOf(BString("announce") to BString(trackerUrl), BString("info") to info)),
            )
        }

        /** A tracker that answers with one compact peer: the seed. Or with a refusal. */
        private fun startTracker(
            peerPort: Int?,
            failure: String?,
        ): HttpServer {
            val started = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            started.createContext("/annc") { exchange: HttpExchange ->
                val body =
                    if (failure != null) {
                        Bencode.encode(BDictionary(mapOf(BString("failure reason") to BString(failure))))
                    } else {
                        val packed =
                            byteArrayOf(
                                LOOPBACK_A,
                                0,
                                0,
                                LOOPBACK_D,
                                ((peerPort!! shr BYTE_BITS) and BYTE).toByte(),
                                (peerPort and BYTE).toByte(),
                            )
                        Bencode.encode(
                            BDictionary(
                                mapOf(
                                    BString("interval") to BInteger(INTERVAL),
                                    BString("peers") to BString(packed),
                                ),
                            ),
                        )
                    }
                exchange.sendResponseHeaders(HTTP_OK, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            started.start()
            return started
        }

        private const val DEFAULT_SIZE = 40_000
        private const val SPREAD = 7
        private const val BYTE = 0xFF
        private const val BYTE_BITS = 8
        private const val LOOPBACK_A: Byte = 127
        private const val LOOPBACK_D: Byte = 1
        private const val INTERVAL = 1800L
        private const val HTTP_OK = 200
    }
}
