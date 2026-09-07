package io.github.youndie.kachok.cli.serve

import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The frame codec, checked against an implementation that is not this one.
 *
 * A codec tested only by its own encoder passes whatever it happens to do — the failure mode this
 * repository already met once with bencode. `java.net.http.WebSocket` is the JDK's client, it is in
 * the module list the distribution already carries, and it was written by somebody who was reading
 * RFC 6455 rather than this file: it masks, it fragments when it feels like it, it sends pings and
 * it expects a close to be echoed.
 *
 * A real browser is the other check, and it is not something a JUnit test can hold — that one is
 * recorded in [B-40](../../../../../../../../docs/backlog/B-40-wasmjs-ui-is-a-client-of-the-headless-engine.md).
 */
class WebSocketServerTest {
    private var server: WebSocketServer? = null
    private val http = HttpClient.newHttpClient()

    @AfterTest
    fun cleanUp() {
        server?.close()
    }

    /** Collects everything the server sends, reassembled, so a fragmented reply is one string. */
    private open class Listening : WebSocket.Listener {
        val messages = LinkedBlockingQueue<String>()
        val closed = CompletableFuture<Int>()
        private val partial = StringBuilder()

        override fun onText(
            socket: WebSocket,
            data: CharSequence,
            last: Boolean,
        ): CompletionStage<*>? {
            partial.append(data)
            if (last) {
                messages += partial.toString()
                partial.clear()
            }
            socket.request(1)
            return null
        }

        override fun onClose(
            socket: WebSocket,
            code: Int,
            reason: String,
        ): CompletionStage<*>? {
            closed.complete(code)
            return null
        }

        fun next(): String? = messages.poll(10, TimeUnit.SECONDS)
    }

    private fun serve(
        allowedOrigins: Set<String> = emptySet(),
        onConnection: (WebSocketServer.Connection) -> Unit,
    ): WebSocketServer =
        WebSocketServer(allowedOrigins = allowedOrigins, onConnection = onConnection)
            .also {
                server = it
                it.start()
            }

    private fun connect(
        into: Listening,
        headers: Map<String, String> = emptyMap(),
    ): WebSocket =
        http
            .newWebSocketBuilder()
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .buildAsync(URI.create("ws://127.0.0.1:${server!!.port}"), into)
            .get(10, TimeUnit.SECONDS)

    @Test
    fun aClientConnectsAndIsSentAMessage() {
        serve { it.sendText("""{"hello":"kachok"}""") }
        val listening = Listening()
        connect(listening)
        assertEquals("""{"hello":"kachok"}""", listening.next())
    }

    /** The direction that is easy to get backwards: a client's frames are masked and a server's are not. */
    @Test
    fun aMessageFromTheClientArrivesUnmasked() {
        val received = LinkedBlockingQueue<String>()
        serve { connection -> connection.onMessage = { received += it } }
        val socket = connect(Listening())
        socket.sendText("""{"type":"pause","infoHash":"abc"}""", true).get(10, TimeUnit.SECONDS)
        assertEquals("""{"type":"pause","infoHash":"abc"}""", received.poll(10, TimeUnit.SECONDS))
    }

    /**
     * A message split across frames is one message.
     *
     * The client decides where to split, so a server that read the first fragment as the whole
     * thing would work for every small message and fail on the one large one — which here is a
     * `.torrent` arriving base64 inside JSON.
     */
    @Test
    fun aFragmentedMessageIsReassembled() {
        val received = LinkedBlockingQueue<String>()
        serve { connection -> connection.onMessage = { received += it } }
        val socket = connect(Listening())
        socket.sendText("{\"a\":\"", false).get(10, TimeUnit.SECONDS)
        socket.sendText("x".repeat(70_000), false).get(10, TimeUnit.SECONDS)
        socket.sendText("\"}", true).get(10, TimeUnit.SECONDS)

        val message = assertNotNull(received.poll(10, TimeUnit.SECONDS))
        assertEquals("{\"a\":\"" + "x".repeat(70_000) + "\"}", message)
    }

