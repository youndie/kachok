package io.github.youndie.kachok.ui.session

import kotlinx.coroutines.channels.Channel
import java.io.IOException
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
 * One client per machine, and every later launch hands its torrent to it.
 *
 * Double-clicking a `.torrent` starts a **new process** on all three platforms unless the
 * application says otherwise, and two of these processes is two clients on one listening port and
 * one download directory — [B-60](../../../../../../../../docs/backlog/B-60-two-torrents-one-path.md)
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
 */
internal class SingleInstance private constructor(
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
    val opened: Channel<Path> = Channel(Channel.UNLIMITED)

    private fun serve() {
        thread(isDaemon = true, name = "kachok-single-instance") {
            while (!server.isClosed) {
                val socket =
                    try {
                        server.accept()
                    } catch (closed: IOException) {
                        return@thread
                    }
                socket.use { handOver(it) }
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
            socket.getOutputStream().write("$ACKNOWLEDGED\n".encodeToByteArray())
            socket.getOutputStream().flush()
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isNotBlank()) opened.trySend(Path.of(line))
            }
        } catch (hungUp: IOException) {
            // A caller that went away mid-sentence takes its own paths with it. Nothing here is
            // worth failing a running client over.
            System.err.println("kachok: a second launch hung up: ${hungUp.message}")
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

    companion object {
        /**
         * The running client, told about [paths] — or a new one, which is this process.
         *
         * Null means somebody else is the client and has been given the paths: the caller's job is
         * then to exit, quietly and successfully. A person who double-clicked a torrent sees it
         * appear in the window that was already open, which is the whole point.
         */
        fun claim(
            configDirectory: Path,
            paths: List<Path>,
        ): SingleInstance? {
            val lock = configDirectory.resolve(LOCK_FILE)
            if (handOverTo(lock, paths)) return null
            return bind(lock)
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
        private const val CONNECT_TIMEOUT_MILLIS = 500
        private const val HANDOVER_TIMEOUT_MILLIS = 2_000
        private const val BACKLOG = 4
        private const val SECRET_BYTES = 16
        private const val BYTE = 0xFF
        private const val HEX = 16
    }
}
