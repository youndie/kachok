package io.github.youndie.kachok.cli.mcp

import io.github.youndie.kachok.engine.io.EngineDispatchers
import io.github.youndie.kachok.engine.runtime.TorrentSet
import io.github.youndie.kachok.swarm.LocalSwarm
import io.github.youndie.kachok.wire.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The engine driven the way an agent drives it: JSON-RPC frames in, frames out, and a real
 * download in between ([B-108](../../../../../../../../docs/backlog/B-108-an-mcp-server-for-agents.md)).
 *
 * The same swarm the socket is tested on — a tracker and a seeding peer on loopback, a real file on
 * a real disk — because the claim is that this is a third adapter on one engine, and the only way
 * to find out is to download something through it. The frames are built by hand here and read back
 * as JSON, so that nothing in the test shares the server's idea of what a frame is.
 */
class McpServerTest {
    private val root: Path = Files.createTempDirectory("kachok-mcp")
    private var swarm: LocalSwarm? = null
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() {
        swarm?.close()
        root.deleteRecursively()
    }

    /** What the server wrote, one frame per line, and a way to wait for the answer to one id. */
    private class Frames {
        val lines = LinkedBlockingQueue<String>()
        private val json = Json { ignoreUnknownKeys = true }

        fun reply(
            id: Int,
            seconds: Long = 60,
        ): JsonObject {
            val deadline = System.nanoTime() + seconds * 1_000_000_000
            while (System.nanoTime() < deadline) {
                val line = lines.poll(seconds, TimeUnit.SECONDS) ?: break
                // Every line is a frame, and a frame is JSON-RPC: the one rule of the transport.
                val frame = json.parseToJsonElement(line).jsonObject
                assertEquals(
                    "2.0",
                    frame["jsonrpc"]?.jsonPrimitive?.content,
                    "a line on stdout was not a JSON-RPC frame: $line",
                )
                if (frame["id"]?.jsonPrimitive?.content == id.toString()) return frame
            }
            throw AssertionError("no reply to request $id within ${seconds}s")
        }
    }

    private class Driver(
        val server: McpServer,
        val frames: Frames,
    ) {
        private var next = 0

        fun request(
            method: String,
            params: String = "{}",
        ): Int {
            val id = ++next
            server.receive("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")
            return id
        }

        fun notify(method: String) {
            server.receive("""{"jsonrpc":"2.0","method":"$method"}""")
        }

        /** A tool call's text and whether it was refused. */
        fun call(
            tool: String,
            arguments: String = "{}",
            seconds: Long = 60,
        ): Pair<String, Boolean> {
            val reply = frames.reply(request("tools/call", """{"name":"$tool","arguments":$arguments}"""), seconds)
            val result = assertNotNull(reply["result"]?.jsonObject, "the call was not answered with a result: $reply")
            val text =
                result["content"]!!
                    .jsonArray
                    .joinToString("\n") { it.jsonObject["text"]!!.jsonPrimitive.content }
            return text to (result["isError"]?.jsonPrimitive?.content == "true")
        }
    }

