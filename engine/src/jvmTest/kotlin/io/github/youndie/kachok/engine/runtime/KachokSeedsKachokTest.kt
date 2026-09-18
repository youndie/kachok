package io.github.youndie.kachok.engine.runtime

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.youndie.kachok.engine.bencode.BDictionary
import io.github.youndie.kachok.engine.bencode.BInteger
import io.github.youndie.kachok.engine.bencode.BString
import io.github.youndie.kachok.engine.bencode.Bencode
import io.github.youndie.kachok.engine.io.EngineDispatchers
import io.github.youndie.kachok.engine.metainfo.Metainfo
import io.github.youndie.kachok.engine.metainfo.MetainfoParser
import io.github.youndie.kachok.engine.wire.PeerWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One kachok downloads from another, which for six milestones was the thing no test did.
 *
 * Every upload test ran the choker, the budget and the read path against a fake connection, and
 * the one line that joined them to a socket was never written: a real connection had no storage to
 * serve from and dropped every request without a word
 * ([B-110](../../../../../../../../docs/backlog/B-110-this-client-never-uploads-a-block.md)). The
 * only way to see that is this: two runtimes, one file, a tracker between them, and the assertion
 * that the *seeder's* `uploaded` — the counter read off its connections, not its own meter — is
 * the whole file.
 */
class KachokSeedsKachokTest {
    private val root: Path = Files.createTempDirectory("kachok-seeds")
    private var tracker: HttpServer? = null

    /** Three pieces of 16 KiB and a short one, so the last block is the awkward case. */
    private val content = ByteArray(60_000) { (it * 13 and 0xFF).toByte() }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() {
        tracker?.stop(0)
        root.deleteRecursively()
    }

    private fun torrentBytes(trackerUrl: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-1")
        val pieces = (content.size + PeerWire.BLOCK_SIZE - 1) / PeerWire.BLOCK_SIZE
        val hashes = ByteArray(pieces * Metainfo.HASH_SIZE)
        (0 until pieces).forEach { index ->
            val from = index * PeerWire.BLOCK_SIZE
            val to = minOf(from + PeerWire.BLOCK_SIZE, content.size)
            digest.reset()
            digest.update(content, from, to - from)
            digest.digest().copyInto(hashes, index * Metainfo.HASH_SIZE)
        }
        val info =
            BDictionary(
                mapOf(
                    BString("length") to BInteger(content.size.toLong()),
                    BString("name") to BString("payload.bin"),
                    BString("piece length") to BInteger(PeerWire.BLOCK_SIZE.toLong()),
                    BString("pieces") to BString(hashes),
                ),
            )
        return Bencode.encode(
            BDictionary(mapOf(BString("announce") to BString(trackerUrl), BString("info") to info)),
        )
    }

    /**
     * A tracker that tells everybody about the seeder — except the seeder, which would otherwise
     * dial itself and hold a connection to its own port for the rest of the test.
     */
    private fun startTracker(seederPort: () -> Int?): String {
        val started = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        started.createContext("/annc") { exchange: HttpExchange ->
            val asking =
                Regex("port=(\\d+)")
                    .find(exchange.requestURI.rawQuery.orEmpty())
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()
            val seeder = seederPort()
            val packed =
                if (seeder != null && seeder != asking) {
                    byteArrayOf(127, 0, 0, 1, ((seeder shr 8) and 0xFF).toByte(), (seeder and 0xFF).toByte())
                } else {
                    ByteArray(0)
                }
            val body =
                Bencode.encode(
                    BDictionary(mapOf(BString("interval") to BInteger(1800), BString("peers") to BString(packed))),
                )
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        started.start()
        tracker = started
        return "http://127.0.0.1:${started.address.port}/annc"
    }

    @Test
    fun theSeederServesEveryByteAndItsOwnCounterSaysSo(): Unit =
        runBlocking {
            val dispatchers = EngineDispatchers()
            val job = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + dispatchers.io + job)
            var seederPort: Int? = null
            val trackerUrl = startTracker { seederPort }
            val metainfo = MetainfoParser.parse(torrentBytes(trackerUrl))

            val seedDir = root.resolve("seed").also { Files.createDirectories(it) }
            Files.write(seedDir.resolve("payload.bin"), content)
            val leechDir = root.resolve("leech")

            val seederSet = TorrentSet(dispatchers, scope)
            val leecherSet = TorrentSet(dispatchers, scope)
            try {
                val seeder = seederSet.add(metainfo, RuntimeOptions(directory = seedDir))
                seeder.restore()
                assertTrue(seeder.state.value.isComplete, "the seeder did not recognise its own complete file")
                seeder.start(scope)
                seederPort = seederSet.listenPort

                val leecher = leecherSet.add(metainfo, RuntimeOptions(directory = leechDir))
                leecher.restore()
                leecher.start(scope)

                val deadline = System.nanoTime() + 60_000_000_000
                while (!leecher.state.value.isComplete && System.nanoTime() < deadline) Thread.sleep(50)
                assertTrue(
                    leecher.state.value.isComplete,
                    "the leecher did not finish: " +
                        "${leecher.state.value.completedPieces}/${metainfo.pieceCount} pieces, " +
                        "peers ${leecher.state.value.connectedPeers}, " +
                        "last error ${leecher.state.value.lastPeerError}",
                )
                assertContentEquals(
                    content,
                    Files.readAllBytes(leechDir.resolve("payload.bin")),
                    "the bytes on disk are not the torrent's",
                )

                // The counter that told the truth for six milestones: it is summed from the
                // connections' own `uploaded`, so a block that never left the socket never counts.
                val settled = System.nanoTime() + 5_000_000_000
                while (seeder.state.value.uploaded < content.size && System.nanoTime() < settled) Thread.sleep(50)
                val s = seeder.state.value
                val l = leecher.state.value
                // Exactly the file, and not a byte more: with B-111 a second connection to the same
                // peer — local discovery dialling back what the tracker gave — is dropped, so
                // endgame has no "other" peer to ask for everything again. Before that the seeder
                // honestly served the file twice, 120 000 of 60 000 bytes.
                assertEquals(
                    content.size.toLong(),
                    s.uploaded,
                    "the seeder served ${s.uploaded} of ${content.size} bytes; connected=${s.connectedPeers}, " +
                        "reasons=${s.disconnectReasons}; leecher downloaded=${l.downloaded} on ${l.connectedPeers}",
                )
                assertEquals(
                    l.downloaded,
                    s.uploaded,
                    "what one side counted as served, the other counted as received",
                )
                assertTrue(s.connectedPeers <= 1 && l.connectedPeers <= 1, "two connections to one peer survived")
                // **And every byte of it went through a keystream.** Both ends default to
                // `PREFERRED` (B-100), so this download — tracker, dial, handshake, blocks — is
                // the encrypted path end to end, and a regression to the clear shows up here
                // rather than on somebody's private tracker.
                assertTrue(
                    s.peers.all { it.encrypted } && l.peers.all { it.encrypted },
                    "the transfer was not encrypted: seeder ${s.peers.map { it.encrypted }}, " +
                        "leecher ${l.peers.map { it.encrypted }}",
                )
            } finally {
                leecherSet.close()
                seederSet.close()
                job.cancelAndJoin()
                dispatchers.close()
            }
        }
}
