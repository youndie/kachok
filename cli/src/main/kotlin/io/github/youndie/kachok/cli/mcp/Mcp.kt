package io.github.youndie.kachok.cli.mcp

import io.github.youndie.kachok.cli.serve.McpOptions
import io.github.youndie.kachok.control.SingleInstance
import io.github.youndie.kachok.control.configDirectory
import io.github.youndie.kachok.control.mcp.McpServer
import io.github.youndie.kachok.engine.io.EngineDispatchers
import io.github.youndie.kachok.engine.runtime.SetOptions
import io.github.youndie.kachok.engine.runtime.TorrentSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.Flushable
import java.io.InputStream
import java.nio.file.Path

/**
 * `kachok mcp`: the engine on stdin/stdout, for an agent runtime that launched this process
 * ([B-108](../../../../../../../../docs/backlog/B-108-an-mcp-server-for-agents.md)).
 *
 * The transport is the pipe, and the pipe is the lifetime: the process runs until its stdin
 * closes, which is how an MCP client says goodbye.
 *
 * **Stdio and not the socket that already exists**, because the socket is guarded against pages
 * and by nothing against programs; a pipe is held by the one process that opened it, which is the
 * right shape for a tool an agent runtime spawns and is the shape every MCP client expects.
 *
 * **What is underneath that pipe is one of two things**
 * ([B-117](../../../../../../../../docs/backlog/B-117-one-client-for-the-window-and-the-agent.md)).
 * If a client is already running on this machine — a window, usually — the frames are relayed into
 * *its* engine and its answers come back, so the agent and the person are looking at one torrent
 * list. If there is none, this process builds the engine itself and stops it when the pipe closes,
 * which is what a box with no desktop does and what this command did everywhere before B-117.
 * `--standalone` is that second path asked for by name.
 */
internal object Mcp {
    fun run(
        options: McpOptions,
        input: InputStream,
        out: Appendable,
        err: Appendable,
        /**
         * Where the running client's lock is looked for.
         *
         * A parameter with the real answer as its default, because a test that used the real one
         * would attach to whatever window the developer has open and add its fixtures to it.
         */
        config: Path = configDirectory(),
    ): Int {
        if (!options.standalone) {
            SingleInstance.attach(config)?.let { relay ->
                relay.use {
                    // **Named, not obeyed, and not a refusal either.** These belong to whoever owns
                    // the engine, and the agent runtime's configuration is one line that has to
                    // work whether or not a window happens to be open; a server that exited here
                    // would be one that works only on the days nobody started the client.
                    if (options.overridden.isNotEmpty()) {
                        err.appendLine(
                            "kachok: ${options.overridden.joinToString(", ")} named here, but the " +
                                "running client decides that; ignored",
                        )
                    }
                    err.appendLine("kachok: mcp server on stdio, attached to the client already running")
                    it.pump(input, out)
                }
                return EXIT_OK
            }
        }
        return standalone(options, input, out, err)
    }

    /** The engine in this process, stopped the way `download` stops it when the pipe closes. */
    private fun standalone(
        options: McpOptions,
        input: InputStream,
        out: Appendable,
        err: Appendable,
    ): Int =
        runBlocking {
            val dispatchers = EngineDispatchers()
            val scope = CoroutineScope(coroutineContext + dispatchers.io + SupervisorJob())
            val set =
                TorrentSet(
                    dispatchers = dispatchers,
                    scope = scope,
                    options = SetOptions(port = options.peerPort, dht = options.dht),
                    onBindFailure = { err.appendLine("kachok: $it") },
                )
            val server =
                McpServer(set, scope, options.directory, dispatchers) { frame ->
                    // The frame and its newline in one append, then a flush: a client reads a line at
                    // a time and a frame that sits in a buffer is a tool call that never answers.
                    out.append(frame).append('\n')
                    (out as? Flushable)?.flush()
                }
            // Stderr, never stdout: stdout is the protocol's.
            err.appendLine(
                "kachok: mcp server on stdio, peers on port ${set.listenPort}, saving to ${options.directory}",
            )
            try {
                // A blocking read, on the engine's own virtual threads rather than the caller's
                // thread — the read is the one thing here that blocks, and it must not hold the
                // coroutine that owns the scope.
                withContext(dispatchers.io) {
                    input.bufferedReader().useLines { lines ->
                        lines.forEach { line -> if (line.isNotBlank()) server.receive(line) }
                    }
                    // Stdin closing means the agent is leaving, not that it is owed nothing: the
                    // answer to the last `tools/call` is still on its way from the engine's
                    // threads, and the engine is stopped in the `finally` below.
                    server.finish(SingleInstance.GOODBYE_MILLIS)
                }
                EXIT_OK
            } finally {
                set.close()
                scope.cancel()
                dispatchers.close()
            }
        }

    const val EXIT_OK: Int = 0
}