    /** A payload over 65 535 bytes uses the eight-byte length, which is its own branch. */
    @Test
    fun aLargeMessageSurvivesInBothDirections() {
        val big = "y".repeat(200_000)
        val received = LinkedBlockingQueue<String>()
        serve { connection ->
            connection.onMessage = {
                received += it
                connection.sendText(big)
            }
        }
        val listening = Listening()
        val socket = connect(listening)
        socket.sendText(big, true).get(10, TimeUnit.SECONDS)
        assertEquals(big, received.poll(10, TimeUnit.SECONDS))
        assertEquals(big, listening.next())
    }

    /** A ping has to be answered or the client decides the server is gone. */
    @Test
    fun aPingIsAnswered() {
        serve { }
        val pongs = CompletableFuture<String>()
        val listening =
            object : Listening() {
                override fun onPong(
                    socket: WebSocket,
                    message: java.nio.ByteBuffer,
                ): CompletionStage<*>? {
                    pongs.complete(Charsets.UTF_8.decode(message).toString())
                    socket.request(1)
                    return null
                }
            }
        val socket = connect(listening)
        socket.sendPing(java.nio.ByteBuffer.wrap("beat".encodeToByteArray())).get(10, TimeUnit.SECONDS)
        assertEquals("beat", pongs.get(10, TimeUnit.SECONDS))
    }

    /** A close is echoed, which is what ends a connection cleanly rather than by timeout. */
    @Test
    fun aCloseIsEchoedWithANormalStatus() {
        serve { }
        val listening = Listening()
        val socket = connect(listening)
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(10, TimeUnit.SECONDS)
        assertEquals(WebSocket.NORMAL_CLOSURE, listening.closed.get(10, TimeUnit.SECONDS))
    }

    // ---- the security decision

    /**
     * A page from somewhere else is refused, which is what makes "localhost only" mean anything.
     *
     * A WebSocket is not subject to the same-origin rule, so without this a page on any site the
     * person visits could remove their torrents with their data.
     */
    @Test
    fun aBrowserFromAnotherSiteIsRefused() {
        serve(allowedOrigins = setOf("http://127.0.0.1:8080")) { }
        val failure =
            runCatching { connect(Listening(), headers = mapOf("Origin" to "https://evil.example")) }
                .exceptionOrNull()
        assertNotNull(failure, "a page from another origin was let in")
    }

    @Test
    fun theOriginThisServerServesIsLetIn() {
        serve(allowedOrigins = setOf("http://127.0.0.1:8080")) { it.sendText("in") }
        val listening = Listening()
        connect(listening, headers = mapOf("Origin" to "http://127.0.0.1:8080"))
        assertEquals("in", listening.next())
    }

    /**
     * A client that sends no `Origin` is not a browser, and is allowed.
     *
     * It is already a program on this machine, which is already the person — the header exists to
     * tell *pages* apart, and a page cannot omit it.
     */
    @Test
    fun aClientWithNoOriginIsNotAPageAndIsAllowed() {
        serve(allowedOrigins = setOf("http://127.0.0.1:8080")) { it.sendText("in") }
        val listening = Listening()
        connect(listening)
        assertEquals("in", listening.next())
    }

    /** Somebody who opens the port in a browser's address bar is told what it is. */
    @Test
    fun aPlainHttpRequestIsAnsweredWithASentence() {
        serve { }
        val connection =
            URI.create("http://127.0.0.1:${server!!.port}/").toURL().openConnection() as HttpURLConnection
        assertEquals(HTTP_UPGRADE_REQUIRED, connection.responseCode)
        assertContains(connection.errorStream.readBytes().decodeToString(), "WebSocket")
    }

    /** The listener is on loopback and nowhere else: no authentication, so no other interface. */
    @Test
    fun theSocketIsBoundToLoopbackOnly() {
        serve { }
        val reachable =
            runCatching {
                java.net.Socket().apply {
                    connect(java.net.InetSocketAddress(localAddress(), server!!.port), 500)
                    close()
                }
            }.isSuccess
        assertTrue(!reachable, "the server answered on a non-loopback address")
    }

    private fun localAddress(): java.net.InetAddress =
        java.net.NetworkInterface
            .getNetworkInterfaces()
            .toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .firstOrNull { it is java.net.Inet4Address }
            ?: java.net.InetAddress.getLoopbackAddress()

    private companion object {
        const val HTTP_UPGRADE_REQUIRED = 426
    }
}
