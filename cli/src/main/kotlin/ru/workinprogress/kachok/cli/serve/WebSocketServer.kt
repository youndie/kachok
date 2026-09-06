package ru.workinprogress.kachok.cli.serve

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A WebSocket server on loopback, and nothing else on loopback.
 *
 * **Bound to `127.0.0.1` and not to `0.0.0.0`**, which is the owner's security decision: the client
 * is a page on this machine, there is no authentication, and a socket on every interface with no
 * authentication is a torrent client anybody on the network can drive.
 *
 * **And an `Origin` check, which is not authentication.** "Localhost only" reads as "only this
 * machine's own programs" and is not that: a WebSocket is exempt from the same-origin rule that
 * keeps an ordinary request from crossing into `127.0.0.1`, so a page on any site somebody visits
 * can open `ws://127.0.0.1:<port>` and remove their torrents *with their data*. Refusing an `Origin`
 * this server did not expect refuses other people's pages, not the person — a browser sets the
 * header itself and a page cannot forge it. Non-browser clients send no `Origin` at all and are
 * allowed: they are already on this machine and already the person
 * ([B-40](../../../../../../../../docs/backlog/B-40-wasmjs-ui-is-a-client-of-the-headless-engine.md)).
 */
internal class WebSocketServer(
    port: Int = 0,
    /**
     * Origins a browser may connect from. Empty means no browser may — every non-browser client
     * still can, which is what a `curl` or the JDK's client is.
     */
    private val allowedOrigins: Set<String> = emptySet(),
    private val onConnection: (Connection) -> Unit,
) : AutoCloseable {
    private val server = ServerSocket(port, BACKLOG, InetAddress.getLoopbackAddress())

    val port: Int get() = server.localPort

    fun start() {
        thread(isDaemon = true, name = "kachok-ws-accept") {
            while (!server.isClosed) {
                val socket =
                    try {
                        server.accept()
                    } catch (closed: IOException) {
                        return@thread
                    }
                thread(isDaemon = true, name = "kachok-ws-${socket.port}") { serve(socket) }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use {
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val request =
                try {
                    readRequest(input)
                } catch (unreadable: IOException) {
                    return
                }
            val key = request["sec-websocket-key"]
            if (key == null) {
                // A plain HTTP request that is not an upgrade. Answering with a sentence rather than
                // hanging up: somebody who opens this port in a browser's address bar deserves to
                // be told what it is.
                output.write(
                    (
                        "HTTP/1.1 426 Upgrade Required\r\nContent-Length: 46\r\n" +
                            "Connection: close\r\n\r\nkachok speaks WebSocket on this port, not HTTP.\n"
                    ).encodeToByteArray(),
                )
                output.flush()
                return
            }
            val origin = request["origin"]
            if (origin != null && origin !in allowedOrigins) {
                output.write(
                    (
                        "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    ).encodeToByteArray(),
                )
                output.flush()
                return
            }
            output.write(
                (
                    "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
                        "Connection: Upgrade\r\nSec-WebSocket-Accept: ${WebSocket.accept(key)}\r\n\r\n"
                ).encodeToByteArray(),
            )
            output.flush()

            val connection = Connection(socket, output)
            try {
                onConnection(connection)
                pump(connection, input)
            } catch (ended: IOException) {
                // A client that went away is the normal way one of these ends.
                connection.onClose(ended.message)
            }
        }
    }

    /**
     * Reads frames until the client goes, handling everything that is not a message itself.
     *
     * Fragmentation is reassembled here rather than handed up: a browser is allowed to split a
     * message across frames whenever it likes, and a reader that treated the first fragment as the
     * message would work for every small snapshot and fail on the one large one.
     */
    private fun pump(
        connection: Connection,
        input: BufferedInputStream,
    ) {
        val assembling = ByteArrayOutputStream()
        var assemblingText = false
        while (true) {
            val frame = WebSocket.readFrame(input) ?: return
            when (frame.opcode) {
                WebSocket.OPCODE_PING -> {
                    connection.send(WebSocket.OPCODE_PONG, frame.payload)
                }

                WebSocket.OPCODE_PONG -> {
                    Unit
                }

                WebSocket.OPCODE_CLOSE -> {
                    // Echoed, which is what closes a connection cleanly instead of leaving the
                    // browser to time out and report a network error to its console.
                    connection.sendClose(NORMAL_CLOSURE)
                    connection.onClose(null)
                    return
                }

                WebSocket.OPCODE_TEXT, WebSocket.OPCODE_BINARY -> {
                    assemblingText = frame.opcode == WebSocket.OPCODE_TEXT
                    assembling.reset()
                    assembling.write(frame.payload)
                    if (frame.fin) deliver(connection, assembling, assemblingText)
                }

                WebSocket.OPCODE_CONTINUATION -> {
                    assembling.write(frame.payload)
                    if (frame.fin) deliver(connection, assembling, assemblingText)
                }

                else -> {
                    throw WebSocket.ProtocolException("unknown opcode ${frame.opcode}")
                }
            }
        }
    }

    private fun deliver(
        connection: Connection,
        assembling: ByteArrayOutputStream,
        text: Boolean,
    ) {
        val message = assembling.toByteArray()
        assembling.reset()
        // Binary is not part of this protocol and saying so beats ignoring it: a client sending
        // binary has misunderstood something, and silence is the slowest way to find out.
        if (text) connection.onMessage(message.decodeToString()) else connection.sendClose(UNSUPPORTED_DATA)
    }

    private fun readRequest(input: BufferedInputStream): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        var line = readLine(input) ?: throw IOException("no request line")
        while (true) {
            line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                // Lowercased, because HTTP header names are case-insensitive and a browser's
                // `Sec-WebSocket-Key` and a test's `sec-websocket-key` are the same header.
                headers[line.take(colon).trim().lowercase()] = line.drop(colon + 1).trim()
            }
            if (headers.size > MAX_HEADERS) throw IOException("too many headers")
        }
        return headers
    }

    private fun readLine(input: BufferedInputStream): String? {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (line.isEmpty()) null else line.toString()
            if (byte == '\n'.code) return line.toString().removeSuffix("\r")
            line.append(byte.toChar())
            if (line.length > MAX_LINE) throw IOException("a header line ran past $MAX_LINE bytes")
        }
    }

    override fun close() {
        try {
            server.close()
        } catch (ignored: IOException) {
            // Closing a socket that is already shut is not news.
        }
    }

    /** One connected client. Writes are synchronised: the sampler and the reader both send. */
    internal class Connection(
        private val socket: Socket,
        private val output: java.io.OutputStream,
    ) {
        var onMessage: (String) -> Unit = {}
        var onClose: (String?) -> Unit = {}

        val isOpen: Boolean get() = !socket.isClosed && socket.isConnected

        fun sendText(text: String): Unit = send(WebSocket.OPCODE_TEXT, text.encodeToByteArray())

        @Synchronized
        fun send(
            opcode: Int,
            payload: ByteArray,
        ) {
            WebSocket.writeFrame(output, opcode, payload)
        }

        @Synchronized
        fun sendClose(code: Int) {
            WebSocket.writeClose(output, code)
        }
    }

    private companion object {
        const val BACKLOG = 8
        const val MAX_HEADERS = 64
        const val MAX_LINE = 8 * 1024
        const val NORMAL_CLOSURE = 1000
        const val UNSUPPORTED_DATA = 1003
    }
}
