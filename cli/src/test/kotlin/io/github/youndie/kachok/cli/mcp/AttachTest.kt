package io.github.youndie.kachok.cli.mcp

import io.github.youndie.kachok.cli.serve.McpOptions
import io.github.youndie.kachok.control.SingleInstance
import io.github.youndie.kachok.control.mcp.McpServer
import io.github.youndie.kachok.engine.hex
import io.github.youndie.kachok.engine.io.EngineDispatchers
import io.github.youndie.kachok.engine.metainfo.MetainfoParser
import io.github.youndie.kachok.engine.runtime.RuntimeOptions
import io.github.youndie.kachok.engine.runtime.SetOptions
import io.github.youndie.kachok.engine.runtime.TorrentSet
import io.github.youndie.kachok.swarm.LocalSwarm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * One client for the window and the agent
 * ([B-117](../../../../../../../../docs/backlog/B-117-one-client-for-the-window-and-the-agent.md)).
 *
 * What is asserted is the thing the item is about and the thing no unit of it can show on its own:
 * that `kachok mcp`, given a client already running, drives **that** engine. So the test stands up
 * the two halves as two real things — a `SingleInstance` bound on a temporary configuration
 * directory with a `TorrentSet` behind it, and `Mcp.run` on a pipe, the same call `main` makes —
 * and asks the agent's side for a torrent only the window's side was ever told about.
 *
 * The frames are built by hand and read back as JSON: nothing here shares the server's idea of what
 * a frame is, which is the only way a transport test proves anything.
 */
class AttachTest {
    private val root: Path = Files.createTempDirectory("kachok-attach")
    private val config: Path = Files.createTempDirectory("kachok-attach-config")
    private var swarm: LocalSwarm? = null

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() {
        swarm?.close()
        root.deleteRecursively()
        config.deleteRecursively()
    }

    /** Whole lines the relay wrote, which is what an agent runtime reads off this process's stdout. */
    private class Frames : Appendable {
        val lines = LinkedBlockingQueue<String>()
        private val buffer = StringBuilder()
        private val json = Json { ignoreUnknownKeys = true }

        override fun append(value: CharSequence?): Appendable =
            apply {
                synchronized(buffer) { buffer.append(value) }
            }

        override fun append(
            value: CharSequence?,
            start: Int,
            end: Int,
        ): Appendable =
            apply {
                synchronized(buffer) { buffer.append(value, start, end) }
            }

        override fun append(value: Char): Appendable =
            apply {
                synchronized(buffer) {
                    if (value == '\n') {
                        lines += buffer.toString()
                        buffer.setLength(0)
                    } else {
                        buffer.append(value)
                    }
                }
            }

