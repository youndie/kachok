package io.github.youndie.kachok.control.mcp

import io.github.youndie.kachok.control.onTheWire
import io.github.youndie.kachok.control.snapshot
import io.github.youndie.kachok.engine.hex
import io.github.youndie.kachok.engine.io.EngineDispatchers
import io.github.youndie.kachok.engine.metainfo.MagnetParser
import io.github.youndie.kachok.engine.metainfo.Metainfo
import io.github.youndie.kachok.engine.metainfo.MetainfoParser
import io.github.youndie.kachok.engine.runtime.RuntimeOptions
import io.github.youndie.kachok.engine.runtime.TorrentRuntime
import io.github.youndie.kachok.engine.runtime.TorrentSet
import io.github.youndie.kachok.engine.runtime.fetchMetainfo
import io.github.youndie.kachok.engine.session.FilePriority
import io.github.youndie.kachok.engine.session.SessionState
import io.github.youndie.kachok.wire.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/**
 * The engine as a Model Context Protocol server: JSON-RPC 2.0, one message per line, on a pipe an
 * agent runtime holds both ends of
 * ([B-108](../../../../../../../../docs/backlog/B-108-an-mcp-server-for-agents.md)).
 *
 * **A third protocol adapter on the same engine, not a second backend.** The command line and the
 * WebSocket already drive a `TorrentSet`; this drives the same one with the same calls — `add`,
 * `pause`, `remove`, `prioritise` — and reads state through the same `onTheWire()` the socket
 * sends, so the snapshot resource here is byte-for-byte the socket's payload. What is different is
 * the shape of the surface: an agent wants *"add this and wait until it is done"*, not a request
 * type and a sequence number to poll, so the tools are task-shaped and one of them blocks.
 *
 * **Every refusal is a sentence the agent can relay.** A tool that fails answers with `isError` and
 * words — which torrent, which path, what was wrong — rather than a code, because the thing on
 * the other end will show the words to a person and has nowhere to look up a code.
 *
 * **Stdout carries frames and nothing else.** One stray line of logging on stdout is a client that
 * disconnects with "invalid JSON"; every human-readable word goes to stderr. Frames are written
 * under one lock because tool calls finish on the engine's threads in whatever order they finish.
 */
