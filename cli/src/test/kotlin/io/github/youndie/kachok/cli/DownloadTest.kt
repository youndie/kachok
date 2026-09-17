package io.github.youndie.kachok.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.youndie.kachok.engine.bencode.BDictionary
import io.github.youndie.kachok.engine.bencode.BInteger
import io.github.youndie.kachok.engine.bencode.BString
import io.github.youndie.kachok.engine.bencode.Bencode
import io.github.youndie.kachok.engine.io.EngineDispatchers
import io.github.youndie.kachok.engine.metainfo.Metainfo
import io.github.youndie.kachok.engine.metainfo.MetainfoParser
import io.github.youndie.kachok.engine.runtime.RuntimeOptions
import io.github.youndie.kachok.engine.runtime.TorrentSet
import io.github.youndie.kachok.engine.wire.ExtensionHandshake
import io.github.youndie.kachok.engine.wire.Message
import io.github.youndie.kachok.engine.wire.PeerWire
import io.github.youndie.kachok.swarm.SeedingPeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        // The exact set, deliberately: this assertion has caught both extensions being added, and
        // "contains ut_pex" would have caught neither. `ut_pex` since B-34 — the torrent is not
        // private, which is the only condition on it — and `ut_metadata` since B-45.
        assertEquals(
            setOf(ExtensionHandshake.UT_PEX, ExtensionHandshake.UT_METADATA),
            read.extensions.keys,
            "what this client offers a peer",
        )
        assertTrue((read.metadataSize ?: 0) > 0, "ut_metadata without metadata_size tells a peer nothing")
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
    fun theDhtIsJoinedUnlessTheCommandLineRefusesIt() {
        // **This test used to assert the opposite and was right to, until it was measured.**
        // `torrent.ubuntu.com` hands out one peer per announce whatever `numwant` asks for, against
        // a swarm of 526: a client without the DHT does not get a small share of a public swarm, it
        // gets one address (B-99).
        assertTrue(Arguments.parseDownload(listOf("x.torrent")).dht, "the DHT is joined by default")
        assertTrue(!Arguments.parseDownload(listOf("x.torrent", "--no-dht")).dht, "--no-dht stays out")
    }

    /**
     * The half of the old reason that has not expired, and the reason it is asserted here.
     *
     * A default of "on" in the *engine* would mean every run of this suite contacting three public
     * bootstrap routers, because ten tests build a `TorrentSet` with its defaults. What a person
     * running the client gets and what a library does when it is constructed are different
     * questions, and this is the line that keeps them apart.
     */
    @Test
    fun theEngineItselfStillJoinsNothingUnlessTold() {
        assertTrue(
            !io.github.youndie.kachok.engine.runtime
                .SetOptions()
                .dht,
            "the library's own default must stay off",
        )
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

    /**
     * `--seed` keeps the set alive, so a second client can actually download from the seeder.
     *
     * The set used to be closed in a `finally` right after the download settled — before the
     * seeding branch — so the seeder had no listener to be reached on and no open files to serve
     * from, and sat at "seeding" looking exactly like a seeder
     * ([B-109](../../../../../../../../docs/backlog/B-109-download-seed-closes-the-set-before-it-seeds.md)).
     * What proves it is the other side finishing, not this side's log; and the seeder is then
     * stopped the way Ctrl-C stops it, through the same interrupt path a cut-short download takes.
     */
    @Test
    fun aSeedingDownloadServesASecondClientUntilItIsInterrupted(): Unit =
        runBlocking {
            // The seeder's port is known only once it prints it, so the tracker's answer is decided late —
            // and never handed to the seeder itself, which would otherwise dial its own port.
            var advertised: Int? = null
            val started = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            started.createContext("/annc") { exchange: HttpExchange ->
                val asking =
                    Regex("port=(\\d+)")
                        .find(exchange.requestURI.rawQuery.orEmpty())
                        ?.groupValues
                        ?.get(1)
                        ?.toInt()
                val port = advertised
                val packed =
                    if (port != null && port != asking) {
                        byteArrayOf(127, 0, 0, 1, ((port shr 8) and 0xFF).toByte(), (port and 0xFF).toByte())
                    } else {
                        ByteArray(0)
                    }
                val body =
                    Bencode.encode(
                        BDictionary(mapOf(BString("interval") to BInteger(1800), BString("peers") to BString(packed))),
                    )
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            started.start()
            server = started
            val trackerUrl = "http://127.0.0.1:${started.address.port}/annc"
            val torrent = root.resolve("fixture.torrent")
            Files.write(torrent, torrentBytes(trackerUrl))
            val seedDir = root.resolve("seed").also { Files.createDirectories(it) }
            Files.write(seedDir.resolve("payload.bin"), content)

            // Buffers, not builders: the seeder writes from its own thread while this one reads.
            val out = StringBuffer()
            val err = StringBuffer()
            val seeding =
                async(Dispatchers.Default) {
                    Download(
                        Arguments.parseDownload(
                            listOf(torrent.toString(), "--dir", seedDir.toString(), "--seed", "--no-dht"),
                        ),
                        out,
                        err,
                    ).run(this)
                }
            withTimeout(30_000) { while (!out.toString().contains("seeding")) delay(50) }
            advertised = Regex("listening on port (\\d+)").find(out.toString())!!.groupValues[1].toInt()

            val dispatchers = EngineDispatchers()
            val job = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + dispatchers.io + job)
            val set = TorrentSet(dispatchers, scope)
            try {
                val leecher =
                    set.add(
                        MetainfoParser.parse(torrentBytes(trackerUrl)),
                        RuntimeOptions(directory = root.resolve("leech")),
                    )
                leecher.restore()
                leecher.start(scope)
                withTimeout(60_000) { while (!leecher.state.value.isComplete) delay(50) }
                assertContentEquals(
                    content,
                    Files.readAllBytes(root.resolve("leech").resolve("payload.bin")),
                    "the second client's file is not the torrent's",
                )
            } finally {
                set.close()
                job.cancelAndJoin()
                dispatchers.close()
            }

            // Ctrl-C, as a test can send it: the seeding wait is cancelled, and the seeder stops.
            seeding.cancel()
            seeding.join()
            assertContains(out.toString(), "seeding")
            assertEquals("", err.toString(), "the seeder complained on the way out")
        }
}
