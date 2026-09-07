package io.github.youndie.kachok.swarm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.youndie.kachok.engine.bencode.BDictionary
import io.github.youndie.kachok.engine.bencode.BInteger
import io.github.youndie.kachok.engine.bencode.BList
import io.github.youndie.kachok.engine.bencode.BString
import io.github.youndie.kachok.engine.bencode.Bencode
import io.github.youndie.kachok.engine.metainfo.Metainfo
import io.github.youndie.kachok.engine.metainfo.MetainfoParser
import io.github.youndie.kachok.engine.wire.PeerWire
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
            /**
             * Whether the seed will serve the `info` dictionary over BEP 9.
             *
             * A magnet carries none of the torrent, so the only way to test that path end to end
             * is a peer on a real socket that answers `ut_metadata` — off by default, because a
             * seed that answers it is a different seed from the one BEP 3's tests want.
             */
            serveMetadata: Boolean = false,
            /**
             * One block a piece by default, which is the smallest thing that exercises the wire.
             *
             * A test that wants the *buffer pool* exercised has to ask for more: the pool's working
             * set is `maxStartedPieces × blocksPerPiece`, and with one block a piece that is eight
             * buffers however large the torrent is.
             */
            pieceLength: Int = PeerWire.BLOCK_SIZE,
            /**
             * Names and lengths, when the torrent is to be a multi-file one.
             *
             * The seed does not care: it serves pieces of one byte stream, and BEP 3's multi-file
             * case is that same stream cut up by the *metainfo*. So this changes the torrent and
             * nothing else — which is what makes it the right way to test a client that skips a
             * file, because the swarm behaves identically whether the client wants that file or
             * not.
             *
             * Must add up to `content.size`.
             */
            files: List<Pair<String, Int>>? = null,
        ): LocalSwarm {
            require(files == null || files.sumOf { it.second } == content.size) {
                "the files must add up to the content: ${files?.sumOf { it.second }} of ${content.size}"
            }
            val torrentBytes = torrentBytes(content, pieceLength, placeholderTracker(), files)
            val metainfo = MetainfoParser.parse(torrentBytes)
            val seed =
                if (failure != null) {
                    null
                } else {
                    SeedingPeer(
                        infoHash = metainfo.infoHash,
                        content = content,
                        pieceLength = pieceLength,
                        delayPerBlockMillis = delayPerBlockMillis,
                        extensionProtocol = serveMetadata,
                        metadata = if (serveMetadata) metainfo.infoBytes else null,
                    )
                }
            val tracker = startTracker(seed?.port, failure)
            val url = "http://127.0.0.1:${tracker.address.port}/annc"
            // Built twice on purpose: the info hash a peer is asked for has to be the one in the
            // torrent the client reads, and the announce URL is only known after the tracker binds.
            val finalTorrent = torrentBytes(content, pieceLength, url, files)
            // The announce URL is outside the `info` dictionary, so the info hash and the bytes the
            // seed serves are the same in both — which is the whole reason the hash is taken over
            // that dictionary and not over the file.
            return LocalSwarm(content, finalTorrent, MetainfoParser.parse(finalTorrent), tracker, seed)
        }

        /** The announce URL is not known until the tracker binds; the info hash must not depend on it. */
        private fun placeholderTracker(): String = "http://127.0.0.1:1/annc"

        private fun torrentBytes(
            content: ByteArray,
            pieceLength: Int,
            trackerUrl: String,
            files: List<Pair<String, Int>>? = null,
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
            // BEP 3: single-file torrents carry `length` and a `name` that *is* the file;
            // multi-file ones carry `files` and a `name` that is the directory they sit in.
            val shape =
                if (files == null) {
                    mapOf(
                        BString("length") to BInteger(content.size.toLong()),
                        BString("name") to BString("payload.bin"),
                    )
                } else {
                    mapOf(
                        BString("files") to
                            BList(
                                files.map { (name, length) ->
                                    BDictionary(
                                        mapOf(
                                            BString("length") to BInteger(length.toLong()),
                                            BString("path") to BList(listOf(BString(name))),
                                        ),
                                    )
                                },
                            ),
                        BString("name") to BString("bundle"),
                    )
                }
            val info =
                BDictionary(
                    shape +
                        mapOf(
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
