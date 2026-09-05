package ru.workinprogress.kachok.engine.storage

import java.io.RandomAccessFile
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.deleteRecursively
import kotlin.math.max
import kotlin.random.Random

/**
 * B-30: `FileChannel.transferTo` against a mapped file, on real sockets.
 *
 * Research D5 chose `transferTo` on a copy-count argument — an mmap read still ends in
 * `SocketChannel.write(segment.asByteBuffer())`, which is the same copy the kernel would have done
 * anyway, plus a mapping to manage. The argument is plausible. So is the opposite one, which is
 * why this exists.
 *
 * **What is measured is the read path and not the client.** Everything above these two calls — the
 * choker, the peer wire, the piece layout — is identical in both variants, so putting it in the
 * measurement would add noise in exactly the amount that could hide the difference. What this does
 * keep from the real thing is the shape: 16 KiB spans at random positions, one virtual thread per
 * connection, a bounded number of peers pulling flat out, which is what the choker allows.
 *
 * `./gradlew :engine:uploadPathBench`. Not part of `build`: it takes minutes and it measures this
 * machine's page cache as much as this code.
 */
public object UploadPathBench {
    private const val BLOCK = 16 * 1024
    private const val MEGABYTE = 1024L * 1024L

    private class Variant(
        val label: String,
        val open: (Path, Long) -> Reader,
    )

    /** The one call the two paths disagree about. */
    private interface Reader : AutoCloseable {
        fun serve(
            position: Long,
            length: Int,
            target: WritableByteChannel,
        ): Long
    }

    private class TransferToReader(
        path: Path,
    ) : Reader {
        private val channel = FileChannel.open(path, StandardOpenOption.READ)

        override fun serve(
            position: Long,
            length: Int,
            target: WritableByteChannel,
        ): Long = channel.transferTo(position, length.toLong(), target)

        override fun close() = channel.close()
    }

    /**
     * The brief's path, written for this measurement only.
     *
     * One shared mapping of the whole file, as a seeding client would keep: mapping per block would
     * be a system call per block, which is the thing `transferTo` is being compared against rather
     * than a fair opponent. `Arena.ofShared` because the mapping outlives the thread that made it
     * and every peer thread reads through it.
     */
    private class MappedReader(
        path: Path,
        size: Long,
    ) : Reader {
        private val arena =
            java.lang.foreign.Arena
                .ofShared()
        private val channel = FileChannel.open(path, StandardOpenOption.READ)
        private val segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, size, arena)

        override fun serve(
            position: Long,
            length: Int,
            target: WritableByteChannel,
        ): Long {
            val buffer = segment.asSlice(position, length.toLong()).asByteBuffer()
            var written = 0L
            while (buffer.hasRemaining()) written += target.write(buffer)
            return written
        }

