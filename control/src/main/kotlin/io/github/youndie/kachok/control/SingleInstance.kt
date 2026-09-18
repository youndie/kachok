package io.github.youndie.kachok.control

import io.github.youndie.kachok.control.mcp.McpServer
import kotlinx.coroutines.channels.Channel
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * One client per machine, and every later launch hands its torrent — or its agent — to it.
 *
 * Double-clicking a `.torrent` starts a **new process** on all three platforms unless the
 * application says otherwise, and two of these processes is two clients on one listening port and
 * one download directory — [B-60](../../../../../../../../../docs/backlog/B-60-two-torrents-one-path.md)
 * again, between processes this time, where no `TorrentSet` can see the collision and refuse it.
 *
 * **A socket is the lock.** A lock *file* cannot tell a running process from a crashed one: the
 * file outlives the process that wrote it, and every scheme for noticing that — a pid, a
 * heartbeat — is a new way to be wrong. A bound port disappears when the process does, so the
 * question "is one already running" is answered by trying to talk to it.
 *
 * **Loopback only, and a shared secret.** The port is written into a file in the user's own
 * configuration directory along with 128 random bits, and a caller that does not send them back is
 * hung up on. Without that, any process on this machine could hand this client a path to open;
 * with it, the surface is anyone who can already read that user's files, which is where the
 * torrents themselves are.
 *
 * **Two things a caller may be, and it says which**
 * ([B-117](../../../../../../../../../docs/backlog/B-117-one-client-for-the-window-and-the-agent.md)).
 * After the acknowledgement it sends one word: [OPEN], and the lines after it are paths for the
 * window to open; or [MCP], and the socket becomes the pipe of a Model Context Protocol session
 * against the engine this process is already running. That is what makes an agent and a person one
 * client instead of two — the alternative was a second engine, with its own peer port, its own DHT
 * node and a torrent list neither surface could see the whole of.
 */
