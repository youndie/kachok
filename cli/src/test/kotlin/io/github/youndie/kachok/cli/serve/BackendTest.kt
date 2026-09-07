package io.github.youndie.kachok.cli.serve

import io.github.youndie.kachok.engine.io.EngineDispatchers
import io.github.youndie.kachok.engine.runtime.TorrentSet
import io.github.youndie.kachok.swarm.LocalSwarm
import io.github.youndie.kachok.wire.Reply
import io.github.youndie.kachok.wire.Request
import io.github.youndie.kachok.wire.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A real download, driven from outside the process
 * ([B-40](../../../../../../../../docs/backlog/B-40-wasmjs-ui-is-a-client-of-the-headless-engine.md)).
 *
 * The claim this item rests on is that the engine's `StateFlow` plus command channel can be put
 * behind a socket without redesign, and the only way to find out is to do it against the same swarm
 * the other two surfaces are tested on: a tracker and a seeding peer on loopback, a real file on a
 * real disk, and a client that shares nothing with the backend except JSON.
 *
 * The client is the JDK's own `java.net.http.WebSocket` — an implementation nobody here wrote.
 */
class BackendTest {
    private val root: Path = Files.createTempDirectory("kachok-backend")
    private var swarm: LocalSwarm? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newHttpClient()

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() {
        swarm?.close()
        root.deleteRecursively()
    }

    private class Listening : WebSocket.Listener {
        val replies = LinkedBlockingQueue<String>()
        private val partial = StringBuilder()

        override fun onText(
            socket: WebSocket,
            data: CharSequence,
            last: Boolean,
        ): CompletionStage<*>? {
            partial.append(data)
            if (last) {
                replies += partial.toString()
                partial.clear()
            }
            socket.request(1)
            return null
        }
    }

    /** Waits for a snapshot that answers [until], so a test never asserts on the first tick it sees. */
    private fun Listening.awaitSnapshot(
        seconds: Long = 30,
        until: (Snapshot) -> Boolean,
    ): Snapshot {
        val deadline = System.nanoTime() + seconds * 1_000_000_000
        while (System.nanoTime() < deadline) {
            val text = replies.poll(seconds, TimeUnit.SECONDS) ?: break
            val reply = json.decodeFromString<Reply>(text)
            if (reply is Reply.State && until(reply.snapshot)) return reply.snapshot
        }
        throw AssertionError("no snapshot answered the question within ${seconds}s")
    }

    private fun Listening.awaitRefusal(): Reply.Refused {
        val deadline = System.nanoTime() + 10_000_000_000
        while (System.nanoTime() < deadline) {
            val text = replies.poll(10, TimeUnit.SECONDS) ?: break
            val reply = json.decodeFromString<Reply>(text)
            if (reply is Reply.Refused) return reply
        }
        throw AssertionError("nothing was refused")
    }

