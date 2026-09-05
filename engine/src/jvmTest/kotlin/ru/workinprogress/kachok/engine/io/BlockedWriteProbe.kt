package ru.workinprogress.kachok.engine.io

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * B-44: what ends a write that is already blocked, and what does not.
 *
 * Found by the upload measurement (research §1.3c): with twenty peers pulling flat out, clearing
 * the run flag and closing every socket left every writer alive, and the `FileChannel.close()`
 * that followed waited for them for ever. The engine does not obviously hit it — the choker bounds
 * concurrent serves to five and shutdown closes peers before storage — but "does not obviously"
 * is not a property, and neither of those is a guarantee.
 *
 * Each row below blocks writers on a socket nobody reads from, then tries one way of stopping
 * them, and counts how many returned. `./gradlew :engine:blockedWriteProbe`, and the same class
 * runs in a Linux container for the other row of the table.
 */
public object BlockedWriteProbe {
    private const val WRITERS = 8
    private const val CHUNK = 1 shl 16
    private const val BUFFER = 8 * 1024
    private const val SETTLE_MILLIS = 500L
    private const val WAIT_SECONDS = 5L

    /**
     * Tearing down a socket this probe has just wedged on purpose.
     *
     * Every call under here is expected to fail — a socket the probe closed, a channel a writer is
     * still inside — and there is no next step for any of them to affect. The one thing that must
     * not happen is the teardown throwing over the report.
     */
    private inline fun quietly(action: () -> Unit) {
        try {
            action()
        } catch (expected: IOException) {
            // See above.
        }
    }

    private class Attempt(
        val label: String,
        val ended: Int,
        val of: Int,
        val note: String,
    )

