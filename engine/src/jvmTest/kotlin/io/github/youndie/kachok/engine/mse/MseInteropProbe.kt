package io.github.youndie.kachok.engine.mse

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.PeerId
import io.github.youndie.kachok.engine.peer.clientOf
import io.github.youndie.kachok.engine.wire.Handshake
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * B-100: dial a **real** encrypted client and see whether this is MSE or only self-consistent.
 *
 * **Why this exists at all.** Every test of the handshake has this client on both ends, and there
 * every symmetric property is invisible: removing the specification's 1 024-byte keystream discard
 * breaks nothing, and so would the wrong ASCII prefix, the wrong key width or the wrong byte order,
 * as long as both halves agree. A protocol is a claim about somebody else's client, and only
 * somebody else's client can check it.
 *
 * ```
 * ./gradlew :engine:mseInteropProbe -Ppeer=192.168.1.102:36881 -Phash=<info hash in hex>
 * ```
 *
 * It fails loudly when it is given nothing to talk to: a probe that quietly succeeds because it
 * found no subject is worse than no probe.
 */
public object MseInteropProbe {
    @JvmStatic
    public fun main(arguments: Array<String>) {
        val peer = System.getProperty("peer").orNullIfBlank() ?: arguments.getOrNull(0)
        val hash = System.getProperty("hash").orNullIfBlank() ?: arguments.getOrNull(1)
        if (peer == null || hash == null) {
            println("usage: -Ppeer=<host:port> -Phash=<40 hex characters of the info hash>")
            exitProcess(2)
        }
        val host = peer.substringBeforeLast(':')
        val port = peer.substringAfterLast(':').toInt()
        val infoHash = InfoHash(hash.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        val peerId = PeerId(("-KA0100-" + "probe".padEnd(12, '0')).encodeToByteArray())

        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT)
            socket.soTimeout = READ_TIMEOUT
            // Every byte, in order, with the call that carried it. Comparing two implementations
            // through a parser that agrees with both says nothing; the wire is the only place a
            // difference can still be hiding.
            val log = StringBuilder()
            val started = System.nanoTime()

            fun stamp(): String = "%7.1fms ".format((System.nanoTime() - started) / 1_000_000.0)
            val stream =
                object : ByteStream {
                    override fun read(
                        into: ByteArray,
                        fromIndex: Int,
                        toIndex: Int,
                    ): Int {
                        val got = socket.getInputStream().read(into, fromIndex, toIndex - fromIndex)
                        if (got >
                            0
                        ) {
                            log
                                .append(stamp())
                                .append(
                                    "R ",
                                ).append(got)
                                .append(' ')
                                .append(hex(into, fromIndex, fromIndex + got))
                                .append('\n')
                        }
                        return got
                    }

                    override fun write(
                        bytes: ByteArray,
                        fromIndex: Int,
                        toIndex: Int,
                    ) {
                        log
                            .append(stamp())
                            .append(
                                "W ",
                            ).append(toIndex - fromIndex)
                            .append(' ')
                            .append(hex(bytes, fromIndex, toIndex))
                            .append('\n')
                        socket.getOutputStream().write(bytes, fromIndex, toIndex - fromIndex)
                        socket.getOutputStream().flush()
                    }
                }
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    System.getProperty("dump").orNullIfBlank()?.let { java.io.File(it).writeText(log.toString()) }
                },
            )

            // The BitTorrent handshake rides inside the MSE one as `IA`, which is what a real
            // client does and what makes the first reply arrive in one round trip.
            val ours = Handshake(infoHash, peerId, Handshake.reservedBits(extensionProtocol = true))
            val result = Mse.dial(stream, infoHash, ours.encode(), Random.Default)

            val encrypted = result.encrypt != null
            println("crypto_select: " + if (encrypted) "RC4" else "plaintext")

            // **The assertion that means something.** Their BEP 3 handshake comes back through the
            // stream this client derived. If any symmetric detail is wrong it decrypts to noise,
            // and the info hash will not match.
            val theirs = ByteArray(Handshake.SIZE)
            var read = 0
            while (read < theirs.size) {
                val got = socket.getInputStream().read(theirs, read, theirs.size - read)
                if (got < 0) error("the peer closed after $read of ${theirs.size} handshake bytes")
                read += got
            }
            result.decrypt?.apply(theirs)
            val decoded = Handshake.decode(theirs)

            check(decoded.infoHash.bytes.contentEquals(infoHash.bytes)) {
                "the peer's handshake decrypted to the wrong info hash — this is not MSE"
            }
            println("their client:  " + clientOf(decoded.peerId))
            println("their hash:    " + decoded.infoHash.bytes.joinToString("") { "%02x".format(it) })
            println("INTEROP OK: a third-party client accepted this handshake and its reply decrypted")
        }
    }

    private fun String?.orNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

    private fun hex(
        bytes: ByteArray,
        fromIndex: Int,
        toIndex: Int,
    ): String = (fromIndex until toIndex).joinToString("") { "%02x".format(bytes[it]) }

    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 15_000
}
