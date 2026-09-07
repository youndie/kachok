package io.github.youndie.kachok.cli.serve

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import io.github.youndie.kachok.engine.io.EngineDispatchers
import io.github.youndie.kachok.engine.runtime.SetOptions
import io.github.youndie.kachok.engine.runtime.TorrentSet
import java.nio.file.Path

/**
 * The headless client as a backend: an engine with a socket on it and no window
 * ([B-40](../../../../../../../../docs/backlog/B-40-wasmjs-ui-is-a-client-of-the-headless-engine.md)).
 *
 * A browser cannot be a BitTorrent peer — no TCP, no UDP, research Risk 4 — so the browser build of
 * the UI is a client of *this*, and the desktop build runs the same engine in-process. One engine,
 * two front ends, and the difference between them is a socket.
 *
 * The socket is on loopback with no authentication, which is the owner's decision; the `--origin`
 * option is what makes "loopback" mean "not any page on the internet", and it is spelled out in
 * [WebSocketServer].
 */
internal object Serve {
    const val EXIT_FAILED: Int = 1

    fun run(
        options: ServeOptions,
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
            val backend =
                Backend(
                    set = set,
                    scope = scope,
                    directory = options.directory,
                    port = options.port,
                    allowedOrigins = options.allowedOrigins,
                )
            try {
                backend.start()
                out.appendLine("kachok: serving ws://127.0.0.1:${backend.port}")
                out.appendLine("kachok: peers on port ${set.listenPort}, saving to ${options.directory}")
                if (options.allowedOrigins.isEmpty()) {
                    out.appendLine(
                        "kachok: no --origin given, so no browser page may connect; " +
                            "non-browser clients still can",
                    )
                } else {
                    out.appendLine("kachok: pages may connect from ${options.allowedOrigins.joinToString(", ")}")
                }
                // Until it is killed. A backend has no completion condition — that is the whole
                // difference between it and `download`.
                awaitCancellation()
            } catch (failed: java.io.IOException) {
                err.appendLine("kachok: cannot serve: ${failed.message}")
                EXIT_FAILED
            } finally {
                backend.close()
                set.close()
                scope.cancel()
                dispatchers.close()
            }
        }
}

class ServeOptions(
    val directory: Path,
    /** The WebSocket port. Zero asks the operating system, and the line it prints says which. */
    val port: Int,
    val peerPort: Int?,
    val dht: Boolean,
    /**
     * Origins a *page* may connect from, and nothing else changes with it.
     *
     * Empty means no page at all, which is the safe default for a socket with no authentication:
     * somebody who wants the browser build says which address they are serving it from.
     */
    val allowedOrigins: Set<String>,
)
