package io.github.youndie.kachok.ui

import io.github.youndie.kachok.engine.hex
import io.github.youndie.kachok.swarm.LocalSwarm
import java.io.BufferedReader
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readLines
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.fail

/**
 * **The shipped window downloads a torrent** — the packaged launcher, its trimmed runtime and its
 * shrunk classes, driven the way an agent drives it.
 *
 * `checkDistributable` asks the image whether the paths the engine takes are *there*; it cannot
 * open a window, and the window is most of what ProGuard has to get right (B-155). A class the
 * shrinker removed because nothing names it is a window that does not appear, a screen that throws
 * on its first frame, or an MCP answer that cannot be written — none of which a preflight that exits
 * before `application {}` would see. So this starts the real launcher, waits for the engine the
 * composition builds to answer on the single-instance lock, and asks its MCP server to fetch a
 * torrent from a seed on loopback: the window, the engine, `:control` and its serialisation, all
 * in the image that ships.
 *
 * Run by `:ui:checkDownload` and by nothing else, because it needs a built image and a display; the
 * ordinary test task excludes it. The client gets a home of its own — through `JAVA_TOOL_OPTIONS`
 * on macOS, `XDG_CONFIG_HOME` on Linux, `APPDATA` on Windows, which are what `configDirectory` reads
 * — so a client the developer has running is neither found nor disturbed.
 */
class DistributionDownloadTest {
    @Test
    fun theShippedWindowDownloadsATorrentItIsHandedOverMcp() {
        val launcher =
            System.getProperty(LAUNCHER) ?: fail("$LAUNCHER is not set; this test is run by :ui:checkDownload")
        val home = Files.createTempDirectory("kachok-distribution")
        val downloads = home.resolve("Downloads").createDirectories()
        // A megabyte in 64 pieces: enough that the download is a transfer and not one round trip.
        val swarm = LocalSwarm.start(content = LocalSwarm.content(1 shl 20))
        try {
            val torrent = home.resolve("smoke.torrent").also { it.writeBytes(swarm.torrent) }
            val hash = swarm.metainfo.infoHash.hex()
            val log = home.resolve("window.log")
            val window =
                ProcessBuilder(launcher)
                    .apply {
                        environment()["JAVA_TOOL_OPTIONS"] =
                            listOfNotNull("-Duser.home=$home", System.getProperty(JVM_OPTIONS)).joinToString(" ")
                        environment()["XDG_CONFIG_HOME"] = home.resolve(".config").toString()
                        environment()["APPDATA"] = home.resolve("AppData").toString()
                        redirectErrorStream(true)
                        redirectOutput(log.toFile())
                    }.start()
            try {
                val answers =
                    mcp(configDirectoryUnder(home), window, log) { send, receive ->
                        send(1, "initialize", """{"protocolVersion":"2025-06-18","capabilities":{}}""")
                        receive(1)
                        send(
                            2,
                            "tools/call",
                            """{"name":"add_torrent","arguments":""" +
                                """{"source":${json(torrent)},"directory":${json(downloads)}}}""",
                        )
                        val added = receive(2)
                        send(
                            3,
                            "tools/call",
                            """{"name":"wait_for_completion","arguments":""" +
                                """{"info_hash":"$hash","timeout_seconds":$WAIT_SECONDS}}""",
                        )
                        listOf(added, receive(3))
                    }
                assertContains(
                    answers[0],
                    "Added ${swarm.metainfo.name}",
                    message = "add_torrent answered: ${answers[0]}",
                )
                assertContains(answers[1], "has finished", message = "wait_for_completion answered: ${answers[1]}")
                assertContentEquals(swarm.content, downloads.resolve(swarm.metainfo.name).readBytes())
            } finally {
                window.destroy()
                if (!window.waitFor(STOP_SECONDS, TimeUnit.SECONDS)) window.destroyForcibly()
            }
        } finally {
            swarm.close()
        }
    }

    /**
     * Connects to the window's lock once its engine is up, says `mcp`, and hands [talk] a way to
     * send a request and to read the reply with a given id.
     *
     * The window binds the lock in `main` and builds its engine on the first composition; between
     * the two it answers `kachok/no-engine`, which is the difference between "not yet" and "nobody
     * is listening" — so that answer is retried and a dead process is not.
     */
    private fun <T> mcp(
        config: Path,
        window: Process,
        log: Path,
        talk: (send: (Int, String, String) -> Unit, receive: (Int) -> String) -> T,
    ): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(START_SECONDS)
        val lock = config.resolve("instance")
        while (System.nanoTime() < deadline) {
            if (!window.isAlive) fail("the window exited with ${window.exitValue()}:\n${log.toFile().readText()}")
            if (lock.exists()) {
                val (port, secret) = lock.readLines()
                Socket(InetAddress.getLoopbackAddress(), port.toInt()).use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val out = socket.getOutputStream()
                    out.write("$secret\nmcp\n".toByteArray())
                    out.flush()
                    if (reader.readLine() == "kachok" && reader.readLine() == "kachok/mcp") {
                        val send = { id: Int, method: String, params: String ->
                            out.write(
                                """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""".toByteArray(),
                            )
                            out.write("\n".toByteArray())
                            out.flush()
                        }
                        return talk(send) { id -> replyTo(id, reader) }
                    }
                }
            }
            Thread.sleep(POLL_MILLIS)
        }
        fail("the window's engine did not answer on its lock in ${START_SECONDS}s:\n${log.toFile().readText()}")
    }

    /** Frames until the one answering [id]; notifications the server sends in between are passed over. */
    private fun replyTo(
        id: Int,
        reader: BufferedReader,
    ): String {
        while (true) {
            val line = reader.readLine() ?: fail("the window closed the MCP connection before answering $id")
            if (line.contains(""""id":$id,""") || line.contains(""""id":$id}""")) return line
        }
    }

    /** `configDirectory()` for another home, which is what the launched window resolves. */
    private fun configDirectoryUnder(home: Path): Path {
        val os = System.getProperty("os.name").orEmpty()
        return when {
            os.startsWith("Mac") -> home.resolve("Library/Application Support/kachok")
            os.startsWith("Windows") -> home.resolve("AppData").resolve("kachok")
            else -> home.resolve(".config").resolve("kachok")
        }
    }

    /** A path as a JSON string: Windows' separators are backslashes, which JSON escapes. */
    private fun json(path: Path): String = "\"" + path.toAbsolutePath().toString().replace("\\", "\\\\") + "\""

    private companion object {
        const val LAUNCHER = "kachok.launcher"

        /** More for the window's VM — how `:ui:aotCache` trains the cache through this run. */
        const val JVM_OPTIONS = "kachok.jvmOptions"
        const val START_SECONDS = 90L
        const val WAIT_SECONDS = 60

        // Long enough for a training run: the VM writes its AOT cache on the way out, and that takes seconds.
        const val STOP_SECONDS = 120L
        const val POLL_MILLIS = 250L
    }
}