    @JvmStatic
    public fun main(args: Array<String>) {
        println(
            "blocked-write probe: ${System.getProperty(
                "os.name",
            )} ${System.getProperty("os.arch")}, JDK ${System.getProperty("java.version")}",
        )
        println()
        println("| What was tried | Writers that ended | Note |")
        println("|---|---|---|")
        listOf(
            channelAttempt("SocketChannel.close() from another thread") { it.close() },
            channelAttempt("SocketChannel.shutdownOutput()") { it.shutdownOutput() },
            channelInterrupt(),
            socketAttempt("Socket.close() from another thread") { it.close() },
            socketAttempt("Socket.shutdownOutput()") { it.shutdownOutput() },
            transferAttempt("FileChannel.transferTo, then close the socket") { socket, _ -> socket.close() },
            transferAttempt("FileChannel.transferTo, then shutdownOutput") { socket, _ -> socket.shutdownOutput() },
            transferInterrupt(),
            transferAttempt("FileChannel.transferTo, then close the file") { _, file -> file.close() },
        ).forEach { println("| ${it.label} | ${it.ended} of ${it.of} | ${it.note} |") }
        println()
        println("A writer counts as ended when its `write` returns or throws within $WAIT_SECONDS s.")
    }

    /**
     * Blocks [WRITERS] virtual threads inside `SocketChannel.write`, then runs [stop] on each
     * socket and counts how many writers came back.
     *
     * The receive buffer is deliberately tiny and the far end never reads, so the writers block on
     * the second or third chunk rather than after megabytes.
     */
    private fun channelAttempt(
        label: String,
        stop: (SocketChannel) -> Unit,
    ): Attempt {
        val server = ServerSocketChannel.open().bind(InetSocketAddress("127.0.0.1", 0), WRITERS)
        server.setOption(StandardSocketOptions.SO_RCVBUF, BUFFER)
        val port = (server.localAddress as InetSocketAddress).port
        val ended = AtomicInteger()
        val blocked = CountDownLatch(WRITERS)
        val sockets = mutableListOf<SocketChannel>()
        val accepted = mutableListOf<SocketChannel>()
        val threads = mutableListOf<Thread>()
        var note = ""
        try {
            repeat(WRITERS) {
                val socket = SocketChannel.open(InetSocketAddress("127.0.0.1", port))
                socket.setOption(StandardSocketOptions.SO_SNDBUF, BUFFER)
                sockets += socket
                accepted += server.accept()
                threads +=
                    Thread.ofVirtual().start {
                        val buffer = ByteBuffer.allocateDirect(CHUNK)
                        try {
                            blocked.countDown()
                            while (true) {
                                buffer.clear()
                                socket.write(buffer)
                            }
                        } catch (stopped: Throwable) {
                            note = stopped::class.simpleName ?: "threw"
                        } finally {
                            ended.incrementAndGet()
                        }
                    }
            }
            blocked.await(WAIT_SECONDS, TimeUnit.SECONDS)
            Thread.sleep(SETTLE_MILLIS)
            sockets.forEach { quietly { stop(it) } }
            threads.forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS) / threads.size + 1) }
            return Attempt(label, ended.get(), WRITERS, note.ifEmpty { "still blocked" })
        } finally {
            sockets.forEach { quietly { it.close() } }
            accepted.forEach { quietly { it.close() } }
            quietly { server.close() }
        }
    }

    /**
     * The one the JDK documents: a channel is interruptible, and interrupting a thread blocked in
     * one is supposed to close it and throw.
     */
    private fun channelInterrupt(): Attempt {
        val server = ServerSocketChannel.open().bind(InetSocketAddress("127.0.0.1", 0), WRITERS)
        server.setOption(StandardSocketOptions.SO_RCVBUF, BUFFER)
        val port = (server.localAddress as InetSocketAddress).port
        val ended = AtomicInteger()
        val blocked = CountDownLatch(WRITERS)
        val sockets = mutableListOf<SocketChannel>()
        val accepted = mutableListOf<SocketChannel>()
        val threads = mutableListOf<Thread>()
        var note = ""
        try {
            repeat(WRITERS) {
                val socket = SocketChannel.open(InetSocketAddress("127.0.0.1", port))
                socket.setOption(StandardSocketOptions.SO_SNDBUF, BUFFER)
                sockets += socket
                accepted += server.accept()
                threads +=
                    Thread.ofVirtual().start {
                        val buffer = ByteBuffer.allocateDirect(CHUNK)
                        try {
                            blocked.countDown()
                            while (true) {
                                buffer.clear()
                                socket.write(buffer)
                            }
                        } catch (stopped: Throwable) {
                            note = stopped::class.simpleName ?: "threw"
                        } finally {
                            ended.incrementAndGet()
                        }
                    }
            }
            blocked.await(WAIT_SECONDS, TimeUnit.SECONDS)
            Thread.sleep(SETTLE_MILLIS)
            threads.forEach { it.interrupt() }
            threads.forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS) / threads.size + 1) }
            return Attempt("Thread.interrupt() on the writer", ended.get(), WRITERS, note.ifEmpty { "still blocked" })
        } finally {
            sockets.forEach { quietly { it.close() } }
            accepted.forEach { quietly { it.close() } }
            quietly { server.close() }
        }
    }

    /**
     * The configuration that actually hung, which is not the one the symptom named.
     *
     * The upload benchmark's threads were inside `FileChannel.transferTo(position, count, socket)`
     * — `sendfile(2)` on this platform — and not inside `SocketChannel.write`. A thread there is
     * registered on the *file* channel while blocking on the *socket*, which is one thread and two
     * channels, and the rows above say nothing about it.
     */
    private fun transferAttempt(
        label: String,
        stop: (SocketChannel, java.nio.channels.FileChannel) -> Unit,
    ): Attempt {
        val file =
            java.nio.file.Files
                .createTempFile("kachok-probe", ".bin")
        java.nio.file.Files
            .write(file, ByteArray(CHUNK * 4))
        val channel =
            java.nio.channels.FileChannel
                .open(file, java.nio.file.StandardOpenOption.READ)
        val server = ServerSocketChannel.open().bind(InetSocketAddress("127.0.0.1", 0), WRITERS)
        server.setOption(StandardSocketOptions.SO_RCVBUF, BUFFER)
        val port = (server.localAddress as InetSocketAddress).port
        val ended = AtomicInteger()
        val blocked = CountDownLatch(WRITERS)
        val sockets = mutableListOf<SocketChannel>()
        val accepted = mutableListOf<SocketChannel>()
        val threads = mutableListOf<Thread>()
        var note = ""
        try {
            repeat(WRITERS) {
                val socket = SocketChannel.open(InetSocketAddress("127.0.0.1", port))
                socket.setOption(StandardSocketOptions.SO_SNDBUF, BUFFER)
                sockets += socket
                accepted += server.accept()
                threads +=
                    Thread.ofVirtual().start {
                        try {
                            blocked.countDown()
                            while (true) channel.transferTo(0, CHUNK.toLong(), socket)
                        } catch (stopped: Throwable) {
                            note = stopped::class.simpleName ?: "threw"
                        } finally {
                            ended.incrementAndGet()
                        }
                    }
            }
            blocked.await(WAIT_SECONDS, TimeUnit.SECONDS)
            Thread.sleep(SETTLE_MILLIS)
            // On its own thread: closing a file channel with writers inside it is the call that
            // waited for ever in §1.3c, and a probe that hung here would say nothing.
            val closer = Thread.ofPlatform().start { sockets.forEach { quietly { stop(it, channel) } } }
            closer.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
            threads.forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS) / threads.size + 1) }
            val closerNote = if (closer.isAlive) "the close itself never returned" else note.ifEmpty { "still blocked" }
            return Attempt(label, ended.get(), WRITERS, closerNote)
        } finally {
            sockets.forEach { quietly { it.close() } }
            accepted.forEach { quietly { it.close() } }
            quietly { server.close() }
            // Neither closed nor interrupted while a writer is still inside it: both of those are
            // calls this probe has just measured as *not returning*, and doing either here would
            // hang the probe instead of reporting what it found. The channel is left open and the
            // process ends without it.
            if (threads.none { it.isAlive }) quietly { channel.close() }
            quietly {
                java.nio.file.Files
                    .deleteIfExists(file)
            }
        }
    }

    /**
     * The one that decides what a fix can look like.
     *
     * `FileChannel` is an `InterruptibleChannel`, so interrupting a thread blocked in one of its
     * operations is documented to close it and throw — even when what the thread is actually
     * waiting for is a socket at the other end of a `sendfile`.
     */
    private fun transferInterrupt(): Attempt {
        val file =
            java.nio.file.Files
                .createTempFile("kachok-probe", ".bin")
        java.nio.file.Files
            .write(file, ByteArray(CHUNK * 4))
        val server = ServerSocketChannel.open().bind(InetSocketAddress("127.0.0.1", 0), WRITERS)
        server.setOption(StandardSocketOptions.SO_RCVBUF, BUFFER)
        val port = (server.localAddress as InetSocketAddress).port
        val ended = AtomicInteger()
        val blocked = CountDownLatch(WRITERS)
        val sockets = mutableListOf<SocketChannel>()
        val accepted = mutableListOf<SocketChannel>()
        val channels = mutableListOf<java.nio.channels.FileChannel>()
        val threads = mutableListOf<Thread>()
        var note = ""
        try {
            repeat(WRITERS) {
                val socket = SocketChannel.open(InetSocketAddress("127.0.0.1", port))
                socket.setOption(StandardSocketOptions.SO_SNDBUF, BUFFER)
                sockets += socket
                accepted += server.accept()
                // One file channel per writer: an interrupt closes the channel it interrupted, so
                // sharing one would make the first interrupt end all eight for the wrong reason.
                val channel =
                    java.nio.channels.FileChannel
                        .open(file, java.nio.file.StandardOpenOption.READ)
                channels += channel
                threads +=
                    Thread.ofVirtual().start {
                        try {
                            blocked.countDown()
                            while (true) channel.transferTo(0, CHUNK.toLong(), socket)
                        } catch (stopped: Throwable) {
                            note = stopped::class.simpleName ?: "threw"
                        } finally {
                            ended.incrementAndGet()
                        }
                    }
            }
            blocked.await(WAIT_SECONDS, TimeUnit.SECONDS)
            Thread.sleep(SETTLE_MILLIS)
            // On its own thread, and bounded: `VirtualThread.interrupt` calls the channel's
            // `postInterrupt`, which closes it, which waits for the thread being interrupted. The
            // first version of this row hung the *probe* rather than reporting.
            val stopper = Thread.ofPlatform().start { threads.forEach { quietly { it.interrupt() } } }
            stopper.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
            threads.forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS) / threads.size + 1) }
            return Attempt(
                "FileChannel.transferTo, then interrupt the writer",
                ended.get(),
                WRITERS,
                if (stopper.isAlive) "the interrupt itself never returned" else note.ifEmpty { "still blocked" },
            )
        } finally {
            sockets.forEach { quietly { it.close() } }
            accepted.forEach { quietly { it.close() } }
            quietly { server.close() }
            // Only the channels nobody is inside: closing the others is the call that does not
            // return, which this row has just finished measuring.
            if (threads.none { it.isAlive }) channels.forEach { quietly { it.close() } }
            quietly {
                java.nio.file.Files
                    .deleteIfExists(file)
            }
        }
    }

    /** The same question of `java.net.Socket`, which is a different implementation underneath. */
    private fun socketAttempt(
        label: String,
        stop: (Socket) -> Unit,
    ): Attempt {
        val server = ServerSocket(0, WRITERS, java.net.InetAddress.getLoopbackAddress())
        server.receiveBufferSize = BUFFER
        val ended = AtomicInteger()
        val blocked = CountDownLatch(WRITERS)
        val sockets = mutableListOf<Socket>()
        val accepted = mutableListOf<Socket>()
        val threads = mutableListOf<Thread>()
        var note = ""
        try {
            repeat(WRITERS) {
                val socket = Socket("127.0.0.1", server.localPort)
                socket.sendBufferSize = BUFFER
                sockets += socket
                accepted += server.accept()
                threads +=
                    Thread.ofVirtual().start {
                        val chunk = ByteArray(CHUNK)
                        try {
                            blocked.countDown()
                            while (true) socket.getOutputStream().write(chunk)
                        } catch (stopped: Throwable) {
                            note = stopped::class.simpleName ?: "threw"
                        } finally {
                            ended.incrementAndGet()
                        }
                    }
            }
            blocked.await(WAIT_SECONDS, TimeUnit.SECONDS)
            Thread.sleep(SETTLE_MILLIS)
            sockets.forEach { quietly { stop(it) } }
            threads.forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS) / threads.size + 1) }
            return Attempt(label, ended.get(), WRITERS, note.ifEmpty { "still blocked" })
        } finally {
            sockets.forEach { quietly { it.close() } }
            accepted.forEach { quietly { it.close() } }
            quietly { server.close() }
        }
    }
}
