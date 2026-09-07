package io.github.youndie.kachok.ui.session

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * One client, however many times a `.torrent` is double-clicked
 * ([B-84](../../../../../../../../docs/backlog/B-84-torrent-files-open-with-the-client.md)).
 *
 * Two processes would be two clients on one listening port and one download directory, and no
 * `TorrentSet` can see across a process boundary to refuse it. What is asserted here is the whole
 * contract: the second launch does not become a client, its paths reach the first, and every way
 * the handshake can go wrong ends with *somebody* being the client rather than nobody.
 *
 * Two claims in one JVM are the same test as two processes: neither knows about the other, and the
 * only thing they share is the lock file and the socket it names.
 */
class SingleInstanceTest {
    private val root: Path = Files.createTempDirectory("kachok-instance")
    private val open = mutableListOf<SingleInstance>()

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() {
        open.forEach { it.close() }
        root.deleteRecursively()
    }

    private fun claim(vararg paths: String): SingleInstance? =
        SingleInstance.claim(root, paths.map { Path.of(it) }).also { if (it != null) open += it }

    private fun await(channel: Channel<Path>): Path? =
        runBlocking { withTimeoutOrNull(5.seconds) { channel.receive() } }

    @Test
    fun theSecondLaunchHandsItsTorrentToTheFirstAndDoesNotBecomeAClient() {
        val first = assertNotNull(claim(), "the first launch has to be the client")
        assertNull(claim("/srv/one.torrent"), "the second launch became a second client")
        assertEquals(Path.of("/srv/one.torrent").toAbsolutePath(), await(first.opened))
    }

    /** Three double-clicks in a second are three torrents, not the last one. */
    @Test
    fun everyPathHandedOverArrives() {
        val first = assertNotNull(claim())
        assertNull(claim("/srv/one.torrent", "/srv/two.torrent"))
        assertNull(claim("/srv/three.torrent"))

        val arrived = listOf(await(first.opened), await(first.opened), await(first.opened))
        assertEquals(
            listOf("/srv/one.torrent", "/srv/two.torrent", "/srv/three.torrent").map { Path.of(it).toAbsolutePath() },
            arrived,
        )
    }

    /**
     * A lock file left by a client that was killed does not lock anybody out.
     *
     * This is the case a lock *file* cannot handle and a socket can: the file outlives the process,
     * and the only honest test of "is it still running" is trying to talk to it.
     */
    @Test
    fun aLockFileLeftByADeadClientIsTakenOver() {
        val dead = java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress()).also { it.close() }
        Files.write(root.resolve("instance"), listOf(dead.localPort.toString(), "0123456789abcdef"))

        val taken = assertNotNull(claim("/srv/one.torrent"), "a dead client's file locked out a live one")
        assertTrue(Files.readAllLines(root.resolve("instance")).first().toInt() != dead.localPort)
        // Its own path is not handed to itself: it is the argument this process already has.
        assertNull(runBlocking { withTimeoutOrNull(1.seconds) { taken.opened.receive() } })
    }

    /**
     * Something that is not this client holding the port in the file is not this client.
     *
     * A port number in a file two weeks old can belong to anything by now. Handing a path to
     * whatever answers and then exiting would lose the torrent silently, so the caller waits to be
     * told the right word first.
     */
    @Test
    fun aStrangerOnThePortIsNotMistakenForTheClient() {
        java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { stranger ->
            Thread {
                runCatching {
                    stranger.accept().use {
                        it.getOutputStream().write(
                            "nope\n".encodeToByteArray(),
                        )
                    }
                }
            }.apply { isDaemon = true }
                .start()
            Files.write(root.resolve("instance"), listOf(stranger.localPort.toString(), "0123456789abcdef"))

            assertNotNull(claim("/srv/one.torrent"), "a stranger on the port was taken for the client")
        }
    }

    /** The secret is the whole access control, so a caller without it is hung up on. */
    @Test
    fun aCallerWithTheWrongSecretIsRefused() {
        val first = assertNotNull(claim())
        val port = Files.readAllLines(root.resolve("instance")).first().toInt()

        val refused =
            try {
                Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                    socket.soTimeout = 2_000
                    socket.getOutputStream().apply {
                        write("not-the-secret\n/srv/one.torrent\n".encodeToByteArray())
                        flush()
                    }
                    socket.getInputStream().bufferedReader().readLine()
                }
            } catch (hungUp: IOException) {
                null
            }
        assertNull(refused, "the client answered a caller that did not know the secret")
        assertNull(
            runBlocking { withTimeoutOrNull(1.seconds) { first.opened.receive() } },
            "a path from an unauthenticated caller reached the window",
        )
    }

    /** Closing puts the machine back where it started, so the next launch is a first launch. */
    @Test
    fun closingTheClientReleasesTheLock() {
        val first = assertNotNull(claim())
        first.close()
        open.remove(first)

        assertTrue(!Files.exists(root.resolve("instance")), "the lock file outlived the client")
        assertNotNull(claim(), "the next launch could not become the client")
    }
}