    private fun <T> serving(
        blockMillis: Long = 20,
        body: (LocalSwarm, Backend, Listening, WebSocket) -> T,
    ): T =
        runBlocking {
            val local = LocalSwarm.start(delayPerBlockMillis = blockMillis).also { swarm = it }
            val dispatchers = EngineDispatchers()
            val job = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + dispatchers.io + job)
            val set = TorrentSet(dispatchers, scope)
            // A fifth of a second rather than a second: this is a test, and the tick is the only
            // thing between an assertion and waiting for it.
            val backend = Backend(set, scope, root, tick = 200.milliseconds)
            try {
                backend.start()
                val listening = Listening()
                val socket =
                    http
                        .newWebSocketBuilder()
                        .buildAsync(URI.create("ws://127.0.0.1:${backend.port}"), listening)
                        .get(10, TimeUnit.SECONDS)
                body(local, backend, listening, socket)
            } finally {
                backend.close()
                set.close()
                job.cancelAndJoin()
                dispatchers.close()
            }
        }

    private fun WebSocket.send(request: Request) {
        sendText(json.encodeToString<Request>(request), true).get(10, TimeUnit.SECONDS)
    }

    @Test
    fun aTorrentSentOverTheSocketDownloadsAndItsProgressComesBack(): Unit =
        serving { local, _, listening, socket ->
            socket.send(Request.AddTorrent(Base64.getEncoder().encodeToString(local.torrent)))

            val started = listening.awaitSnapshot { it.torrents.isNotEmpty() }
            assertEquals("payload.bin", started.torrents.single().name)
            assertEquals(local.content.size.toLong(), started.torrents.single().totalLength)

            val finished = listening.awaitSnapshot { it.torrents.singleOrNull()?.isComplete == true }
            assertEquals(0L, finished.torrents.single().left)
            assertTrue(local.served > 0, "the bytes came off the wire, not off the disk")
            assertContentEqualsOnDisk(local)
        }

    private fun assertContentEqualsOnDisk(local: LocalSwarm) {
        val written = Files.readAllBytes(root.resolve("payload.bin"))
        assertTrue(written.contentEquals(local.content), "the file on the disk is not the torrent's")
    }

    /** The first thing a client gets, so a fresh window is not blank for a tick. */
    @Test
    fun aClientIsSentTheStateBeforeItAsksForAnything(): Unit =
        serving { _, backend, listening, _ ->
            val first = listening.awaitSnapshot { true }
            assertTrue(first.listenPort > 0, "the snapshot did not say which port the peers use")
            assertEquals(null, first.dhtNodes, "a DHT that was never asked for counted nodes")
            assertNotNull(backend.port)
        }

    /** A command from a client reaches the engine, which is the other half of the wire. */
    @Test
    fun pauseAndResumeSentOverTheSocketReachTheSession(): Unit =
        serving { local, _, listening, socket ->
            socket.send(Request.AddTorrent(Base64.getEncoder().encodeToString(local.torrent)))
            val hash =
                listening
                    .awaitSnapshot { it.torrents.isNotEmpty() }
                    .torrents
                    .single()
                    .infoHash

            socket.send(Request.Pause(hash))
            assertTrue(
                listening
                    .awaitSnapshot { it.torrents.single().paused }
                    .torrents
                    .single()
                    .paused,
            )

            socket.send(Request.Resume(hash))
            assertTrue(
                !listening
                    .awaitSnapshot { !it.torrents.single().paused }
                    .torrents
                    .single()
                    .paused,
            )
        }

    @Test
    fun removingATorrentOverTheSocketTakesItOutOfTheList(): Unit =
        serving { local, _, listening, socket ->
            socket.send(Request.AddTorrent(Base64.getEncoder().encodeToString(local.torrent)))
            val hash =
                listening
                    .awaitSnapshot { it.torrents.isNotEmpty() }
                    .torrents
                    .single()
                    .infoHash

            socket.send(Request.Remove(hash))
            assertTrue(listening.awaitSnapshot { it.torrents.isEmpty() }.torrents.isEmpty())
        }

    // ---- what the backend will not do, and says so

    /**
     * A request that fails is answered, because a client told nothing draws a button that did nothing.
     */
    @Test
    fun aCommandForATorrentThatIsNotHereIsRefusedInWords(): Unit =
        serving { _, _, listening, socket ->
            socket.send(Request.Pause("0".repeat(40)))
            assertContains(listening.awaitRefusal().why, "no torrent here")
        }

    @Test
    fun aTorrentThatIsNotATorrentIsRefusedInWords(): Unit =
        serving { _, _, listening, socket ->
            socket.send(Request.AddTorrent(Base64.getEncoder().encodeToString("not a torrent".encodeToByteArray())))
            assertContains(listening.awaitRefusal().why, "not a usable torrent")
        }

    @Test
    fun somethingThatIsNotBase64IsRefusedInWords() =
        serving { _, _, listening, socket ->
            socket.send(Request.AddTorrent("this is not base64!!"))
            assertContains(listening.awaitRefusal().why, "base64")
        }

    /** Malformed JSON from a client must not take the backend down with it. */
    @Test
    fun nonsenseOnTheSocketIsRefusedAndTheBackendKeepsRunning(): Unit =
        serving { local, _, listening, socket ->
            socket.sendText("{not json", true).get(10, TimeUnit.SECONDS)
            assertContains(listening.awaitRefusal().why, "not a request")

            socket.send(Request.AddTorrent(Base64.getEncoder().encodeToString(local.torrent)))
            assertEquals(1, listening.awaitSnapshot { it.torrents.isNotEmpty() }.torrents.size)
        }

    /** The set refuses a torrent it already has, and the reason reaches the client rather than a log. */
    @Test
    fun addingTheSameTorrentTwiceIsRefusedInWords(): Unit =
        serving { local, _, listening, socket ->
            val base64 = Base64.getEncoder().encodeToString(local.torrent)
            socket.send(Request.AddTorrent(base64))
            listening.awaitSnapshot { it.torrents.isNotEmpty() }
            socket.send(Request.AddTorrent(base64))
            assertContains(listening.awaitRefusal().why, "already has")
        }
}