        override fun close() {
            channel.close()
            arena.close()
        }
    }

    private val variants =
        listOf(
            Variant("transferTo") { path, _ -> TransferToReader(path) },
            Variant("mmap + write") { path, size -> MappedReader(path, size) },
        )

    private class Measurement(
        val variant: String,
        val megabytesPerSecond: Double,
        val cpuSecondsPerGigabyte: Double,
        val peakPlatformThreads: Int,
    )

    @JvmStatic
    public fun main(args: Array<String>) {
        val megabytes = argument(args, "--megabytes")?.toInt() ?: 512
        // Five, because that is what the choker allows: four regular slots and one optimistic.
        // Above about ten this harness stops being able to tear itself down — the threads blocked
        // writing to a closed socket do not end, and the round after them measures their wreckage
        // rather than the read path (research §1.3c).
        val peers = argument(args, "--peers")?.toInt() ?: 5
        val seconds = argument(args, "--seconds")?.toInt() ?: 8
        val runs = argument(args, "--runs")?.toInt() ?: 3

        val root = Files.createTempDirectory("kachok-upload")
        val file = root.resolve("payload.bin")
        writeFixture(file, megabytes)
        println("upload path benchmark: $megabytes MB, $peers peers, ${seconds}s per run, $runs runs")

        try {
            // Both variants read the same file, and the first one to run would otherwise pay for
            // pulling it into the page cache.
            variants.forEach { measure(it, file, megabytes, peers, 2) }

            val measurements = mutableListOf<Measurement>()
            repeat(runs) { round ->
                variants.forEach { variant ->
                    val measured = measure(variant, file, megabytes, peers, seconds)
                    println(
                        "round ${round + 1}, ${variant.label}: " +
                            "%.0f MB/s, %.2f CPU-s per GB, %d platform threads".format(
                                measured.megabytesPerSecond,
                                measured.cpuSecondsPerGigabyte,
                                measured.peakPlatformThreads,
                            ),
                    )
                    measurements += measured
                }
            }
            report(measurements, megabytes, peers, seconds, runs)
        } finally {
            @OptIn(kotlin.io.path.ExperimentalPathApi::class)
            root.deleteRecursively()
        }
    }

    private fun measure(
        variant: Variant,
        file: Path,
        megabytes: Int,
        peers: Int,
        seconds: Int,
    ): Measurement {
        val size = megabytes * MEGABYTE
        val served = AtomicLong()
        val threads = ManagementFactory.getThreadMXBean()
        val operatingSystem =
            ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        var peakThreads = threads.threadCount
        val running =
            java.util.concurrent.atomic
                .AtomicBoolean(true)

        val server = ServerSocketChannel.open().bind(InetSocketAddress("127.0.0.1", 0), peers)
        val port = (server.localAddress as InetSocketAddress).port
        val workers = mutableListOf<Thread>()
        val sockets = mutableListOf<SocketChannel>()

        val reader = variant.open(file, size)
        run {
            repeat(peers) {
                // The peer: connects and reads as fast as the kernel will give it anything.
                val socket = SocketChannel.open(InetSocketAddress("127.0.0.1", port))
                sockets += socket
                workers +=
                    Thread.ofVirtual().start {
                        val sink = ByteBuffer.allocateDirect(BLOCK)
                        try {
                            while (running.get()) {
                                sink.clear()
                                if (socket.read(sink) < 0) break
                            }
                        } catch (closed: java.io.IOException) {
                            // The run is over and the socket was closed under this read; that is
                            // how a reader blocked on an idle socket is woken.
                        }
                    }
            }
            repeat(peers) {
                val socket = server.accept()
                sockets += socket
                // The seed: 16 KiB spans at random positions, the way a swarm asks for them.
                workers +=
                    Thread.ofVirtual().start {
                        val random = Random(socket.hashCode())
                        try {
                            while (running.get()) {
                                val at = (random.nextLong(size / BLOCK)) * BLOCK
                                served.addAndGet(reader.serve(at, BLOCK, socket))
                            }
                        } catch (closed: java.io.IOException) {
                            // Same: the peer went away or the run ended.
                        }
                    }
            }

            val cpuBefore = operatingSystem.processCpuTime
            val started = System.nanoTime()
            val deadline = started + seconds * NANOS_PER_SECOND
            val bytesBefore = served.get()
            while (System.nanoTime() < deadline) {
                peakThreads = max(peakThreads, threads.threadCount)
                Thread.sleep(POLL_MILLIS)
            }
            val bytes = served.get() - bytesBefore
            val elapsed = (System.nanoTime() - started).toDouble() / NANOS_PER_SECOND
            val cpu = (operatingSystem.processCpuTime - cpuBefore).toDouble() / NANOS_PER_SECOND

            // Closing first, joining second. A peer parked in `read` on a socket nobody is
            // writing to any more does not see the flag at all.
            running.set(false)
            sockets.forEach { socket -> closeQuietly(socket) }
            server.close()
            workers.forEach { thread -> thread.join(JOIN_MILLIS) }

            // Not `use`, and not a `close()` in a `finally`. Above about ten peers some
            // `transferTo` calls do not end when the socket they are writing to is closed from
            // another thread, and `FileChannel.close()` then blocks for ever inside
            // `NativeThreadSet.signalAndWait` waiting for them — which is a hung benchmark that
            // looks like a slow one. Saying so and leaking the channel into an exiting process is
            // the honest teardown; the observation itself is research §1.3c.
            val stragglers = workers.count { it.isAlive }
            if (stragglers == 0) {
                reader.close()
            } else {
                println("  (${variant.label}: $stragglers of ${workers.size} threads did not stop; channel left open)")
            }

            return Measurement(
                variant = variant.label,
                megabytesPerSecond = bytes / MEGABYTE.toDouble() / elapsed,
                cpuSecondsPerGigabyte = cpu / (bytes / (MEGABYTE * 1024).toDouble()),
                peakPlatformThreads = peakThreads,
            )
        }
    }

    private fun report(
        measurements: List<Measurement>,
        megabytes: Int,
        peers: Int,
        seconds: Int,
        runs: Int,
    ) {
        println()
        println("$megabytes MB, $peers peers pulling flat out, ${seconds}s per run, $runs runs, ${jdk()}")
        println()
        println("| Read path | Throughput | CPU per GB served | Peak platform threads |")
        println("|---|---|---|---|")
        variants.forEach { variant ->
            val rows = measurements.filter { it.variant == variant.label }
            println(
                "| ${variant.label} " +
                    "| ${rows.spread { "%.0f MB/s".format(it.megabytesPerSecond) }} " +
                    "| ${rows.spread { "%.2f s".format(it.cpuSecondsPerGigabyte) }} " +
                    "| ${rows.spread { it.peakPlatformThreads.toString() }} |",
            )
        }
        println()
        println("Each cell is min–max over the runs.")
    }

    private fun List<Measurement>.spread(format: (Measurement) -> String): String {
        val rendered = map(format).distinct().sorted()
        return if (rendered.size == 1) rendered.first() else "${rendered.first()}–${rendered.last()}"
    }

    private fun writeFixture(
        file: Path,
        megabytes: Int,
    ) {
        RandomAccessFile(file.toFile(), "rw").use { out ->
            val chunk = ByteArray(MEGABYTE.toInt()) { (it * 31 and 0xFF).toByte() }
            repeat(megabytes) { out.write(chunk) }
            out.fd.sync()
        }
    }

    private fun jdk(): String = "JDK " + (System.getProperty("java.version") ?: "?")

    private fun argument(
        args: Array<String>,
        name: String,
    ): String? {
        val index = args.indexOf(name)
        return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
    }

    private fun closeQuietly(closeable: AutoCloseable) {
        try {
            closeable.close()
        } catch (ending: java.io.IOException) {
            // Tearing a benchmark down; there is nobody left to tell.
        }
    }

    private const val JOIN_MILLIS = 2_000L
    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val POLL_MILLIS = 50L
}
