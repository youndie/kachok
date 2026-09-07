package io.github.youndie.kachok.engine.io

import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.IOException
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.FileChannel
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * B-44, as a guard rather than a probe: what `SocketPeerConnection.close` has to do to a socket
 * with a `transferTo` in flight, and what is not enough.
 *
 * The full table of what was tried is research §1.3d, produced by `:engine:blockedWriteProbe`.
 * This keeps the one row the engine depends on: `close()` alone leaves the writer blocked, and
 * `shutdownOutput()` ends it — on both platforms measured. If a JDK ever makes `close()` enough,
 * this test starts failing on its first assertion, which is the right way to find that out.
 */
class BlockedTransferTest {
    private val started = CountDownLatch(1)
    private var server: ServerSocketChannel? = null
    private var client: SocketChannel? = null
    private var accepted: SocketChannel? = null
    private var file: java.nio.file.Path? = null
    private var channel: FileChannel? = null
    private var writer: Thread? = null
    private val ended = AtomicBoolean()

    @AfterTest
    fun tearDown() {
        // Whatever the test found, the socket has to be shut down or this teardown inherits the
        // problem: a channel with a writer inside it cannot be closed.
        closeQuietly { client?.shutdownOutput() }
        writer?.join(WAIT_MILLIS)
        closeQuietly { client?.close() }
        closeQuietly { accepted?.close() }
        closeQuietly { server?.close() }
        if (writer?.isAlive != true) closeQuietly { channel?.close() }
        file?.let { closeQuietly { Files.deleteIfExists(it) } }
    }

    /**
     * Tearing down after a test that deliberately wedged a socket.
     *
     * Every call here is expected to fail for one reason or another — the socket the test closed,
     * the channel a writer is still inside — and there is no next step for any of them to affect.
     */
    private inline fun closeQuietly(action: () -> Unit) {
        try {
            action()
        } catch (expected: IOException) {
            // See above: the teardown of a deliberately broken socket.
        }
    }

    /** A writer wedged inside `transferTo`, because the far end accepted and never reads. */
    private fun blockedTransfer() {
        val listener = ServerSocketChannel.open().bind(InetSocketAddress("127.0.0.1", 0), 1)
        listener.setOption(StandardSocketOptions.SO_RCVBUF, BUFFER)
        server = listener
        val port = (listener.localAddress as InetSocketAddress).port
        val payload = Files.createTempFile("kachok-transfer", ".bin")
        file = payload
        Files.write(payload, ByteArray(CHUNK * 4))
        val source = FileChannel.open(payload, StandardOpenOption.READ)
        channel = source
        val socket = SocketChannel.open(InetSocketAddress("127.0.0.1", port))
        socket.setOption(StandardSocketOptions.SO_SNDBUF, BUFFER)
        client = socket
        accepted = listener.accept()
        writer =
            Thread.ofVirtual().start {
                try {
                    started.countDown()
                    while (true) source.transferTo(0, CHUNK.toLong(), socket)
                } catch (stopped: IOException) {
                    // The point of the test: it got out.
                } finally {
                    ended.set(true)
                }
            }
        started.await(5, TimeUnit.SECONDS)
        Thread.sleep(SETTLE_MILLIS)
        assertTrue(!ended.get(), "the writer was never blocked, so this test measures nothing")
    }

    /**
     * **POSIX only, and Windows is the reason this probe exists at all.**
     *
     * Run there, it fails — `close()` *does* end a transfer in flight on Windows, where on Linux
     * and macOS it does not. That is the platform difference `SocketPeerConnection.close` carries
     * its `shutdownOutput` for, and a probe that asserted the POSIX behaviour on Windows would be
     * reporting a client that works as a client that is broken. Written down in the research beside
     * §1.3d rather than left as a red build.
     */
    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun closingTheSocketDoesNotEndATransferInFlight() {
        blockedTransfer()

        client!!.close()
        writer!!.join(WAIT_MILLIS)

        assertTrue(
            !ended.get(),
            "close() ended the transfer on this JDK — if that is now true everywhere, " +
                "SocketPeerConnection.close no longer needs its shutdownOutput and research §1.3d is stale",
        )
    }

    @Test
    fun shuttingTheOutputDownDoesEndIt() {
        // On a still-open socket, which is why `SocketPeerConnection.close` shuts down *before* it
        // closes: after the close there is nothing left to shut down and the writer stays wedged.
        blockedTransfer()

        client!!.shutdownOutput()
        writer!!.join(WAIT_MILLIS)

        assertTrue(
            ended.get(),
            "nothing ended the transfer; a peer that stops reading holds a coroutine for as long " +
                "as TCP takes to give up, and FileSet.close() then never returns",
        )
    }

    private companion object {
        const val CHUNK = 1 shl 16
        const val BUFFER = 8 * 1024
        const val SETTLE_MILLIS = 300L
        const val WAIT_MILLIS = 2_000L
    }
}