    private fun <T> serving(body: (LocalSwarm, Driver) -> T): T =
        runBlocking {
            val local = LocalSwarm.start(delayPerBlockMillis = 20).also { swarm = it }
            val dispatchers = EngineDispatchers()
            val job = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + dispatchers.io + job)
            val set = TorrentSet(dispatchers, scope)
            val frames = Frames()
            val server = McpServer(set, scope, root, dispatchers) { frames.lines += it }
            try {
                body(local, Driver(server, frames))
            } finally {
                set.close()
                job.cancelAndJoin()
                dispatchers.close()
            }
        }

    private fun torrentFile(local: LocalSwarm): Path =
        root.resolve("payload.torrent").also {
            Files.write(it, local.torrent)
        }

    /** The handshake, and what the server says it can do. */
    @Test
    fun initializeAnnouncesToolsAndResources(): Unit =
        serving { _, driver ->
            val init =
                driver.frames.reply(
                    driver.request(
                        "initialize",
                        """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"0"}}""",
                    ),
                )
            val result = init["result"]!!.jsonObject
            assertEquals("2025-06-18", result["protocolVersion"]?.jsonPrimitive?.content)
            assertNotNull(result["capabilities"]!!.jsonObject["tools"])
            assertNotNull(result["capabilities"]!!.jsonObject["resources"])
            driver.notify("notifications/initialized")

            val tools =
                driver.frames
                    .reply(driver.request("tools/list"))["result"]!!
                    .jsonObject["tools"]!!
                    .jsonArray
            val names = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }
            listOf(
                "add_torrent",
                "list_torrents",
                "torrent_status",
                "wait_for_completion",
                "pause_torrent",
                "resume_torrent",
                "remove_torrent",
                "set_file_priority",
            ).forEach { assertContains(names, it) }
            tools.forEach { assertNotNull(it.jsonObject["inputSchema"], "a tool without a schema: $it") }
        }

    /**
     * The acceptance: "add this and tell me when it has finished", as two tool calls.
     *
     * The second one *blocks* until the torrent is done, which is what makes the sentence a plan an
     * agent can carry out without polling a sequence number.
     */
    @Test
    fun aTorrentAddedByPathDownloadsAndWaitForCompletionAnswersWhenItIsDone(): Unit =
        serving { local, driver ->
            val (added, refused) =
                driver.call(
                    "add_torrent",
                    """{"source":"${torrentFile(local)}","directory":"$root"}""",
                )
            assertFalse(refused, added)
            assertContains(added, "payload.bin")
            val hash = Regex("info_hash: ([0-9a-f]{40})").find(added)!!.groupValues[1]

            val (listed, _) = driver.call("list_torrents")
            assertContains(listed, hash)

            val (done, failed) = driver.call("wait_for_completion", """{"info_hash":"$hash","timeout_seconds":60}""")
            assertFalse(failed, done)
            assertContains(done, "has finished")
            assertTrue(local.served > 0, "the bytes came off the wire, not off the disk")
            assertTrue(
                Files.readAllBytes(root.resolve("payload.bin")).contentEquals(local.content),
                "the file on the disk is not the torrent's",
            )
        }

    /** A refusal is a sentence with the hash in it, and `isError`, not a code and not silence. */
    @Test
    fun aTorrentThatIsNotThereIsRefusedInWords(): Unit =
        serving { _, driver ->
            val (text, refused) = driver.call("pause_torrent", """{"info_hash":"${"0".repeat(40)}"}""")
            assertTrue(refused, "a missing torrent was not an error")
            assertContains(text, "no torrent here")
            assertContains(text, "0".repeat(40))

            val (missing, refusedToo) = driver.call("torrent_status")
            assertTrue(refusedToo)
            assertContains(missing, "info_hash")
        }

    /** A tier moves through the tool and shows in the status, which is B-106 reached from outside. */
    @Test
    fun aFilesTierIsChangedThroughTheToolAndShowsInTheStatus(): Unit =
        serving { local, driver ->
            val (added, _) = driver.call("add_torrent", """{"source":"${torrentFile(local)}","directory":"$root"}""")
            val hash = Regex("info_hash: ([0-9a-f]{40})").find(added)!!.groupValues[1]

            val (moved, refused) =
                driver.call(
                    "set_file_priority",
                    """{"info_hash":"$hash","file":0,"priority":"high"}""",
                )
            assertFalse(refused, moved)
            assertContains(moved, "high")

            val (status, _) = driver.call("torrent_status", """{"info_hash":"$hash"}""")
            assertContains(status, "[0] payload.bin")
            assertContains(status, "high")

            val (noSuchFile, refusedFile) =
                driver.call(
                    "set_file_priority",
                    """{"info_hash":"$hash","file":9,"priority":"skip"}""",
                )
            assertTrue(refusedFile, noSuchFile)
            assertContains(noSuchFile, "no file 9")
        }

    /** The resource is the socket's payload: the same class decodes it. */
    @Test
    fun theSnapshotResourceIsTheWiresOwnJson(): Unit =
        serving { _, driver ->
            val listed =
                driver.frames
                    .reply(
                        driver.request("resources/list"),
                    )["result"]!!
                    .jsonObject["resources"]!!
                    .jsonArray
            assertEquals(
                McpServer.SNAPSHOT_URI,
                listed
                    .single()
                    .jsonObject["uri"]
                    ?.jsonPrimitive
                    ?.content,
            )

            val read = driver.frames.reply(driver.request("resources/read", """{"uri":"${McpServer.SNAPSHOT_URI}"}"""))
            val text =
                read["result"]!!
                    .jsonObject["contents"]!!
                    .jsonArray
                    .single()
                    .jsonObject["text"]!!
                    .jsonPrimitive.content
            val snapshot = json.decodeFromString<Snapshot>(text)
            assertTrue(snapshot.listenPort > 0, "the snapshot did not say which port the peers use")
            assertEquals(0, snapshot.torrents.size)
        }

    /** Removing with the data takes the file with it; without, the file stays. */
    @Test
    fun removeWithDataDeletesWhatWasWritten(): Unit =
        serving { local, driver ->
            val (added, _) = driver.call("add_torrent", """{"source":"${torrentFile(local)}","directory":"$root"}""")
            val hash = Regex("info_hash: ([0-9a-f]{40})").find(added)!!.groupValues[1]
            driver.call("wait_for_completion", """{"info_hash":"$hash","timeout_seconds":60}""")
            assertTrue(Files.exists(root.resolve("payload.bin")))

            val (removed, refused) = driver.call("remove_torrent", """{"info_hash":"$hash","delete_data":true}""")
            assertFalse(refused, removed)
            assertFalse(Files.exists(root.resolve("payload.bin")), "the data was not deleted")
            val (listed, _) = driver.call("list_torrents")
            assertContains(listed, "No torrents")
        }

    /** The one rule of the transport: an unknown method is an error frame, and a bad line is too. */
    @Test
    fun whatIsNotUnderstoodIsAnsweredWithAnErrorFrameNotSilence(): Unit =
        serving { _, driver ->
            val unknown = driver.frames.reply(driver.request("tools/dance"))
            assertEquals(
                -32601,
                unknown["error"]!!
                    .jsonObject["code"]
                    ?.jsonPrimitive
                    ?.content
                    ?.toInt(),
            )

            driver.server.receive("this is not json")
            val line = driver.frames.lines.poll(10, TimeUnit.SECONDS)
            assertNotNull(line, "a bad line was met with silence")
            assertContains(line, "-32700")
        }
}