public class SingleInstance private constructor(
    private val server: ServerSocket,
    private val lock: Path,
    private val secret: String,
) : AutoCloseable {
    /**
     * Paths handed over by later launches, in the order they arrived.
     *
     * Unlimited and never dropped: a person double-clicking three torrents in a row is three
     * launches within a second, and a conflating channel would open the last one.
     */
    public val opened: Channel<Path> = Channel(Channel.UNLIMITED)

    /**
     * Where an MCP session comes from, set by the surface that owns the engine.
     *
     * Volatile and nullable because the window binds this socket in `main` and builds its
     * `TorrentSet` a moment later on the composition: an agent that connects in between is told so
     * in a word rather than left holding a pipe that never answers.
     */
    @Volatile
    public var agents: McpSessions? = null

    /** The surface's side of an MCP session: one server per connected agent. */
    public fun interface McpSessions {
        /**
         * A server writing its frames through [write], or null while this process has no engine to
         * drive yet.
         */
        public fun open(write: (String) -> Unit): McpServer?
    }

    private fun serve() {
        thread(isDaemon = true, name = "kachok-single-instance") {
            while (!server.isClosed) {
                val socket =
                    try {
                        server.accept()
                    } catch (closed: IOException) {
                        return@thread
                    }
                // A connection used to be answered on this thread, because handing three paths over
                // takes microseconds. An MCP session lasts as long as the agent does, so a second
                // agent would have waited for the first to go away — and `wait_for_completion`
                // blocks for up to an hour.
                thread(isDaemon = true, name = "kachok-instance-caller") {
                    socket.use { handOver(it) }
                }
            }
        }
    }

    private fun handOver(socket: Socket) {
        try {
            socket.soTimeout = HANDOVER_TIMEOUT_MILLIS
            val reader = socket.getInputStream().bufferedReader()
            if (reader.readLine() != secret) return
            // The reply comes first, so a caller that is about to exit knows it was heard rather
            // than guessing from the fact that its write did not throw.
            val out = socket.getOutputStream()
            out.write("$ACKNOWLEDGED\n".encodeToByteArray())
            out.flush()
            when (reader.readLine()) {
                OPEN -> open(reader)

                MCP -> relay(socket, reader, out)

                // A word this client does not know is a newer launch talking to an older client.
                // Hanging up is the whole answer: the caller sees the socket close and decides.
                else -> return
            }
        } catch (hungUp: IOException) {
            // A caller that went away mid-sentence takes its own paths with it. Nothing here is
            // worth failing a running client over.
            System.err.println("kachok: a second launch hung up: ${hungUp.message}")
        }
    }

    private fun open(reader: BufferedReader) {
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isNotBlank()) opened.trySend(Path.of(line))
        }
    }

    private fun relay(
        socket: Socket,
        reader: BufferedReader,
        out: OutputStream,
    ) {
        // **No read timeout for an agent.** Two seconds is right for a launch handing over a path
        // and wrong for a session that may sit idle between tool calls, or spend an hour inside
        // `wait_for_completion`. Zero is "until the other end closes", which is what the pipe this
        // stands in for does.
        socket.soTimeout = 0
        val server =
            agents?.open { frame ->
                // **Swallowed, and it has to be.** A frame is written from whichever engine
                // coroutine finished the call, and an agent that left in the middle of a long one
                // — `wait_for_completion` runs for up to an hour — makes this throw. Letting it
                // out would carry an `IOException` about a departed agent into the window's own
                // scope, which is the last place that is anybody's problem.
                try {
                    out.write("$frame\n".encodeToByteArray())
                    out.flush()
                } catch (gone: IOException) {
                    // The reading loop below is at the same socket and ends on its own.
                }
            }
        if (server == null) {
            out.write("$UNAVAILABLE\n".encodeToByteArray())
            out.flush()
            return
        }
        out.write("$READY\n".encodeToByteArray())
        out.flush()
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isNotBlank()) server.receive(line)
        }
    }

    override fun close() {
        opened.close()
        try {
            server.close()
        } catch (ignored: IOException) {
            // Closing a socket that is already shut is not news.
        }
        try {
            // Only if it is still ours. A second client that replaced the file after this one
            // stopped answering owns it now, and deleting it would leave *that* one invisible.
            if (readLock(lock)?.second == secret) Files.deleteIfExists(lock)
        } catch (undeletable: IOException) {
            System.err.println("kachok: cannot remove $lock: ${undeletable.message}")
        }
    }

    public companion object {
        /**
         * The running client, told about [paths] — or a new one, which is this process.
         *
         * Null means somebody else is the client and has been given the paths: the caller's job is
         * then to exit, quietly and successfully. A person who double-clicked a torrent sees it
         * appear in the window that was already open, which is the whole point.
         */
        public fun claim(
            configDirectory: Path,
            paths: List<Path>,
        ): SingleInstance? {
            val lock = configDirectory.resolve(LOCK_FILE)
            if (handOverTo(lock, paths)) return null
            return bind(lock)
        }

        /**
         * An MCP session on the client that is already running, or null when there is none.
         *
         * Null is not a failure: it is the headless case, and the caller answers it by building an
         * engine of its own — which is what `kachok mcp` did on every machine before B-117.
         */
        public fun attach(configDirectory: Path): McpRelay? {
            val (port, secret) = readLock(configDirectory.resolve(LOCK_FILE)) ?: return null
            var socket: Socket? = null
            return try {
                val opened = Socket()
                socket = opened
                opened.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MILLIS)
                opened.soTimeout = HANDOVER_TIMEOUT_MILLIS
                val out = opened.getOutputStream()
                out.write("$secret\n$MCP\n".encodeToByteArray())
                out.flush()
                val reader = opened.getInputStream().bufferedReader()
                // Whatever answers on a port from a possibly stale file has to say the right word
                // twice before this process hands it an agent's frames.
                if (reader.readLine() != ACKNOWLEDGED) return closing(opened)
                when (reader.readLine()) {
                    READY -> {
                        opened.soTimeout = 0
                        McpRelay(opened, reader, out)
                    }

                    // The client is up but has no engine yet, which is a window still starting.
                    // Telling the caller apart from "nobody is there" is the point of the word.
                    else -> {
                        closing(opened)
                    }
                }
            } catch (unreachable: IOException) {
                socket?.let { closing(it) }
                null
            }
        }

        private fun closing(socket: Socket): McpRelay? {
            try {
                socket.close()
            } catch (ignored: IOException) {
                // Already shut, or never opened. Either way there is nothing to attach to.
            }
            return null
        }

        private fun handOverTo(
            lock: Path,
            paths: List<Path>,
        ): Boolean {
            val (port, secret) = readLock(lock) ?: return false
            return try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MILLIS)
                    socket.soTimeout = HANDOVER_TIMEOUT_MILLIS
                    socket.getOutputStream().apply {
                        write("$secret\n".encodeToByteArray())
                        flush()
                    }
                    val reader = socket.getInputStream().bufferedReader()
                    // A port in a stale file can belong to anything by now. Whatever answers has to
                    // say the right word before this process hands it a path and exits.
                    if (reader.readLine() != ACKNOWLEDGED) return false
                    socket.getOutputStream().apply {
                        write("$OPEN\n".encodeToByteArray())
                        paths.forEach { write("${it.toAbsolutePath()}\n".encodeToByteArray()) }
                        flush()
                    }
                }
                true
            } catch (unreachable: IOException) {
                // Nobody is listening, or something that is not this client is. Either way this
                // process becomes the client and takes the file over.
                false
            }
        }

        private fun bind(lock: Path): SingleInstance? =
            try {
                // Port zero: the operating system picks, and the file says which. A fixed port
                // would be one this client does not own on a machine where something else took it.
                val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
                val secret =
                    SecureRandom()
                        .let { random -> ByteArray(SECRET_BYTES).also(random::nextBytes) }
                        .joinToString("") { (it.toInt() and BYTE).toString(HEX).padStart(2, '0') }
                writeLock(lock, server.localPort, secret)
                SingleInstance(server, lock, secret).also { it.serve() }
            } catch (unavailable: IOException) {
                // A client that cannot open a loopback socket still works; it just cannot be handed
                // a second torrent. Refusing to start over that would be the worse trade.
                System.err.println("kachok: no single-instance lock: ${unavailable.message}")
                null
            }

        private fun readLock(lock: Path): Pair<Int, String>? =
            try {
                val lines = Files.readAllLines(lock)
                val port = lines.getOrNull(0)?.trim()?.toIntOrNull()
                val secret = lines.getOrNull(1)?.trim()
                if (port == null || secret.isNullOrBlank()) null else port to secret
            } catch (absent: IOException) {
                null
            }

        private fun writeLock(
            lock: Path,
            port: Int,
            secret: String,
        ) {
            Files.createDirectories(lock.parent)
            val temporary = lock.resolveSibling("${lock.fileName}.new")
            Files.write(temporary, listOf(port.toString(), secret))
            Files.move(temporary, lock, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

        private const val LOCK_FILE = "instance"

        /** Compared against `readLine()`, which strips the newline the writer adds. */
        private const val ACKNOWLEDGED = "kachok"

        /** The caller opens torrents in the running window; the lines after it are paths. */
        private const val OPEN = "open"

        /** The caller is an agent's process; the socket is a JSON-RPC pipe from here on. */
        private const val MCP = "mcp"

        /** The client has an engine and a session is open on it. */
        private const val READY = "kachok/mcp"

        /** The client is running but has no engine yet. */
        private const val UNAVAILABLE = "kachok/no-engine"
        private const val CONNECT_TIMEOUT_MILLIS = 500
        private const val HANDOVER_TIMEOUT_MILLIS = 2_000
        private const val BACKLOG = 4
        private const val SECRET_BYTES = 16
        private const val BYTE = 0xFF
        private const val HEX = 16
    }
}

