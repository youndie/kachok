package io.github.youndie.kachok.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.bencode.BDictionary
import io.github.youndie.kachok.engine.bencode.BInteger
import io.github.youndie.kachok.engine.bencode.BString
import io.github.youndie.kachok.engine.bencode.Bencode
import io.github.youndie.kachok.engine.metainfo.Metainfo
import io.github.youndie.kachok.engine.metainfo.MetainfoParser
import io.github.youndie.kachok.engine.resume.ResumeRecord
import io.github.youndie.kachok.engine.wire.PeerWire
import io.github.youndie.kachok.swarm.SeedingPeer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The acceptance criterion of B-25, against a real signal and a real process.
 *
 * A shutdown sequence is only worth anything if it survives the way it is actually triggered, so
 * this spawns the client in its own JVM and sends it `SIGINT` — the same thing a user pressing
 * Ctrl-C sends. Everything else about it is in-process; this one cannot be.
 *
 * **POSIX only, and it says so rather than failing.** `SIGINT` is what a shutdown hook is *for*,
 * and there is no way to send one on Windows: `Process.destroy` there is `TerminateProcess`, which
 * runs no hook and would test nothing. Running the suite on Windows found this as a red build with
 * `Cannot run program "kill"` — which is a test that cannot run, reported as a client that does not
 * work. It is skipped there, loudly, and the client's shutdown on Windows is not covered by
 * anything: what a person there presses is the window's close button, which is
 * [B-53](../../../../../../../docs/backlog/B-53-a-window-that-closes-cleanly.md)'s path and not
 * this one.
 */
class ShutdownTest {
    private val root: Path = Files.createTempDirectory("kachok-shutdown")
    private var server: HttpServer? = null
    private var seed: SeedingPeer? = null
    private val announces = ConcurrentLinkedQueue<String>()

    /** Large enough that the download is still running when the signal arrives. */
    private val content = ByteArray(4_000_000) { (it * 31 and 0xFF).toByte() }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() {
        server?.stop(0)
        seed?.close()
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

    private fun startTracker(peerPort: Int): String {
        val started = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        started.createContext("/annc") { exchange: HttpExchange ->
            announces += exchange.requestURI.rawQuery ?: ""
            val packed =
                byteArrayOf(
                    127,
                    0,
                    0,
                    1,
                    ((peerPort shr 8) and 0xFF).toByte(),
                    (peerPort and 0xFF).toByte(),
                )
            val body =
                Bencode.encode(
                    BDictionary(
                        mapOf(
                            BString("interval") to BInteger(1800),
                            BString("peers") to BString(packed),
                        ),
                    ),
                )
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        started.start()
        server = started
        return "http://127.0.0.1:${started.address.port}/annc"
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun anInterruptedDownloadTellsTheTrackerAndLeavesAUsableRecord() {
        val placeholder = startTracker(1)
        val infoHash: InfoHash = MetainfoParser.parse(torrentBytes(placeholder)).infoHash
        // A seed that serves slowly, so the signal arrives mid-download rather than after it.
        val peer = SeedingPeer(infoHash, content, PeerWire.BLOCK_SIZE, delayPerBlockMillis = 15)
        seed = peer
        server?.stop(0)
        announces.clear()
        val trackerUrl = startTracker(peer.port)

        val torrent = root.resolve("fixture.torrent")
        Files.write(torrent, torrentBytes(trackerUrl))
        val out = root.resolve("out")

        val process =
            ProcessBuilder(
                listOf(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    "io.github.youndie.kachok.cli.MainKt",
                    "download",
                    torrent.toString(),
                    "--dir",
                    out.toString(),
                ),
            ).redirectErrorStream(true).start()

        val output = StringBuilder()
        val reader =
            Thread.ofVirtual().start {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(output) { output.appendLine(line) }
                }
            }

        // Wait until it is genuinely downloading, then interrupt it.
        //
        // The signal to wait on is the seed's own request log and not the client's progress output.
        // Progress is printed on a timer, so a run that got ahead of that timer — a warm JIT is
        // enough, and the end-to-end test above warms it — reached the first printed line only
        // after the download had already finished, and there was nothing left to interrupt. What
        // the test needs to know is that blocks are moving, and the seed knows that first.
        val deadline = System.nanoTime() + SECONDS_TO_NANOS * 30
        while (System.nanoTime() < deadline && peer.served.size < BLOCKS_BEFORE_THE_SIGNAL) {
            Thread.sleep(5)
        }
        assertTrue(
            peer.served.size >= BLOCKS_BEFORE_THE_SIGNAL,
            "the download never started, so the signal would prove nothing: $output",
        )
        // `kill -INT` and not `Process.destroy()`: on POSIX the latter sends SIGTERM, which this
        // client does not install a hook for, and the whole point is the signal Ctrl-C sends.
        ProcessBuilder("kill", "-INT", process.pid().toString()).start().waitFor()

        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the client did not stop: $output")
        reader.join(5_000)

        assertTrue(
            announces.any { it.contains("event=stopped") },
            "the tracker never heard `stopped`; announces were $announces",
        )

        // `payload.bin.<hash>.resume`, not `payload.bin.resume`: two different torrents can be
        // called `payload.bin`, and the name alone had them sharing one record (B-60). Matched by
        // shape rather than spelled out, because the hash is the fixture's and not this test's
        // business.
        val record =
            Files
                .list(out)
                .use { paths -> paths.filter { it.fileName.toString().endsWith(".resume") }.toList() }
                .singleOrNull()
        assertTrue(record != null, "no resume record was written; output was $output")
        assertTrue(
            record.fileName.toString().startsWith("payload.bin."),
            "the record is not named after its torrent: ${record.fileName}",
        )
        val read = ResumeRecord.decode(Files.readAllBytes(record), infoHash, pieceCount = 245)
        assertTrue(read.verified.cardinality > 0, "the record vouches for nothing, so it saved nothing")

        // Every piece the record claims must actually be on the disk and hash correctly — the
        // record's whole promise.
        val file = Files.readAllBytes(out.resolve("payload.bin"))
        val digest = MessageDigest.getInstance("SHA-1")
        (0 until read.verified.size).filter { read.verified[it] }.forEach { index ->
            val from = index * PeerWire.BLOCK_SIZE
            val to = minOf(from + PeerWire.BLOCK_SIZE, content.size)
            digest.reset()
            digest.update(file, from, to - from)
            assertTrue(
                digest.digest().contentEquals(
                    MessageDigest.getInstance("SHA-1").digest(content.copyOfRange(from, to)),
                ),
                "the record claims piece $index but the disk does not have it",
            )
        }
        assertEquals(content.size.toLong(), Files.size(out.resolve("payload.bin")))
    }

    private companion object {
        const val SECONDS_TO_NANOS = 1_000_000_000L

        /** Enough that the download is under way, few enough that most of it is still to come. */
        const val BLOCKS_BEFORE_THE_SIGNAL = 20
    }
}
