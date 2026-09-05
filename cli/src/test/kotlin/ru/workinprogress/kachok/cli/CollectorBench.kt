package ru.workinprogress.kachok.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import jdk.jfr.consumer.RecordingFile
import ru.workinprogress.kachok.engine.InfoHash
import ru.workinprogress.kachok.engine.bencode.BDictionary
import ru.workinprogress.kachok.engine.bencode.BInteger
import ru.workinprogress.kachok.engine.bencode.BString
import ru.workinprogress.kachok.engine.bencode.Bencode
import ru.workinprogress.kachok.engine.metainfo.Metainfo
import ru.workinprogress.kachok.engine.metainfo.MetainfoParser
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteRecursively
import kotlin.math.max

/**
 * The measurement B-27 asks for: the same download, run under each collector this project might
 * ship, with the numbers that decide between them.
 *
 * It is a local swarm rather than the public one of research §1.2b on purpose. A collector is
 * chosen on how it behaves under this engine's allocation rate, and the public swarm sets that rate
 * by whatever the internet gives on the day — two runs of the same configuration would differ more
 * than two configurations do. Here the seed is in this process and serves from memory, so every
 * configuration sees the same bytes at whatever rate the machine can manage, and the differences
 * that remain are the collectors'.
 *
 * Run it with `./gradlew :cli:collectorBench`. Nothing calls it from a test: it takes minutes, it
 * measures the machine as much as the code, and a number produced by CI on a shared runner would
 * be worse than no number at all.
 */
public object CollectorBench {
    private const val MEGABYTE = 1024L * 1024L
    private const val POLL_MILLIS = 100L
    private const val RUN_TIMEOUT_MINUTES = 10L
    private const val NANOS_PER_MILLI = 1_000_000L
    private const val NANOS_PER_MICRO = 1_000L

    private data class Config(
        val label: String,
        val flags: List<String>,
    )

    private data class Measurement(
        val config: String,
        val wallMillis: Long,
        val liveBytes: Long,
        val maxPauseMicros: Long,
        val totalPauseMicros: Long,
        val collections: Int,
        val peakRssKilobytes: Long,
        val complete: Boolean,
    )

    /**
     * The four the item asks for, plus the two the first four make worth asking: resident memory
     * turned out to track the *committed* heap rather than the live set, which makes `-Xmx` itself
     * a variable in this comparison and not a constant of it.
     */
    private val configurations =
        listOf(
            Config("G1 + compact headers, 256m", listOf("-XX:+UseG1GC", "-XX:+UseCompactObjectHeaders", "-Xmx256m")),
            Config("G1, 256m", listOf("-XX:+UseG1GC", "-XX:-UseCompactObjectHeaders", "-Xmx256m")),
            Config("ZGC + compact headers, 256m", listOf("-XX:+UseZGC", "-XX:+UseCompactObjectHeaders", "-Xmx256m")),
            Config("ZGC, 256m", listOf("-XX:+UseZGC", "-XX:-UseCompactObjectHeaders", "-Xmx256m")),
            Config("G1 + compact headers, 128m", listOf("-XX:+UseG1GC", "-XX:+UseCompactObjectHeaders", "-Xmx128m")),
            Config("G1 + compact headers, 64m", listOf("-XX:+UseG1GC", "-XX:+UseCompactObjectHeaders", "-Xmx64m")),
            Config("ZGC + compact headers, 64m", listOf("-XX:+UseZGC", "-XX:+UseCompactObjectHeaders", "-Xmx64m")),
        )

    @JvmStatic
    public fun main(args: Array<String>) {
        val megabytes = argument(args, "--megabytes")?.toInt() ?: 256
        val pieceLength = argument(args, "--piece-length")?.toInt() ?: (256 * 1024)
        val runs = argument(args, "--runs")?.toInt() ?: 3

        val root = Files.createTempDirectory("kachok-bench")
        println("kachok collector benchmark: $megabytes MB, $pieceLength B pieces, $runs runs per configuration")
        println("workspace $root")

        val content = ByteArray((megabytes * MEGABYTE).toInt()) { (it * 31 and 0xFF).toByte() }
        val expected = MessageDigest.getInstance("SHA-256").digest(content)

        val fixture = Fixture(root, content, pieceLength)
        try {
            // A first run nobody records: the page cache is cold, the JIT has never seen this code
            // and the first configuration would wear the cost of both.
            println("warming up…")
            runOnce(fixture, configurations.first(), expected, root.resolve("warmup"))

            val measurements = mutableListOf<Measurement>()
            // Round robin rather than all the runs of one configuration together: whatever else
            // this machine decides to do during the next few minutes should land on all four.
            repeat(runs) { round ->
                configurations.forEach { config ->
                    print("round ${round + 1}, ${config.label}… ")
                    val measured = runOnce(fixture, config, expected, root.resolve("r$round-${config.hashCode()}"))
                    println(
                        "${measured.wallMillis} ms, live ${measured.liveBytes / MEGABYTE} MB, " +
                            "max pause ${measured.maxPauseMicros} µs, RSS ${measured.peakRssKilobytes / 1024} MB",
                    )
                    measurements += measured
                }
            }
            report(measurements, megabytes, runs)
        } finally {
            fixture.close()
            @OptIn(kotlin.io.path.ExperimentalPathApi::class)
            root.deleteRecursively()
        }
    }

