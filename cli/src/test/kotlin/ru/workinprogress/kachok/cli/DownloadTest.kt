package ru.workinprogress.kachok.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import ru.workinprogress.kachok.engine.bencode.BDictionary
import ru.workinprogress.kachok.engine.bencode.BInteger
import ru.workinprogress.kachok.engine.bencode.BString
import ru.workinprogress.kachok.engine.bencode.Bencode
import ru.workinprogress.kachok.engine.metainfo.Metainfo
import ru.workinprogress.kachok.engine.metainfo.MetainfoParser
import ru.workinprogress.kachok.engine.wire.ExtensionHandshake
import ru.workinprogress.kachok.engine.wire.Message
import ru.workinprogress.kachok.engine.wire.PeerWire
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The acceptance criteria of B-18, and the first time every part of this engine runs together.
 *
 * The swarm is real: an HTTP tracker on localhost that names a seeding peer, and a peer that
 * speaks BEP 3 over a socket and serves the bytes it claims to have. Nothing is mocked below the
 * command line, so a passing run means the codec, the transport, the picker, the hasher, the
 * writer, the tracker client and the session agree with each other *and* with the protocol.
 */
class DownloadTest {
    private val root: Path = Files.createTempDirectory("kachok-cli")
    private var server: HttpServer? = null
    private var seed: SeedingPeer? = null

    /** 40 000 bytes at a 16 KiB piece length: three pieces, the last one short. */
    private val content = ByteArray(40_000) { (it * 7 and 0xFF).toByte() }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() {
        server?.stop(0)
        seed?.close()
        root.deleteRecursively()
    }

    private fun torrentBytes(trackerUrl: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-1")
        val pieces = (content.size + PeerWire.BLOCK_SIZE - 1) / PeerWire.BLOCK_SIZE
        val hashes = ByteArray(pieces * Metainfo.HASH_SIZE)
        (0 until pieces).forEach { index ->
            val from = index * PeerWire.BLOCK_SIZE
            val to = minOf(from + PeerWire.BLOCK_SIZE, content.size)
            digest.reset()
            digest.update(content, from, to - from)
            digest.digest().copyInto(hashes, index * Metainfo.HASH_SIZE)
        }
        val info =
            BDictionary(
                mapOf(
                    BString("length") to BInteger(content.size.toLong()),
                    BString("name") to BString("payload.bin"),
                    BString("piece length") to BInteger(PeerWire.BLOCK_SIZE.toLong()),
                    BString("pieces") to BString(hashes),
                ),
            )
        return Bencode.encode(
            BDictionary(mapOf(BString("announce") to BString(trackerUrl), BString("info") to info)),
        )
    }

