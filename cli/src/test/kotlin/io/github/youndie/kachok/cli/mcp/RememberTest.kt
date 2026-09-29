package io.github.youndie.kachok.cli.mcp

import io.github.youndie.kachok.cli.serve.McpOptions
import io.github.youndie.kachok.control.SingleInstance
import io.github.youndie.kachok.control.store.loadStoredTorrents
import io.github.youndie.kachok.engine.hex
import io.github.youndie.kachok.engine.metainfo.MetainfoParser
import io.github.youndie.kachok.swarm.LocalSwarm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * `kachok mcp` with no window running is the machine's engine, and it remembers
 * ([B-136](../../../../../../../../docs/backlog/B-136-one-engine-that-remembers.md)).
 *
 * Each case starts the command the way `main` does — `Mcp.run` on a pipe, against a temporary
 * configuration directory — and ends it the way an agent runtime does, by closing the pipe. The
 * claims are the three the item makes: what was added is there after a restart; a second command
 * attaches to the first rather than building an engine of its own; and a window asking for the
 * engine is given it, with the list on the disk for the window to open.
 */
class RememberTest {
    private val root: Path = Files.createTempDirectory("kachok-remember")
    private val config: Path = Files.createTempDirectory("kachok-remember-config")
    private val local: LocalSwarm = LocalSwarm.start()
    private val hash = MetainfoParser.parse(local.torrent).infoHash.hex()

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() {
        local.close()
        root.deleteRecursively()
        config.deleteRecursively()
    }

    /** One running `kachok mcp`: its stdin, what it wrote, and how it ended. */
    private inner class Running {
        private val toServer = PipedOutputStream()

        // Connected here, before the server thread starts: made inside it, the first request could
        // be written before the pipe existed and fail with "Pipe not connected".
        private val fromAgent = PipedInputStream(toServer)
        private val lines = LinkedBlockingQueue<String>()
        private val diagnostics = StringBuilder()
        private val json = Json { ignoreUnknownKeys = true }
        private var next = 0

        @Volatile
        var exit: Int = -1
            private set

        private val out =
            object : Appendable {
                private val buffer = StringBuilder()

                override fun append(value: CharSequence?): Appendable = apply { value?.forEach { append(it) } }

                override fun append(
                    value: CharSequence?,
                    start: Int,
                    end: Int,
                ): Appendable = apply { value?.subSequence(start, end)?.forEach { append(it) } }

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
            }

        private val server =
            thread(name = "kachok-mcp-remember") {
                exit =
                    Mcp.run(
                        McpOptions(directory = root, peerPort = null, dht = false),
                        fromAgent,
                        out,
                        diagnostics,
                        config,
                    )
            }

        fun call(
            tool: String,
            arguments: String = "{}",
        ): String {
            val id = ++next
            toServer.write(
                """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}"""
                    .toByteArray(),
            )
            toServer.write('\n'.code)
            toServer.flush()
            val reply = reply(id)
            val result =
                assertNotNull(reply["result"]?.jsonObject, "not answered with a result: $reply; stderr: $diagnostics")
            return result["content"]!!.jsonArray.joinToString("\n") { it.jsonObject["text"]!!.jsonPrimitive.content }
        }

        private fun reply(id: Int): JsonObject {
            val deadline = System.nanoTime() + WAIT_SECONDS * 1_000_000_000
            while (System.nanoTime() < deadline) {
                val line = lines.poll(WAIT_SECONDS, TimeUnit.SECONDS) ?: break
                val frame = json.parseToJsonElement(line).jsonObject
                if (frame["id"]?.jsonPrimitive?.content == id.toString()) return frame
            }
            throw AssertionError("no reply to request $id within ${WAIT_SECONDS}s; stderr: $diagnostics")
        }

        /** Closes the pipe, which is how an agent says goodbye, and waits for the process to end. */
        fun stop() {
            toServer.close()
            awaitExit()
        }

        fun awaitExit() {
            server.join(JOIN_MILLIS)
            assertFalse(server.isAlive, "kachok mcp did not exit; stderr: $diagnostics")
            assertEquals(Mcp.EXIT_OK, exit, "kachok mcp did not exit cleanly; stderr: $diagnostics")
        }
    }

    private fun addArguments(): String {
        val file = root.resolve("payload.torrent").also { Files.write(it, local.torrent) }
        return """{"source":${JsonPrimitive(file.toString())},"directory":${JsonPrimitive(root.toString())}}"""
    }

    @Test
    fun whatWasAddedIsThereAfterARestart() {
        val first = Running()
        assertContains(first.call("add_torrent", addArguments()), hash)
        first.stop()

        val second = Running()
        try {
            val deadline = System.nanoTime() + WAIT_SECONDS * 1_000_000_000
            var listed = second.call("list_torrents")
            // Reopened in the background, so it may take a moment to be listed.
            while (!listed.contains(hash) && System.nanoTime() < deadline) {
                Thread.sleep(POLL_MILLIS)
                listed = second.call("list_torrents")
            }
            assertContains(listed, hash, message = "the restarted server forgot the torrent")
        } finally {
            second.stop()
        }
    }

    @Test
    fun aSecondServerAttachesToTheFirstRatherThanBuildingAnEngine() {
        val first = Running()
        try {
            // Answered means it is up, and it took the lock before it built its engine.
            first.call("list_torrents")
            val second = Running()
            try {
                assertContains(second.call("add_torrent", addArguments()), hash)
            } finally {
                second.stop()
            }
            assertContains(first.call("list_torrents"), hash, message = "the second server built an engine of its own")
        } finally {
            first.stop()
        }
    }

    @Test
    fun aWindowIsGivenTheEngineAndTheListIsLeftForIt() {
        val headless = Running()
        assertContains(headless.call("add_torrent", addArguments()), hash)

        val window = SingleInstance.claimForWindow(config, emptyList())
        try {
            assertNotNull(window, "the window was handed to the headless engine instead of getting it")
            headless.awaitExit()
            assertEquals(
                listOf(hash),
                loadStoredTorrents(config.resolve("torrents")).map { it.infoHash },
                "the list the window opens does not have the torrent",
            )
        } finally {
            window?.close()
        }
    }

    private companion object {
        const val WAIT_SECONDS = 30L
        const val JOIN_MILLIS = 30_000L
        const val POLL_MILLIS = 200L
    }
}