/**
 * An MCP session running in the client that was already up: the agent's frames go in, that
 * client's answers come back, and no engine is built in this process
 * ([B-117](../../../../../../../../../docs/backlog/B-117-one-client-for-the-window-and-the-agent.md)).
 */
public class McpRelay internal constructor(
    private val socket: Socket,
    private val incoming: BufferedReader,
    private val outgoing: OutputStream,
) : AutoCloseable {
    /**
     * Carries frames both ways until either end stops, and returns when the agent's [input] closes
     * or the client on the other side goes away.
     *
     * The answers are pumped on a thread of their own because both directions block: a tool call
     * that takes an hour must not stop the agent from sending the next frame, and a client that
     * closes mid-call must not leave this process reading a pipe nobody will write to.
     */
    public fun pump(
        input: InputStream,
        out: Appendable,
    ) {
        val answers =
            thread(isDaemon = true, name = "kachok-mcp-relay") {
                try {
                    while (true) {
                        val line = incoming.readLine() ?: break
                        out.append(line).append('\n')
                        (out as? java.io.Flushable)?.flush()
                    }
                } catch (gone: IOException) {
                    // The client went away. The agent finds out when its own end closes below.
                } finally {
                    // Unblocks the read on the other thread rather than waiting for the agent to
                    // notice: a relay whose far end died is over, and an agent left holding a pipe
                    // that answers nothing is the failure this whole item exists to avoid.
                    closeQuietly()
                }
            }
        try {
            input.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    outgoing.write("$line\n".encodeToByteArray())
                    outgoing.flush()
                }
            }
            // **Half-closed, and not closed.** Stdin reaching EOF is the agent saying goodbye, and
            // an agent that says it right after a request is still owed the answer. Dropping the
            // socket here loses it: the client is mid-call on its own threads and finishes into a
            // socket that is no longer there. Shutting down this direction alone is the EOF it
            // needs to finish what it owes, and it closes the other direction when it has.
            //
            // The first run against a real window is how this was found — two frames from a file,
            // stdin at EOF before either could be answered, and nothing on stdout at all.
            shutdownOutputQuietly()
        } catch (gone: IOException) {
            // The far end closed while a frame was being written. Nothing to say and nowhere to
            // say it.
            closeQuietly()
        }
        // Bounded, because the wait is for politeness and not for correctness: a client that
        // neither answers nor closes must not keep a process alive after its agent has gone.
        answers.join(GOODBYE_MILLIS)
        closeQuietly()
    }

    private fun shutdownOutputQuietly() {
        try {
            if (!socket.isClosed) socket.shutdownOutput()
        } catch (ignored: IOException) {
            // Already gone, and the reader on the other thread ends on its own.
        }
    }

    private fun closeQuietly() {
        try {
            socket.close()
        } catch (ignored: IOException) {
            // Closing a socket that is already shut is not news.
        }
    }

    override fun close() {
        closeQuietly()
    }

    private companion object {
        /** How long a departing agent's owed answers are waited for. */
        const val GOODBYE_MILLIS = 10_000L
    }
}