    /** A tracker that answers with one compact peer: the seed. */
    private fun startTracker(
        peerPort: Int?,
        failure: String? = null,
    ): String {
        val started = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        started.createContext("/annc") { exchange: HttpExchange ->
            val body =
                when {
                    failure != null -> {
                        Bencode.encode(BDictionary(mapOf(BString("failure reason") to BString(failure))))
                    }

                    else -> {
                        val packed =
                            byteArrayOf(
                                127,
                                0,
                                0,
                                1,
                                ((peerPort!! shr 8) and 0xFF).toByte(),
                                (peerPort and 0xFF).toByte(),
                            )
                        Bencode.encode(
                            BDictionary(
                                mapOf(
                                    BString("interval") to BInteger(1800),
                                    BString("peers") to BString(packed),
                                ),
                            ),
                        )
                    }
                }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        started.start()
        server = started
        return "http://127.0.0.1:${started.address.port}/annc"
    }

    @Test
    fun aTorrentIsDownloadedFromALocalSwarmAndTheBytesMatch() {
        val out = StringBuilder()
        val err = StringBuilder()

        val placeholder = startTracker(peerPort = 1)
        val peer =
            SeedingPeer(
                infoHash = MetainfoParser.parse(torrentBytes(placeholder)).infoHash,
                content = content,
                pieceLength = PeerWire.BLOCK_SIZE,
            )
        seed = peer
        server?.stop(0)
        val trackerUrl = startTracker(peerPort = peer.port)

        val torrent = root.resolve("fixture.torrent")
        Files.write(torrent, torrentBytes(trackerUrl))

        val exit =
            Cli.run(
                listOf("download", torrent.toString(), "--dir", root.resolve("out").toString()),
                out,
                err,
            )

        assertEquals(Download.EXIT_OK, exit, "exit code; stderr was: $err")
        val downloaded = root.resolve("out").resolve("payload.bin")
        assertTrue(Files.exists(downloaded), "the file was never created")
        assertContentEquals(content, Files.readAllBytes(downloaded), "the bytes on disk are not the torrent's")
        assertContains(out.toString(), "complete")
        assertTrue(peer.served.isNotEmpty(), "nothing was requested; the file cannot have come from the swarm")
        assertEquals(
            content.size,
            peer.served.sumOf { it.length },
            "every byte was requested exactly once — no duplicate requests outside endgame",
        )
    }

    @Test
    fun aPeerThatAdvertisesBep10IsSentAnExtensionHandshakeOverTheRealSocket() {
        // In-process tests prove the session decides to send it; this proves the reserved bit
        // actually leaves the socket and that message id 20 frames the way a peer reads it.
        val placeholder = startTracker(peerPort = 1)
        val peer =
            SeedingPeer(
                infoHash = MetainfoParser.parse(torrentBytes(placeholder)).infoHash,
                content = content,
                pieceLength = PeerWire.BLOCK_SIZE,
                extensionProtocol = true,
            )
        seed = peer
        server?.stop(0)
        val trackerUrl = startTracker(peerPort = peer.port)
        val torrent = root.resolve("fixture.torrent")
        Files.write(torrent, torrentBytes(trackerUrl))

        val err = StringBuilder()
        val exit =
            Cli.run(
                listOf("download", torrent.toString(), "--dir", root.resolve("out").toString()),
                StringBuilder(),
                err,
            )

        assertEquals(Download.EXIT_OK, exit, "stderr was: $err")
        val handshake = peer.extended.firstOrNull()
        assertTrue(handshake != null, "the peer advertised BEP 10 and was sent no extended message")
        assertEquals(ExtensionHandshake.HANDSHAKE_ID, handshake.extensionId)
        val read = ExtensionHandshake.decode(handshake.payload)
        // `ut_pex` since B-34, under the id this client chose. The torrent is not private, which is
        // the only condition on offering it.
        assertEquals(
            setOf(ExtensionHandshake.UT_PEX),
            read.extensions.keys,
            "what this client offers a peer",
        )
        assertContains(read.clientVersion ?: "", "kachok")
    }

    @Test
    fun aPeerThatAdvertisesBep6IsToldWhatThisClientHasInOneByte() {
        // The client starts with nothing, so BEP 6's answer is `have none` — which a BEP 3 client
        // does not send at all. Over a real socket, so the reserved byte is the one that went out.
        val placeholder = startTracker(peerPort = 1)
        val peer =
            SeedingPeer(
                infoHash = MetainfoParser.parse(torrentBytes(placeholder)).infoHash,
                content = content,
                pieceLength = PeerWire.BLOCK_SIZE,
                fastExtension = true,
            )
        seed = peer
        server?.stop(0)
        val trackerUrl = startTracker(peerPort = peer.port)
        val torrent = root.resolve("fixture.torrent")
        Files.write(torrent, torrentBytes(trackerUrl))

        val err = StringBuilder()
        val exit =
            Cli.run(
                listOf("download", torrent.toString(), "--dir", root.resolve("out").toString()),
                StringBuilder(),
                err,
            )

        assertEquals(Download.EXIT_OK, exit, "stderr was: $err")
        val opening = peer.received.firstOrNull { it !is Message.Extended }
        assertTrue(opening === Message.HaveNone, "the client opened with $opening, not `have none`")
    }

    @Test
    fun anUnreachableTrackerExitsOneWithTheTrackersOwnWords() {
        val out = StringBuilder()
        val err = StringBuilder()
        val trackerUrl = startTracker(peerPort = null, failure = "forbidden")
        val torrent = root.resolve("fixture.torrent")
        Files.write(torrent, torrentBytes(trackerUrl))

        val exit =
            Cli.run(
                listOf("download", torrent.toString(), "--dir", root.resolve("out").toString()),
                out,
                err,
            )

        assertEquals(Download.EXIT_FAILED, exit)
        assertContains(err.toString(), "forbidden")
    }

    @Test
    fun aTorrentThatCannotBeReadFailsWithoutAStackTrace() {
        val out = StringBuilder()
        val err = StringBuilder()
        val exit = Cli.run(listOf("download", root.resolve("absent.torrent").toString()), out, err)
        assertEquals(Download.EXIT_FAILED, exit)
        assertContains(err.toString(), "cannot read")
    }

    @Test
    fun aMissingFileArgumentIsAUsageError() {
        val err = StringBuilder()
        assertEquals(Download.EXIT_USAGE, Cli.run(listOf("download"), StringBuilder(), err))
        assertContains(err.toString(), "needs a .torrent file")
        assertContains(err.toString(), "kachok download")
    }

    @Test
    fun aMagnetLinkIsASourceAndNotAPath() {
        val magnet = Arguments.parseDownload(listOf("magnet:?xt=urn:btih:${"a".repeat(40)}&dn=x"))
        assertTrue(magnet.source is TorrentSource.Magnet)

        val file = Arguments.parseDownload(listOf("x.torrent"))
        assertTrue(file.source is TorrentSource.File)

        // A magnet that will not parse is the *download* failing, not the command line: the link
        // is a well-formed argument that turns out to name nothing.
        val err = StringBuilder()
        assertEquals(Download.EXIT_FAILED, Cli.run(listOf("download", "magnet:?xt=nonsense"), StringBuilder(), err))
        assertContains(err.toString(), "not a usable magnet link")
    }

    @Test
    fun theDhtIsOffUnlessAskedFor() {
        // Joining it means contacting three public routers and announcing this machine to
        // strangers. Nothing in phase 1 needs that — every torrent this client can open names a
        // tracker — and a default of "on" would also mean every run of this suite doing it.
        assertTrue(!Arguments.parseDownload(listOf("x.torrent")).dht)
        assertTrue(Arguments.parseDownload(listOf("x.torrent", "--dht")).dht)
    }

    @Test
    fun rateLimitsAreGivenInKibibytesAndZeroIsTheDefault() {
        val plain = Arguments.parseDownload(listOf("x.torrent"))
        assertEquals(0L, plain.uploadLimit, "no limit is the default, and it is not zero bytes a second")
        assertEquals(0L, plain.downloadLimit)

        val limited = Arguments.parseDownload(listOf("x.torrent", "--up", "512", "--down", "2048"))
        assertEquals(512L * 1024, limited.uploadLimit)
        assertEquals(2048L * 1024, limited.downloadLimit)

        listOf(listOf("x.torrent", "--up"), listOf("x.torrent", "--down", "lots")).forEach { arguments ->
            assertFailsWith<UsageException>("$arguments should not parse") { Arguments.parseDownload(arguments) }
        }
    }

    @Test
    fun unknownOptionsAndCommandsAreUsageErrors() {
        listOf(
            listOf("download", "x.torrent", "--nope"),
            listOf("download", "x.torrent", "--port"),
            listOf("download", "x.torrent", "--port", "zero"),
            listOf("frobnicate"),
            emptyList(),
        ).forEach { arguments ->
            val err = StringBuilder()
            assertEquals(
                Download.EXIT_USAGE,
                Cli.run(arguments, StringBuilder(), err),
                "arguments $arguments should be a usage error",
            )
            assertContains(err.toString(), "kachok download")
        }
    }
}
