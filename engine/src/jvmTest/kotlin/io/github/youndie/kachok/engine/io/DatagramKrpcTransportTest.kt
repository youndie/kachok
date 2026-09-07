package io.github.youndie.kachok.engine.io

import io.github.youndie.kachok.engine.bencode.BDictionary
import io.github.youndie.kachok.engine.bencode.BString
import io.github.youndie.kachok.engine.dht.Krpc
import io.github.youndie.kachok.engine.dht.KrpcMessage
import io.github.youndie.kachok.engine.dht.NodeId
import io.github.youndie.kachok.engine.peer.PeerAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The transport half of B-35: one socket, several questions at once, and the transaction id
 * telling the answers apart.
 */
class DatagramKrpcTransportTest {
    private val self = NodeId(ByteArray(NodeId.SIZE) { it.toByte() })
    private var responder: DatagramSocket? = null
    private var transport: DatagramKrpcTransport? = null

    /**
     * The reader's own scope, and not the test's.
     *
     * `runBlocking` waits for every coroutine started in its context, and this reader ends only
     * when its socket closes — which happens in the teardown that the test is waiting to reach.
     */
    private val reader = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    @AfterTest
    fun stop() {
        reader.cancel()
        responder?.close()
        transport?.close()
    }

    /**
     * A node that answers every query with its own id and a delay of its choosing.
     *
     * The delay is what makes the multiplexing testable: with every answer instant, a transport
     * that replied to whoever asked last would pass.
     */
    private fun respondingNode(delayFor: (String) -> Long = { 0 }): DatagramSocket {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
        responder = socket
        Thread.ofVirtual().start {
            val buffer = ByteArray(4096)
            while (!socket.isClosed) {
                val datagram = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(datagram)
                } catch (closed: IOException) {
                    return@start
                }
                val query = Krpc.decode(datagram.data, datagram.length) as KrpcMessage.Query
                val name = (query.arguments["name"] as? BString)?.asString() ?: ""
                Thread.ofVirtual().start {
                    Thread.sleep(delayFor(name))
                    val reply =
                        Krpc.encode(
                            KrpcMessage.Response(
                                query.transactionId,
                                BDictionary(
                                    mapOf(BString("id") to BString(self.bytes), BString("name") to BString(name)),
                                ),
                            ),
                        )
                    try {
                        socket.send(DatagramPacket(reply, reply.size, datagram.socketAddress))
                    } catch (gone: IOException) {
                        // The test ended and the socket went with it.
                    }
                }
            }
        }
        return socket
    }

    private fun transport(timeoutMillis: Long = 2_000): DatagramKrpcTransport =
        DatagramKrpcTransport(Dispatchers.IO, timeout = timeoutMillis.milliseconds).also { transport = it }

    @Test
    fun answersReachTheQuestionThatAskedThem(): Unit =
        runBlocking {
            // Three questions on one socket, answered in the reverse order they were asked. A
            // transport that matched answers by arrival would hand each one the wrong reply.
            val node =
                respondingNode { name ->
                    if (name == "first") {
                        400
                    } else if (name == "second") {
                        200
                    } else {
                        0
                    }
                }
            val client = transport()
            client.start(reader)
            val address = PeerAddress("127.0.0.1", node.localPort)

            val answers =
                listOf("first", "second", "third")
                    .map { name ->
                        async {
                            name to
                                client.query(
                                    address,
                                    Krpc.PING,
                                    BDictionary(
                                        mapOf(
                                            BString("id") to BString(self.bytes),
                                            BString("name") to BString(name),
                                        ),
                                    ),
                                )
                        }
                    }.awaitAll()

            answers.forEach { (asked, response) ->
                assertEquals(
                    asked,
                    (response?.values?.get("name") as? BString)?.asString(),
                    "the answer to `$asked` went to the wrong question",
                )
            }
        }

    @Test
    fun everyQueryLeavesTheSameSourcePort(): Unit =
        runBlocking {
            // BEP 5's `implied_port` tells a node to remember the port a query arrived from, so a
            // transport that opened a socket per query would announce a port nothing listens on.
            val seen = mutableSetOf<Int>()
            val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
            responder = socket
            Thread.ofVirtual().start {
                val buffer = ByteArray(4096)
                while (!socket.isClosed) {
                    val datagram = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(datagram)
                    } catch (closed: IOException) {
                        return@start
                    }
                    synchronized(seen) { seen += datagram.port }
                    val query = Krpc.decode(datagram.data, datagram.length) as KrpcMessage.Query
                    val reply =
                        Krpc.encode(KrpcMessage.Response(query.transactionId, BDictionary(emptyMap())))
                    socket.send(DatagramPacket(reply, reply.size, datagram.socketAddress))
                }
            }
            val client = transport()
            client.start(reader)
            val address = PeerAddress("127.0.0.1", socket.localPort)

            repeat(3) { client.query(address, Krpc.PING, Krpc.ping(self)) }

            assertEquals(setOf(client.port), synchronized(seen) { seen.toSet() })
        }

    @Test
    fun aNodeThatSaysNothingIsANullAndNotAnException(): Unit =
        runBlocking {
            val client = transport(timeoutMillis = 150)
            client.start(reader)

            // Port 1 on loopback: nothing listens there, and nothing will answer.
            val answer = client.query(PeerAddress("127.0.0.1", 1), Krpc.PING, Krpc.ping(self))

            assertNull(answer, "an unanswered datagram is the ordinary case on UDP")
        }

    @Test
    fun aDatagramThatIsNotKrpcIsDroppedAndTheSocketKeepsWorking(): Unit =
        runBlocking {
            // An open UDP port receives everything anyone cares to send it.
            val node = respondingNode()
            val client = transport()
            client.start(reader)
            val junk = DatagramSocket()
            junk.send(
                DatagramPacket(
                    "not bencode at all".encodeToByteArray(),
                    18,
                    InetAddress.getLoopbackAddress(),
                    client.port,
                ),
            )
            junk.close()

            val answer = client.query(PeerAddress("127.0.0.1", node.localPort), Krpc.PING, Krpc.ping(self))

            assertTrue(answer != null, "the junk datagram took the reader down with it")
        }
}
