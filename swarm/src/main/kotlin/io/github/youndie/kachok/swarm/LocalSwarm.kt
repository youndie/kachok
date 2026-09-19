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
    /** Every seed the tracker names, in the order they were asked for. Usually one. */
    public val peers: List<SeedingPeer>,
) : AutoCloseable {
    public val trackerUrl: String get() = "http://127.0.0.1:${tracker.address.port}/annc"

    /** What the seeds were asked for, so a test can tell "it downloaded" from "it had it already". */
    public val served: Int get() = peers.sumOf { it.served.size }

    override fun close() {
        tracker.stop(0)
        peers.forEach { it.close() }
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
            /**
             * One entry per seed, naming the pieces that seed holds; null for one seed with the
             * torrent.
             *
             * **This is the only way to make a piece rare**, and rarity is what every question
             * about the picker turns on: on a stand where every seed has everything, rarest-first
             * and in-order ask for the same pieces in a different order and a figure taken from it
             * measures nothing ([B-65](../../../../../../../docs/backlog/B-65-sequential-download.md)).
             * The subsets are not checked for covering the torrent: a swarm that between them
             * cannot serve every piece is a real state, and a test about a download that cannot
             * finish needs it.
             */
            seeds: List<Set<Int>>? = null,
            /**
             * Bytes a second per seed, or zero for as fast as loopback goes.
             *
             * A timing taken at loopback speed is a timing of the kernel and the disk; a rate is
             * what makes it a timing of the client. It is a floor and never a ceiling — see
             * [SeedingPeer.bytesPerSecond].
             */
            bytesPerSecond: Long = 0,
        ): LocalSwarm {
            require(files == null || files.sumOf { it.second } == content.size) {
                "the files must add up to the content: ${files?.sumOf { it.second }} of ${content.size}"
            }
            val torrentBytes = torrentBytes(content, pieceLength, placeholderTracker(), files)
            val metainfo = MetainfoParser.parse(torrentBytes)
            val holdings: List<Set<Int>?> = seeds ?: listOf(null)
            val started =
                if (failure != null) {
                    emptyList()
                } else {
                    holdings.map { held ->
                        SeedingPeer(
                            infoHash = metainfo.infoHash,
                            content = content,
                            pieceLength = pieceLength,
                            delayPerBlockMillis = delayPerBlockMillis,
                            holds = held,
                            bytesPerSecond = bytesPerSecond,
                            extensionProtocol = serveMetadata,
                            metadata = if (serveMetadata) metainfo.infoBytes else null,
                        )
                    }
                }
            val tracker = startTracker(started.map { it.port }, failure)
            val url = "http://127.0.0.1:${tracker.address.port}/annc"
            // Built twice on purpose: the info hash a peer is asked for has to be the one in the
            // torrent the client reads, and the announce URL is only known after the tracker binds.
            val finalTorrent = torrentBytes(content, pieceLength, url, files)
            // The announce URL is outside the `info` dictionary, so the info hash and the bytes the
            // seed serves are the same in both — which is the whole reason the hash is taken over
            // that dictionary and not over the file.
            return LocalSwarm(content, finalTorrent, MetainfoParser.parse(finalTorrent), tracker, started)
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

        /** A tracker that answers with a compact peer list: the seeds. Or with a refusal. */
        private fun startTracker(
            peerPorts: List<Int>,
            failure: String?,
        ): HttpServer {
            val started = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            started.createContext("/annc") { exchange: HttpExchange ->
                val body =
                    if (failure != null) {
                        Bencode.encode(BDictionary(mapOf(BString("failure reason") to BString(failure))))
                    } else {
                        // Six bytes a peer, end to end: BEP 23's compact list, which is what
                        // every tracker worth the name answers with.
                        val packed =
                            ByteArray(peerPorts.size * COMPACT_PEER_SIZE).also { bytes ->
                                peerPorts.forEachIndexed { index, port ->
                                    val at = index * COMPACT_PEER_SIZE
                                    bytes[at] = LOOPBACK_A
                                    bytes[at + 3] = LOOPBACK_D
                                    bytes[at + 4] = ((port shr BYTE_BITS) and BYTE).toByte()
                                    bytes[at + 5] = (port and BYTE).toByte()
                                }
                            }
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

        /** BEP 23: four bytes of address and two of port, per peer. */
        private const val COMPACT_PEER_SIZE = 6
        private const val INTERVAL = 1800L
        private const val HTTP_OK = 200
    }
}
