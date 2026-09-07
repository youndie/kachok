package io.github.youndie.kachok.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.youndie.kachok.engine.bencode.BDictionary
import io.github.youndie.kachok.engine.bencode.BInteger
import io.github.youndie.kachok.engine.bencode.BList
import io.github.youndie.kachok.engine.bencode.BString
import io.github.youndie.kachok.engine.bencode.Bencode
import io.github.youndie.kachok.engine.metainfo.Metainfo
import io.github.youndie.kachok.engine.metainfo.MetainfoParser
import io.github.youndie.kachok.engine.wire.PeerWire
import io.github.youndie.kachok.swarm.SeedingPeer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * A one-torrent swarm, served to anything that can reach this machine.
 *
 * The tests build their swarm in the same process as the client; a run-time image cannot, because
 * the whole point of checking one is that the client runs somewhere else — in a container with no
 * JDK in it. This is that swarm, run from `./gradlew :cli:swarmHost` and pointed at by
 * `scripts/verify_runtime_image.sh`.
 *
 * It writes the torrent and the expected SHA-256 into a directory and then serves until it is
 * killed. The tracker answers with BEP 3's *non-compact* peer list, which is the one place this
 * differs from the tests: compact peers are four bytes of IPv4, and what a container needs is a
 * name — `host.docker.internal` — which only the older form can carry.
 */
public object SwarmHost {
    @JvmStatic
    public fun main(args: Array<String>) {
        val directory = Path.of(argument(args, "--dir") ?: ".")
        val megabytes = argument(args, "--megabytes")?.toInt() ?: 16
        val announceHost = argument(args, "--announce-host") ?: "127.0.0.1"
        val bind = argument(args, "--bind") ?: "0.0.0.0"

        Files.createDirectories(directory)
        val content = ByteArray(megabytes * 1024 * 1024) { (it * 31 and 0xFF).toByte() }
        val info = infoDictionary(content)
        val seed =
            SeedingPeer(
                infoHash = infoHashOf(info),
                content = content,
                pieceLength = PIECE_LENGTH,
                bindAddress = bind,
            )
        val tracker = trackerServing(bind, announceHost, seed.port)
        val url = "http://$announceHost:${tracker.address.port}/annc"
        val torrent =
            Bencode.encode(
                BDictionary(mapOf(BString("announce") to BString(url), BString("info") to info)),
            )
        Files.write(directory.resolve("fixture.torrent"), torrent)
        Files.writeString(
            directory.resolve("expected.sha256"),
            MessageDigest.getInstance("SHA-256").digest(content).joinToString("") {
                (it.toInt() and 0xFF).toString(16).padStart(2, '0')
            } + "  payload.bin\n",
        )

        println("swarm: $megabytes MB, tracker $url, seed $announceHost:${seed.port}")
        println("torrent ${directory.resolve("fixture.torrent")}")
        System.out.flush()
        Runtime.getRuntime().addShutdownHook(
            Thread {
                tracker.stop(0)
                seed.close()
            },
        )
        // Serving is all this process does; the sockets have their own threads.
        Thread.currentThread().join()
    }

    private fun infoHashOf(info: BDictionary): io.github.youndie.kachok.engine.InfoHash =
        MetainfoParser
            .parse(
                Bencode.encode(
                    BDictionary(
                        mapOf(
                            BString("announce") to BString("http://127.0.0.1:1/annc"),
                            BString("info") to info,
                        ),
                    ),
                ),
            ).infoHash

    private fun infoDictionary(content: ByteArray): BDictionary {
        val digest = MessageDigest.getInstance("SHA-1")
        val pieces = (content.size + PIECE_LENGTH - 1) / PIECE_LENGTH
        val hashes = ByteArray(pieces * Metainfo.HASH_SIZE)
        (0 until pieces).forEach { index ->
            val from = index * PIECE_LENGTH
            val to = minOf(from + PIECE_LENGTH, content.size)
            digest.reset()
            digest.update(content, from, to - from)
            digest.digest().copyInto(hashes, index * Metainfo.HASH_SIZE)
        }
        return BDictionary(
            mapOf(
                BString("length") to BInteger(content.size.toLong()),
                BString("name") to BString("payload.bin"),
                BString("piece length") to BInteger(PIECE_LENGTH.toLong()),
                BString("pieces") to BString(hashes),
            ),
        )
    }

    private fun trackerServing(
        bind: String,
        announceHost: String,
        peerPort: Int,
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress(bind, 0), 0)
        val body =
            Bencode.encode(
                BDictionary(
                    mapOf(
                        BString("interval") to BInteger(1800),
                        BString("peers") to
                            BList(
                                listOf(
                                    BDictionary(
                                        mapOf(
                                            BString("ip") to BString(announceHost),
                                            BString("port") to BInteger(peerPort.toLong()),
                                        ),
                                    ),
                                ),
                            ),
                    ),
                ),
            )
        server.createContext("/annc") { exchange: HttpExchange ->
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        return server
    }

    private fun argument(
        args: Array<String>,
        name: String,
    ): String? {
        val index = args.indexOf(name)
        return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
    }

    private const val PIECE_LENGTH = 256 * 1024
}