public class McpServer(
    private val set: TorrentSet,
    private val scope: CoroutineScope,
    /** Where a torrent is saved when the tool call names nowhere. */
    private val directory: Path,
    private val dispatchers: EngineDispatchers,
    /** One frame, with no newline in it; the caller appends the newline and flushes. */
    private val write: (String) -> Unit,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val sequence = AtomicLong()
    private val writing = Any()

    /**
     * Where a tool call runs, and **not** where a torrent runs.
     *
     * A call belongs to the session that asked for it: when the agent goes, the answers it is owed
     * are the only thing left to finish, and [finish] waits for exactly these. A torrent is the
     * opposite — `runtime.start(scope)` and the magnet fetch stay on the caller's [scope], because
     * a download must outlive the conversation that started it, which is the whole point of
     * attaching to a running client.
     *
     * A supervisor, so one refused call does not take the session's other calls with it.
     */
    private val calls = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    /**
     * Waits for the calls already asked for to answer, then takes no more.
     *
     * The agent's pipe closing means it is leaving, not that it is owed nothing: a `tools/call` is
     * answered from the engine's threads, so a session torn down the moment its input ends loses
     * the answer to the last thing it was asked. Bounded, because this is politeness and not
     * correctness, and a call that will not finish must not hold a departing session open.
     *
     * Torrents are untouched. They were started on the caller's scope and go on downloading.
     */
    public fun finish(millis: Long) {
        runBlocking {
            withTimeoutOrNull(millis) {
                calls.coroutineContext.job
                    .children
                    .toList()
                    .joinAll()
            }
        }
        calls.cancel()
    }

    /**
     * One line from the client. A request gets exactly one reply — at once for the cheap methods,
     * from the session's own scope for a tool call — and a notification gets none.
     */
    public fun receive(line: String) {
        val message =
            try {
                json.parseToJsonElement(line).jsonObject
            } catch (unreadable: Exception) {
                // Deliberately broad: `kotlinx.serialization` throws several unrelated types for a
                // document that is not JSON, and a client's bad frame must not take the server down.
                send(error(JsonNull, PARSE_ERROR, "not JSON-RPC: ${unreadable.message}"))
                return
            }
        val id = message["id"]
        val method = message["method"]?.jsonPrimitive?.contentOrNull
        val params = message["params"] as? JsonObject
        if (method == null) {
            if (id != null) send(error(id, INVALID_REQUEST, "a request without a method"))
            return
        }
        // A notification: no id, no reply, whatever it says. `notifications/initialized` and
        // `notifications/cancelled` are the two a client sends; neither needs anything done.
        if (id == null) return

        when (method) {
            "initialize" -> {
                send(result(id, initialize(params)))
            }

            "ping" -> {
                send(result(id, JsonObject(emptyMap())))
            }

            "tools/list" -> {
                send(
                    result(id, buildJsonObject { putJsonArray("tools") { TOOLS.forEach { add(it.description()) } } }),
                )
            }

            "resources/list" -> {
                send(result(id, resources()))
            }

            "resources/read" -> {
                send(readResource(id, params))
            }

            "prompts/list" -> {
                send(result(id, buildJsonObject { putJsonArray("prompts") {} }))
            }

            "tools/call" -> {
                calls.launch { send(call(id, params)) }
            }

            else -> {
                send(error(id, METHOD_NOT_FOUND, "no such method: $method"))
            }
        }
    }

    private fun initialize(params: JsonObject?): JsonObject {
        // The client's version when it is one this speaks, else the newest this speaks: a client
        // that cannot take that answer disconnects, which the specification says it should.
        val asked = params?.get("protocolVersion")?.jsonPrimitive?.contentOrNull
        val version = if (asked != null && asked in PROTOCOL_VERSIONS) asked else PROTOCOL_VERSIONS.last()
        return buildJsonObject {
            put("protocolVersion", version)
            putJsonObject("capabilities") {
                putJsonObject("tools") {}
                putJsonObject("resources") {}
            }
            putJsonObject("serverInfo") {
                put("name", "kachok")
                put("version", VERSION)
            }
            put(
                "instructions",
                "kachok is a BitTorrent client. add_torrent takes a .torrent path or a magnet link and " +
                    "answers with the torrent's info hash; every other tool names a torrent by that hash. " +
                    "wait_for_completion blocks until the torrent is done, so 'download this and tell me " +
                    "when it has finished' is add_torrent followed by wait_for_completion.",
            )
        }
    }

    private fun resources(): JsonObject =
        buildJsonObject {
            putJsonArray("resources") {
                add(
                    buildJsonObject {
                        put("uri", SNAPSHOT_URI)
                        put("name", "snapshot")
                        put("description", "Every torrent this client holds, as the WebSocket sends it once a second.")
                        put("mimeType", "application/json")
                    },
                )
            }
        }

    private fun readResource(
        id: JsonElement,
        params: JsonObject?,
    ): JsonObject {
        val uri = params?.get("uri")?.jsonPrimitive?.contentOrNull
        if (uri !=
            SNAPSHOT_URI
        ) {
            return error(id, RESOURCE_NOT_FOUND, "no such resource: $uri; there is only $SNAPSHOT_URI")
        }
        val text = json.encodeToString(Snapshot.serializer(), set.snapshot(sequence.incrementAndGet()))
        return result(
            id,
            buildJsonObject {
                putJsonArray("contents") {
                    add(
                        buildJsonObject {
                            put("uri", SNAPSHOT_URI)
                            put("mimeType", "application/json")
                            put("text", text)
                        },
                    )
                }
            },
        )
    }

    private suspend fun call(
        id: JsonElement,
        params: JsonObject?,
    ): JsonObject {
        val name = params?.get("name")?.jsonPrimitive?.contentOrNull
        val tool = TOOLS.firstOrNull { it.name == name } ?: return error(id, INVALID_PARAMS, "no such tool: $name")
        val arguments = params?.get("arguments") as? JsonObject ?: JsonObject(emptyMap())
        val outcome =
            try {
                tool.run(this, arguments)
            } catch (refused: Refusal) {
                Outcome(refused.message.orEmpty(), isError = true)
            }
        return result(
            id,
            buildJsonObject {
                putJsonArray("content") {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", outcome.text)
                        },
                    )
                }
                put("isError", outcome.isError)
            },
        )
    }

    private class Outcome(
        val text: String,
        val isError: Boolean = false,
    )

    /** A tool's refusal, in words. The message is what the agent is shown. */
    private class Refusal(
        message: String,
    ) : Exception(message)

    private class Tool(
        val name: String,
        val about: String,
        val schema: JsonObject,
        val run: suspend McpServer.(JsonObject) -> Outcome,
    ) {
        fun description(): JsonObject =
            buildJsonObject {
                put("name", name)
                put("description", about)
                put("inputSchema", schema)
            }
    }

    // ---- the tools ----------------------------------------------------------------------------

    private suspend fun addTorrent(arguments: JsonObject): Outcome {
        val source =
            arguments.string("source")
                ?: throw Refusal("add_torrent needs `source`: a path to a .torrent file, or a magnet link")
        val saveTo = arguments.string("directory")?.let { Path.of(it) } ?: directory
        val high =
            arguments["high"]
                ?.let { it as? JsonArray }
                ?.mapNotNull { it.jsonPrimitive.intOrNull }
                ?.toSet()
                .orEmpty()
        val metainfo = if (source.startsWith(MAGNET_SCHEME)) fromMagnet(source) else fromFile(source)
        val runtime =
            try {
                set.add(metainfo, RuntimeOptions(directory = saveTo, highFiles = high))
            } catch (refused: IllegalArgumentException) {
                // The set refuses a torrent it already has, and one whose files another one owns.
                throw Refusal(refused.message.orEmpty())
            }
        runtime.restore()
        runtime.start(scope)
        return Outcome(
            "Added ${metainfo.name}: ${human(metainfo.totalLength)} in ${metainfo.pieceCount} pieces, " +
                "${metainfo.files.size} file(s), saving to $saveTo.\ninfo_hash: ${metainfo.infoHash.hex()}\n" +
                metainfo.files.withIndex().joinToString("\n") { (at, file) ->
                    "  [$at] ${file.path.joinToString("/")}  ${human(file.length)}" + if (at in high) "  (high)" else ""
                },
        )
    }

    private suspend fun fromMagnet(source: String): Metainfo {
        val link =
            try {
                MagnetParser.parse(source)
            } catch (malformed: IllegalArgumentException) {
                throw Refusal("not a usable magnet link: ${malformed.message}")
            }
        return try {
            fetchMetainfo(link = link, scope = scope, dispatchers = dispatchers, listenPort = set.listenPort)
        } catch (unavailable: IllegalArgumentException) {
            throw Refusal("the swarm did not hand over the torrent for that magnet: ${unavailable.message}")
        }
    }

    private fun fromFile(source: String): Metainfo {
        val bytes =
            try {
                Files.readAllBytes(Path.of(source))
            } catch (unreadable: IOException) {
                throw Refusal("cannot read $source: ${unreadable.message}")
            } catch (notAPath: java.nio.file.InvalidPathException) {
                throw Refusal("$source is not a path: ${notAPath.message}")
            }
        return try {
            MetainfoParser.parse(bytes)
        } catch (malformed: IllegalArgumentException) {
            throw Refusal("$source is not a usable torrent: ${malformed.message}")
        }
    }

    private fun listTorrents(): Outcome {
        val torrents = set.torrents
        if (torrents.isEmpty()) return Outcome("No torrents. add_torrent adds one.")
        return Outcome(
            torrents.joinToString("\n") { runtime ->
                val state = runtime.state.value.onTheWire()
                val percent = if (state.pieceCount == 0) 0 else state.completedPieces * PERCENT / state.pieceCount
                "${state.infoHash}  ${state.name}  ${stateWord(state.paused, state.isComplete, state.sessionError)}  " +
                    "$percent%  down ${human(state.downBytesPerSecond)}/s  up ${human(state.upBytesPerSecond)}/s  " +
                    "peers ${state.connectedPeers}/${state.knownPeers}"
            },
        )
    }

    private fun torrentStatus(arguments: JsonObject): Outcome {
        val runtime = runtimeFor(arguments)
        val state = runtime.state.value.onTheWire()
        return Outcome(
            buildString {
                appendLine(
                    "${state.name} (${state.infoHash}): ${stateWord(
                        state.paused,
                        state.isComplete,
                        state.sessionError,
                    )}",
                )
                appendLine(
                    "pieces ${state.completedPieces}/${state.pieceCount}, downloaded ${human(
                        state.downloaded,
                    )}, left ${human(state.left)}, uploaded ${human(state.uploaded)}",
                )
                appendLine(
                    "down ${human(
                        state.downBytesPerSecond,
                    )}/s, up ${human(
                        state.upBytesPerSecond,
                    )}/s, peers ${state.connectedPeers} connected of ${state.knownPeers} known, ${state.outstandingRequests} requests out",
                )
                state.trackerError?.let { appendLine("tracker: $it") }
                state.lastPeerError?.let { appendLine("last peer error: $it") }
                state.sessionError?.let { appendLine("session error: $it") }
                appendLine("saving to ${runtime.directory}")
                if (state.files.isNotEmpty()) {
                    appendLine("files:")
                    state.files.forEachIndexed { at, file ->
                        val percent = if (file.length == 0L) PERCENT else file.verifiedBytes * PERCENT / file.length
                        appendLine("  [$at] ${file.path}  ${human(file.length)}  $percent%  ${file.priority}")
                    }
                }
            }.trimEnd(),
        )
    }

    private suspend fun waitForCompletion(arguments: JsonObject): Outcome {
        val runtime = runtimeFor(arguments)
        val asked = arguments["timeout_seconds"]?.jsonPrimitive?.longOrNull ?: DEFAULT_WAIT_SECONDS
        val timeout = asked.coerceIn(1, MAX_WAIT_SECONDS).seconds
        val settled = withTimeoutOrNull(timeout) { runtime.state.first { it.isComplete || it.sessionError != null } }
        val state = runtime.state.value
        return when {
            settled?.isComplete == true -> {
                Outcome(
                    "${state.name} has finished: ${human(state.downloaded)} downloaded, saved to ${runtime.directory}.",
                )
            }

            settled != null -> {
                Outcome("${state.name} failed: ${settled.sessionError}", isError = true)
            }

            else -> {
                val percent = if (state.pieceCount == 0) 0 else state.completedPieces * PERCENT / state.pieceCount
                Outcome(
                    "${state.name} is still downloading after ${timeout.inWholeSeconds}s: $percent%, " +
                        "${state.connectedPeers} peers, ${human(state.left)} left" +
                        (state.trackerError?.let { "; tracker: $it" } ?: "") +
                        ". Call wait_for_completion again to keep waiting.",
                )
            }
        }
    }

    private suspend fun pause(arguments: JsonObject): Outcome {
        val runtime = runtimeFor(arguments)
        runtime.pause()
        return if (runtime.settled { it.paused }) {
            Outcome("Paused ${runtime.metainfo.name}.")
        } else {
            Outcome("Asked ${runtime.metainfo.name} to pause; the session has not confirmed it yet.")
        }
    }

    private suspend fun resume(arguments: JsonObject): Outcome {
        val runtime = runtimeFor(arguments)
        runtime.resume()
        return if (runtime.settled { !it.paused }) {
            Outcome("Resumed ${runtime.metainfo.name}.")
        } else {
            Outcome("Asked ${runtime.metainfo.name} to resume; the session has not confirmed it yet.")
        }
    }

    /**
     * Waits, briefly, for the session to say what a command just asked for.
     *
     * A command is a message to the session's own thread, and a tool that answered "paused" the
     * moment it was *sent* would be answering about the future. The next tool call — an agent's
     * `torrent_status` straight after `set_file_priority` — reads the state, and it must read
     * the change, not a race with it. Two seconds is longer than any command takes and shorter
     * than an agent's patience.
     */
    private suspend fun TorrentRuntime.settled(what: (SessionState) -> Boolean): Boolean =
        withTimeoutOrNull(SETTLE_SECONDS.seconds) { state.first(what) } != null

    private suspend fun remove(arguments: JsonObject): Outcome {
        val runtime = runtimeFor(arguments)
        val deleteData = arguments["delete_data"]?.jsonPrimitive?.booleanOrNull ?: false
        val name = runtime.metainfo.name
        // Read before the remove: `remove` closes the files, and a closed `FileSet` is not
        // somewhere to ask what it was writing.
        val paths = runtime.paths
        set.remove(runtime)
        if (!deleteData) return Outcome("Removed $name from the list; its files are still on the disk.")
        val kept = mutableListOf<String>()
        paths.forEach { path ->
            try {
                Files.deleteIfExists(path)
            } catch (undeletable: IOException) {
                kept += "$path (${undeletable.message})"
            }
        }
        return if (kept.isEmpty()) {
            Outcome("Removed $name and deleted its ${paths.size} file(s).")
        } else {
            Outcome("Removed $name; ${kept.size} file(s) could not be deleted: ${kept.joinToString()}", isError = true)
        }
    }

    private suspend fun setFilePriority(arguments: JsonObject): Outcome {
        val runtime = runtimeFor(arguments)
        val file =
            arguments["file"]?.jsonPrimitive?.intOrNull
                ?: throw Refusal("set_file_priority needs `file`, the file's index as torrent_status lists it")
        val files = runtime.metainfo.files
        if (file !in
            files.indices
        ) {
            throw Refusal(
                "${runtime.metainfo.name} has ${files.size} file(s), indexed 0..${files.size - 1}; there is no file $file",
            )
        }
        val word =
            arguments.string("priority") ?: throw Refusal("set_file_priority needs `priority`: skip, normal or high")
        val priority =
            FilePriority.entries.firstOrNull { it.name.equals(word, ignoreCase = true) }
                ?: throw Refusal("priority must be skip, normal or high, not '$word'")
        runtime.prioritise(file, priority)
        val name = files[file].path.joinToString("/")
        return if (runtime.settled { it.files.getOrNull(file)?.priority == priority }) {
            Outcome("$name is now ${priority.name.lowercase()}.")
        } else {
            Outcome("Asked for $name to be ${priority.name.lowercase()}; the session has not confirmed it yet.")
        }
    }

    private fun runtimeFor(arguments: JsonObject): TorrentRuntime {
        val hash =
            arguments.string("info_hash")
                ?: throw Refusal(
                    "this tool needs `info_hash`, the forty hex characters add_torrent and list_torrents show",
                )
        return set.torrents.firstOrNull {
            it.metainfo.infoHash
                .hex()
                .equals(hash, ignoreCase = true)
        }
            ?: throw Refusal("no torrent here with info hash $hash; list_torrents says what there is")
    }

    // ---- framing ------------------------------------------------------------------------------

    private fun send(frame: JsonObject) {
        synchronized(writing) { write(frame.toString()) }
    }

    private fun result(
        id: JsonElement,
        result: JsonObject,
    ): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }

    private fun error(
        id: JsonElement,
        code: Int,
        message: String,
    ): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf {
            it.isNotBlank()
        }

    internal companion object {
        const val SNAPSHOT_URI: String = "kachok://snapshot"
        const val VERSION: String = "0.1.0"

        /** Oldest first. The last is the newest this speaks and the one offered to a client that asks for something else. */
        val PROTOCOL_VERSIONS: List<String> = listOf("2024-11-05", "2025-03-26", "2025-06-18")

        private const val MAGNET_SCHEME = "magnet:"
        private const val PERCENT = 100
        private const val DEFAULT_WAIT_SECONDS = 600L
        private const val SETTLE_SECONDS = 2L
        private const val MAX_WAIT_SECONDS = 3_600L

        private const val PARSE_ERROR = -32_700
        private const val INVALID_REQUEST = -32_600
        private const val METHOD_NOT_FOUND = -32_601
        private const val INVALID_PARAMS = -32_602
        private const val RESOURCE_NOT_FOUND = -32_002

        private fun stateWord(
            paused: Boolean,
            complete: Boolean,
            sessionError: String?,
        ): String =
            when {
                sessionError != null -> "error"
                paused -> "paused"
                complete -> "seeding"
                else -> "downloading"
            }

        private const val KIB = 1024.0

        fun human(bytes: Long): String {
            var value = bytes.toDouble()
            val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
            var unit = 0
            while (value >= KIB && unit < units.lastIndex) {
                value /= KIB
                unit++
            }
            return if (unit == 0) "$bytes B" else String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit])
        }

        private fun schema(
            required: List<String>,
            build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
        ): JsonObject =
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties", build)
                putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
            }

        private fun kotlinx.serialization.json.JsonObjectBuilder.property(
            name: String,
            type: String,
            about: String,
            items: String? = null,
            enum: List<String>? = null,
        ) {
            putJsonObject(name) {
                put("type", type)
                put("description", about)
                items?.let { putJsonObject("items") { put("type", it) } }
                enum?.let { putJsonArray("enum") { it.forEach { word -> add(JsonPrimitive(word)) } } }
            }
        }

        private const val HASH =
            "The torrent's info hash: forty hex characters, as add_torrent and list_torrents show it."

        private val TOOLS: List<Tool> =
            listOf(
                Tool(
                    "add_torrent",
                    "Add a torrent from a .torrent file on this machine or from a magnet link, and start " +
                        "downloading it. " +
                        "Answers with the info hash every other tool needs. A magnet is fetched from the swarm first, which can take a while.",
                    schema(listOf("source")) {
                        property("source", "string", "A path to a .torrent file, or a magnet: link.")
                        property("directory", "string", "Where to save it. Defaults to the server's directory.")
                        property("high", "array", "Indices of files to fetch before the others.", items = "integer")
                    },
                ) { addTorrent(it) },
                Tool(
                    "list_torrents",
                    "Every torrent this client holds: hash, name, state, percentage, rates, peers. One line each.",
                    schema(emptyList()) {},
                ) { listTorrents() },
                Tool(
                    "torrent_status",
                    "One torrent in detail: progress, rates, peers, tracker and peer errors, and every file with its priority.",
                    schema(listOf("info_hash")) { property("info_hash", "string", HASH) },
                ) { torrentStatus(it) },
                Tool(
                    "wait_for_completion",
                    "Block until the torrent has finished downloading, has failed, or the timeout passes. " +
                        "Answers with what happened; if it is still going, call again to keep waiting.",
                    schema(listOf("info_hash")) {
                        property("info_hash", "string", HASH)
                        property(
                            "timeout_seconds",
                            "integer",
                            "How long to wait before answering anyway. Default 600, at most 3600.",
                        )
                    },
                ) { waitForCompletion(it) },
                Tool(
                    "pause_torrent",
                    "Stop transferring and tell the tracker so; the torrent keeps its place and its progress.",
                    schema(listOf("info_hash")) { property("info_hash", "string", HASH) },
                ) { pause(it) },
                Tool(
                    "resume_torrent",
                    "Start a paused torrent again.",
                    schema(listOf("info_hash")) { property("info_hash", "string", HASH) },
                ) { resume(it) },
                Tool(
                    "remove_torrent",
                    "Take a torrent off the list. With delete_data, also delete the files it wrote.",
                    schema(listOf("info_hash")) {
                        property("info_hash", "string", HASH)
                        property("delete_data", "boolean", "Delete the downloaded files too. Default false.")
                    },
                ) { remove(it) },
                Tool(
                    "set_file_priority",
                    "Move one file of a torrent to another tier: skip (do not fetch), normal, or high " +
                        "(fetch before the others). " +
                        "Works on a running torrent.",
                    schema(listOf("info_hash", "file", "priority")) {
                        property("info_hash", "string", HASH)
                        property("file", "integer", "The file's index, as torrent_status lists it.")
                        property("priority", "string", "skip, normal or high.", enum = listOf("skip", "normal", "high"))
                    },
                ) { setFilePriority(it) },
            )
    }
}
