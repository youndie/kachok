package io.github.youndie.kachok.cli.mcp

import io.github.youndie.kachok.cli.serve.McpOptions
import io.github.youndie.kachok.control.SingleInstance
import io.github.youndie.kachok.control.configDirectory
import io.github.youndie.kachok.control.mcp.McpKeeper
import io.github.youndie.kachok.control.mcp.McpServer
import io.github.youndie.kachok.control.store.StoredTorrentsKeeper
import io.github.youndie.kachok.control.store.loadStoredTorrents
import io.github.youndie.kachok.control.store.reopenStored
import io.github.youndie.kachok.engine.io.EngineDispatchers
import io.github.youndie.kachok.engine.runtime.RuntimeOptions
import io.github.youndie.kachok.engine.runtime.SetOptions
import io.github.youndie.kachok.engine.runtime.TorrentSet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Flushable
import java.io.InputStream
import java.nio.file.Path
import kotlin.concurrent.thread

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
        // Nobody to attach to. Unless a second engine was asked for by name, this process becomes
        // the machine's engine: it takes the lock, so later agents attach to it, and it keeps the
        // torrent list, so a restart does not forget what it was doing (B-136).
        if (!options.standalone) {
            val instance = SingleInstance.claim(config, emptyList())
            if (instance != null) return engine(options, input, out, err, instance, config.resolve(TORRENTS))
            // Somebody holds the lock and would not take an agent: a window still building its
            // engine. It will in a moment, and attaching is better than a second engine.
            repeat(ATTACH_RETRIES) {
                Thread.sleep(ATTACH_RETRY_MILLIS)
                SingleInstance.attach(config)?.let { relay ->
                    relay.use { it.pump(input, out) }
                    return EXIT_OK
                }
            }
            err.appendLine("kachok: a client holds the lock but has no engine; running one here that will not remember")
        }
        return engine(options, input, out, err, instance = null, store = null)
    }

    /**
     * The engine in this process.
     *
     * With an [instance], it is the machine's: later `kachok mcp` processes attach to it, the list
     * is read from [store] at the start and written as agents change it, and a window that asks for
     * the engine gets it — this process stops its torrents, lets go of the lock and exits, and the
     * window opens the same list. Without one it is the second engine `--standalone` asks for,
     * which remembers nothing, because two engines sharing one list would open the same files twice.
     */
    private fun engine(
        options: McpOptions,
        input: InputStream,
        out: Appendable,
        err: Appendable,
        instance: SingleInstance?,
        store: Path?,
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
            val keeper = store?.let { StoredTorrentsKeeper(it) } ?: McpKeeper.NOTHING

            fun server(write: (String) -> Unit) =
                McpServer(
                    set,
                    scope,
                    options.directory,
                    dispatchers,
                    keeper = keeper,
                    extraTrackers = options.extraTrackers,
                    write = write,
                )
            val server =
                server { frame ->
                    // The frame and its newline in one append, then a flush: a client reads a line at
                    // a time and a frame that sits in a buffer is a tool call that never answers.
                    out.append(frame).append('\n')
                    (out as? Flushable)?.flush()
                }
            val yielded = CompletableDeferred<Unit>()
            instance?.agents = SingleInstance.McpSessions { write -> server(write) }
            instance?.onYield = { yielded.complete(Unit) }
            // In the background, like the window: opening a torrent checks its files, and an agent
            // asking `list_torrents` must not wait for a hundred gigabytes to be looked at.
            store?.let { directory -> scope.launch { reopen(set, scope, directory, err) } }
            // Stderr, never stdout: stdout is the protocol's.
            err.appendLine(
                "kachok: mcp server on stdio, peers on port ${set.listenPort}, saving to ${options.directory}" +
                    if (store != null) ", remembering in $store" else "",
            )
            try {
                // A blocking read, on a daemon thread of its own. **Not on the engine's executor**,
                // which is where it used to be: closing the executor waits for its threads, and a
                // read of stdin cannot be interrupted, so an engine yielding to a window with its
                // agent still connected never finished closing (B-136).
                val reading = CompletableDeferred<Unit>()
                thread(isDaemon = true, name = "kachok-mcp-stdin") {
                    try {
                        input.bufferedReader().useLines { lines ->
                            lines.forEach { line -> if (line.isNotBlank()) server.receive(line) }
                        }
                        // Stdin closing means the agent is leaving, not that it is owed nothing: the
                        // answer to the last `tools/call` is still on its way from the engine's
                        // threads, and the engine is stopped in the `finally` below.
                        server.finish(SingleInstance.GOODBYE_MILLIS)
                    } finally {
                        reading.complete(Unit)
                    }
                }
                // Whichever comes first: the agent leaving, or a window asking for the engine. On a
                // yield the read is left behind, and the process exits straight after this returns.
                select {
                    reading.onAwait {}
                    yielded.onAwait {}
                }
                EXIT_OK
            } finally {
                // Stopped before the set closes, so every torrent writes its record: the next engine
                // — this command again, or the window — opens the list and trusts them.
                withTimeoutOrNull(SingleInstance.GOODBYE_MILLIS) {
                    set.torrents.forEach { it.stop() }
                    set.torrents.forEach { it.awaitStopped() }
                }
                set.close()
                instance?.close()
                scope.cancel()
                dispatchers.close()
            }
        }

    /**
     * Every torrent the list remembers, opened the way it was left — and the ones that cannot be
     * opened yet, tried again until they can (B-138).
     */
    private suspend fun reopen(
        set: TorrentSet,
        scope: CoroutineScope,
        store: Path,
        err: Appendable,
    ) {
        val stored = loadStoredTorrents(store)
        stored.filter { it.metainfo == null }.forEach { err.appendLine("kachok: ${it.name} ${it.problem}") }
        reopenStored(
            stored,
            store,
            open = { entry, metainfo ->
                val runtime =
                    set.add(
                        metainfo,
                        RuntimeOptions(
                            directory = Path.of(entry.directory),
                            unwantedFiles = entry.unwanted,
                            highFiles = entry.high,
                            sequential = entry.sequential,
                        ),
                    )
                try {
                    runtime.restore()
                    runtime.start(scope, paused = entry.paused)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failed: Exception) {
                    // Nothing left behind: an opened-but-unchecked torrent would turn every retry
                    // into "this set already has it".
                    set.remove(runtime)
                    throw failed
                }
            },
            report = { err.appendLine("kachok: $it") },
        )
    }

    const val EXIT_OK: Int = 0

    /** Where the list lives inside the configuration directory: the window's own (B-81). */
    private const val TORRENTS = "torrents"
    private const val ATTACH_RETRIES = 20
    private const val ATTACH_RETRY_MILLIS = 500L
}