        /** The answer to one request, and every line on the way to it has to be a frame. */
        fun reply(
            id: Int,
            seconds: Long = 30,
        ): JsonObject {
            val deadline = System.nanoTime() + seconds * 1_000_000_000
            while (System.nanoTime() < deadline) {
                val line = lines.poll(seconds, TimeUnit.SECONDS) ?: break
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

    /** The agent's end of the pipe: frames in, ids out. */
    private class Agent(
        private val toServer: OutputStream,
        val frames: Frames,
    ) {
        private var next = 0

        fun request(
            method: String,
            params: String = "{}",
        ): Int {
            val id = ++next
            toServer.write("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""".toByteArray())
            toServer.write('\n'.code)
            toServer.flush()
            return id
        }

        fun call(
            tool: String,
            arguments: String = "{}",
        ): String {
            val reply = frames.reply(request("tools/call", """{"name":"$tool","arguments":$arguments}"""))
            val result = assertNotNull(reply["result"]?.jsonObject, "the call was not answered with a result: $reply")
            return result["content"]!!
                .jsonArray
                .joinToString("\n") { it.jsonObject["text"]!!.jsonPrimitive.content }
        }
    }

    /**
     * A window: an engine, the lock bound on [config], and MCP sessions registered on it — which is
     * `App.kt` in three lines, and deliberately not `App.kt` itself, because a test that opened a
     * Compose window would be testing the window.
     */
    private fun <T> windowRunning(body: (TorrentSet, LocalSwarm) -> T): T =
        runBlocking {
            val local = LocalSwarm.start().also { swarm = it }
            val dispatchers = EngineDispatchers()
            val job = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + dispatchers.io + job)
            val set = TorrentSet(dispatchers, scope, SetOptions(dht = false))
            val instance = assertNotNull(SingleInstance.claim(config, emptyList()), "the lock was not bound")
            instance.agents =
                SingleInstance.McpSessions { write -> McpServer(set, scope, root, dispatchers, write) }
            try {
                body(set, local)
            } finally {
                instance.close()
                set.close()
                job.cancelAndJoin()
                dispatchers.close()
            }
        }

    /** Runs `kachok mcp` against [config] on a thread, and gives the body the agent's end. */
    private fun <T> agentAttached(
        standalone: Boolean,
        body: (Agent) -> T,
    ): T {
        val toServer = PipedOutputStream()
        val serverIn = PipedInputStream(toServer)
        val frames = Frames()
        val diagnostics = StringBuilder()
        var exit = -1
        val server =
            thread(name = "kachok-mcp-under-test") {
                exit =
                    Mcp.run(
                        McpOptions(
                            directory = root,
                            peerPort = null,
                            dht = false,
                            standalone = standalone,
                        ),
                        serverIn,
                        frames,
                        diagnostics,
                        config,
                    )
            }
        try {
            return body(Agent(toServer, frames))
        } finally {
            // Stdin closing is how an MCP client says goodbye, and the one thing that ends either
            // path: the relay drops its socket, the standalone server stops its engine.
            toServer.close()
            server.join(JOIN_MILLIS)
            assertEquals(Mcp.EXIT_OK, exit, "the server did not exit cleanly; it said: $diagnostics")
        }
    }

    /**
     * The acceptance, and the whole item in one assertion: a torrent the *window* holds is in the
     * agent's `list_torrents`. Before B-117 this listed nothing, because the agent's process had
     * built an engine of its own and that engine had never been told about anything.
     */
    @Test
    fun theAgentListsTheTorrentsTheWindowHolds(): Unit =
        windowRunning { set, local ->
            val metainfo = MetainfoParser.parse(local.torrent)
            runBlocking { set.add(metainfo, RuntimeOptions(directory = root)) }

            agentAttached(standalone = false) { agent ->
                val initialize =
                    agent.frames.reply(
                        agent.request(
                            "initialize",
                            """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"0"}}""",
                        ),
                    )
                assertNotNull(initialize["result"]?.jsonObject, "the relayed handshake was not answered")

                val listed = agent.call("list_torrents")
                assertContains(listed, metainfo.infoHash.hex())
                assertContains(listed, metainfo.name)
            }
        }

    /**
     * And the other direction, which is what makes it one client rather than a reader: what the
     * agent adds is in the window's own set, by the time the tool has answered.
     */
    @Test
    fun whatTheAgentAddsIsInTheWindowsSet(): Unit =
        windowRunning { set, local ->
            val file = root.resolve("payload.torrent").also { Files.write(it, local.torrent) }
            val expected = MetainfoParser.parse(local.torrent).infoHash.hex()
            assertTrue(set.torrents.isEmpty(), "the window started with a torrent in it")

            agentAttached(standalone = false) { agent ->
                // Doubled, because the path goes inside a JSON string and Windows separators are
                // escapes there. A test that forgot this passed a torrent nobody could open.
                val source = file.toAbsolutePath().toString().replace("\\", "\\\\")
                val added = agent.call("add_torrent", """{"source":"$source"}""")
                assertContains(added, expected)
            }
            assertEquals(
                listOf(expected),
                set.torrents.map { it.metainfo.infoHash.hex() },
                "the agent's torrent went somewhere other than the running client",
            )
        }

    /**
     * `--standalone` is the way back to what this command did before B-117, and it has to work on a
     * machine where a window *is* running — which is the only machine on which the difference is
     * visible at all.
     */
    @Test
    fun standaloneIgnoresTheRunningClient(): Unit =
        windowRunning { set, local ->
            runBlocking { set.add(MetainfoParser.parse(local.torrent), RuntimeOptions(directory = root)) }

            agentAttached(standalone = true) { agent ->
                agent.frames.reply(
                    agent.request(
                        "initialize",
                        """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"0"}}""",
                    ),
                )
                val listed = agent.call("list_torrents")
                assertTrue(
                    !listed.contains(MetainfoParser.parse(local.torrent).infoHash.hex()),
                    "--standalone attached to the running client anyway: $listed",
                )
            }
        }

    private companion object {
        const val JOIN_MILLIS = 30_000L
    }
}
