package io.github.youndie.kachok.cli.mcp

import io.github.youndie.kachok.cli.serve.McpOptions
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

/**
 * `kachok mcp`: the engine on stdin/stdout, for an agent runtime that launched this process
 * ([B-108](../../../../../../../../docs/backlog/B-108-an-mcp-server-for-agents.md)).
 *
 * The transport is the pipe, and the pipe is the lifetime: the process runs until its stdin
 * closes, which is how an MCP client says goodbye, and then stops the engine the way `download`
 * does — tracker told, peers closed, records written.
 *
 * **Stdio and not the socket that already exists**, because the socket is guarded against pages
 * and by nothing against programs; a pipe is held by the one process that opened it, which is the
 * right shape for a tool an agent runtime spawns and is the shape every MCP client expects.
 */
internal object Mcp {
    fun run(
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