    private fun runOnce(
        fixture: Fixture,
        config: Config,
        expected: ByteArray,
        workspace: Path,
    ): Measurement {
        Files.createDirectories(workspace)
        val recording = workspace.resolve("run.jfr")
        val command =
            buildList {
                add(Path.of(System.getProperty("java.home"), "bin", "java").toString())
                addAll(config.flags)
                add("-XX:StartFlightRecording=filename=$recording,settings=default,dumponexit=true")
                add("-cp")
                add(System.getProperty("java.class.path"))
                add("ru.workinprogress.kachok.cli.MainKt")
                add("download")
                add(fixture.torrent.toString())
                add("--dir")
                add(workspace.resolve("out").toString())
            }

        val started = System.nanoTime()
        val process =
            ProcessBuilder(command)
                .redirectOutput(workspace.resolve("stdout.txt").toFile())
                .redirectError(workspace.resolve("stderr.txt").toFile())
                .start()
        val sampler = ResidentSetSampler(process.pid())
        val finished = process.waitFor(RUN_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        val wall = (System.nanoTime() - started) / NANOS_PER_MILLI
        sampler.stop()
        check(finished) { "${config.label} did not finish inside $RUN_TIMEOUT_MINUTES minutes" }
        check(process.exitValue() == 0) {
            "${config.label} exited ${process.exitValue()}: ${Files.readString(workspace.resolve("stderr.txt"))}"
        }

        val downloaded = workspace.resolve("out").resolve(fixture.name)
        val actual = digestOf(downloaded)
        val gc = GcSummary.of(recording)
        // The payload is a gigabyte and there are a dozen runs; keeping them all would measure the
        // disk filling up rather than the collector.
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        workspace.resolve("out").deleteRecursively()
        return Measurement(
            config = config.label,
            wallMillis = wall,
            liveBytes = gc.liveBytes,
            maxPauseMicros = gc.maxPauseNanos / NANOS_PER_MICRO,
            totalPauseMicros = gc.totalPauseNanos / NANOS_PER_MICRO,
            collections = gc.collections,
            peakRssKilobytes = sampler.peakKilobytes,
            complete = actual.contentEquals(expected),
        )
    }

    /**
     * The numbers a collector is chosen on, read out of the recording rather than out of `-Xlog:gc`:
     * the log's shape differs per collector, and a pause phase is a pause phase in every recording.
     */
    private class GcSummary(
        val liveBytes: Long,
        val maxPauseNanos: Long,
        val totalPauseNanos: Long,
        val collections: Int,
    ) {
        companion object {
            fun of(recording: Path): GcSummary {
                var live = 0L
                var maxPause = 0L
                var totalPause = 0L
                var collections = 0
                RecordingFile(recording).use { file ->
                    while (file.hasMoreEvents()) {
                        val event = file.readEvent()
                        when (event.eventType.name) {
                            "jdk.GCHeapSummary" -> {
                                if (event.getString("when") == "After GC") {
                                    live = max(live, event.getLong("heapUsed"))
                                }
                            }

                            "jdk.GCPhasePause" -> {
                                val nanos = event.duration.toNanos()
                                maxPause = max(maxPause, nanos)
                                totalPause += nanos
                            }

                            "jdk.GarbageCollection" -> {
                                collections++
                            }
                        }
                    }
                }
                return GcSummary(live, maxPause, totalPause, collections)
            }
        }
    }

    /**
     * Peak resident set, sampled. A sample can miss a spike between two polls; what it cannot miss
     * is the difference between a collector that keeps a hundred megabytes mapped and one that
     * keeps three hundred, which is the question this is here to answer.
     */
    private class ResidentSetSampler(
        pid: Long,
    ) {
        @Volatile
        private var running = true

        @Volatile
        var peakKilobytes: Long = 0
            private set

        private val thread =
            Thread.ofVirtual().start {
                while (running) {
                    val sample = readResidentSetKilobytes(pid)
                    if (sample > peakKilobytes) peakKilobytes = sample
                    Thread.sleep(POLL_MILLIS)
                }
            }

        fun stop() {
            running = false
            thread.join(TimeUnit.SECONDS.toMillis(2))
        }

        private fun readResidentSetKilobytes(pid: Long): Long {
            val process = ProcessBuilder("ps", "-o", "rss=", "-p", pid.toString()).start()
            val text =
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            process.waitFor(2, TimeUnit.SECONDS)
            return text.toLongOrNull() ?: 0
        }
    }

    private fun report(
        measurements: List<Measurement>,
        megabytes: Int,
        runs: Int,
    ) {
        println()
        println("$megabytes MB over a local swarm, $runs runs per configuration, JDK ${systemJdk()}")
        println()
        println("| Configuration | Wall (s) | Live after GC | Max pause | Total pause | Collections | Peak RSS |")
        println("|---|---|---|---|---|---|---|")
        configurations.forEach { config ->
            val rows = measurements.filter { it.config == config.label }
            check(rows.all { it.complete }) { "${config.label} produced bytes that are not the torrent's" }
            println(
                "| ${config.label} " +
                    "| ${rows.map { it.wallMillis }.spread { "%.1f".format(it / 1000.0) }} " +
                    "| ${rows.map { it.liveBytes }.spread { "${it / MEGABYTE} MB" }} " +
                    "| ${rows.map { it.maxPauseMicros }.spread { "%.2f ms".format(it / 1000.0) }} " +
                    "| ${rows.map { it.totalPauseMicros }.spread { "%.1f ms".format(it / 1000.0) }} " +
                    "| ${rows.map { it.collections.toLong() }.spread { it.toString() }} " +
                    "| ${rows.map { it.peakRssKilobytes }.spread { "${it / 1024} MB" }} |",
            )
        }
        println()
        println("Every cell is min–max over the runs; one number means the runs agreed.")
    }

    /** A range, not an average: with three runs the spread is the honest summary. */
    private fun List<Long>.spread(format: (Long) -> String): String {
        val low = min()
        val high = max()
        return if (low == high) format(low) else "${format(low)}–${format(high)}"
    }

    /** Streamed rather than read whole: the payload does not fit in this process twice. */
    private fun digestOf(file: Path): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    private fun systemJdk(): String = System.getProperty("java.version") ?: "unknown"

    private fun argument(
        args: Array<String>,
        name: String,
    ): String? {
        val index = args.indexOf(name)
        return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
    }

    /**
     * The torrent, the tracker and the seed: built once and served to every configuration.
     *
     * The announce URL is not part of the info dictionary, so the info hash does not depend on it —
     * the seed can be built before the tracker knows its own port.
     */
    private class Fixture(
        root: Path,
        content: ByteArray,
        pieceLength: Int,
    ) : AutoCloseable {
        val name: String = "payload.bin"
        private val seed: SeedingPeer
        private val tracker: HttpServer
        val torrent: Path

        init {
            val info = infoDictionary(content, pieceLength, name)
            seed = SeedingPeer(infoHashOf(info), content, pieceLength)
            tracker = trackerServing(seed.port)
            val url = "http://127.0.0.1:${tracker.address.port}/annc"
            torrent = root.resolve("fixture.torrent")
            Files.write(
                torrent,
                Bencode.encode(BDictionary(mapOf(BString("announce") to BString(url), BString("info") to info))),
            )
        }

        override fun close() {
            tracker.stop(0)
            seed.close()
        }

        private fun infoHashOf(info: BDictionary): InfoHash =
            MetainfoParser
                .parse(
                    Bencode.encode(
                        BDictionary(
                            mapOf(
                                BString("announce") to BString("http://127.0.0.1:1/annc"),
                                BString("info") to info,
                            ),
                        ),
                    ),
                ).infoHash

        private fun infoDictionary(
            content: ByteArray,
            pieceLength: Int,
            name: String,
        ): BDictionary {
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
            return BDictionary(
                mapOf(
                    BString("length") to BInteger(content.size.toLong()),
                    BString("name") to BString(name),
                    BString("piece length") to BInteger(pieceLength.toLong()),
                    BString("pieces") to BString(hashes),
                ),
            )
        }

        private fun trackerServing(peerPort: Int): HttpServer {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
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
            server.createContext("/annc") { exchange: HttpExchange ->
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            return server
        }
    }
}
