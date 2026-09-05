package ru.workinprogress.kachok.engine.io

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.workinprogress.kachok.engine.bencode.BDictionary
import ru.workinprogress.kachok.engine.dht.Krpc
import ru.workinprogress.kachok.engine.dht.KrpcException
import ru.workinprogress.kachok.engine.dht.KrpcMessage
import ru.workinprogress.kachok.engine.dht.KrpcTransport
import ru.workinprogress.kachok.engine.peer.PeerAddress
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * KRPC over one unconnected datagram socket (BEP 5).
 *
 * **One socket for the whole DHT**, unlike the UDP tracker's, which opens one per announce. A
 * lookup has three queries in flight at a time to three different nodes and walks eight rounds
 * deep; a socket per query would be a file descriptor per hop and a source port the network has
 * never seen before on each — and `announce_peer`'s `implied_port` tells nodes to remember the
 * port a query arrived from, so it has to be the same port every time or the announce points at
 * nothing.
 *
 * That makes this a multiplexer, and the transaction id is what multiplexes: one reader coroutine
 * takes every datagram, looks its `t` up, and hands it to whoever asked. A reply nobody is waiting
 * for is dropped — on an open UDP port that is the ordinary case, not an error.
 */
public class DatagramKrpcTransport(
    private val dispatcher: CoroutineDispatcher,
    public val socket: DatagramSocket = DatagramSocket(),
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val random: Random = Random.Default,
) : KrpcTransport,
    AutoCloseable {
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<KrpcMessage>>()

    public val port: Int get() = socket.localPort

    /** Queries this node answered, for the tests: a DHT that only asks is not a DHT node. */
    public var queriesSeen: Int = 0
        private set

    /**
     * Starts the reader.
     *
     * Separate from construction because the loop belongs to the caller's scope: a DHT that
     * outlived the session that made it would keep a socket open and a virtual thread parked on it.
     */
    public fun start(scope: CoroutineScope) {
        scope.launch(dispatcher) {
            val buffer = ByteArray(MAX_DATAGRAM)
            while (!socket.isClosed) {
                val datagram = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(datagram)
                } catch (closed: IOException) {
                    return@launch
                }
                val message =
                    try {
                        Krpc.decode(datagram.data, datagram.length)
                    } catch (malformed: KrpcException) {
                        // An open UDP port receives everything; a datagram that is not KRPC is
                        // somebody else's business.
                        continue
                    }
                when (message) {
                    is KrpcMessage.Query -> queriesSeen++
                    else -> waiting.remove(key(message.transactionId))?.complete(message)
                }
            }
        }
    }

    override suspend fun query(
        node: PeerAddress,
        method: String,
        arguments: BDictionary,
    ): KrpcMessage.Response? =
        withContext(dispatcher) {
            val transaction = ByteArray(TRANSACTION_SIZE) { random.nextInt(256).toByte() }
            val pending = CompletableDeferred<KrpcMessage>()
            waiting[key(transaction)] = pending
            try {
                val packet = Krpc.encode(KrpcMessage.Query(transaction, method, arguments))
                val endpoint = InetSocketAddress(node.host, node.port)
                if (endpoint.isUnresolved) return@withContext null
                socket.send(DatagramPacket(packet, packet.size, endpoint))
                // A node that does not answer is a null and not an exception: on UDP that is the
                // ordinary case, and every caller above treats it as information.
                withTimeoutOrNull(timeout) { pending.await() } as? KrpcMessage.Response
            } catch (unreachable: IOException) {
                null
            } finally {
                waiting.remove(key(transaction))
            }
        }

    override fun close() {
        socket.close()
        waiting.values.forEach { it.cancel() }
        waiting.clear()
    }

    private fun key(transaction: ByteArray): String = transaction.joinToString(",") { it.toString() }

    public companion object {
        /** Two bytes, which is what every implementation uses and what BEP 5's examples show. */
        public const val TRANSACTION_SIZE: Int = 2

        /** Larger than any KRPC reply: `nodes` is eight nodes of 26 bytes plus a little. */
        public const val MAX_DATAGRAM: Int = 4096

        public val DEFAULT_TIMEOUT: Duration = 5.seconds
    }
}
