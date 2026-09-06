package ru.workinprogress.kachok.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import ru.workinprogress.kachok.engine.InfoHash
import ru.workinprogress.kachok.engine.PeerId
import ru.workinprogress.kachok.engine.bencode.BDictionary
import ru.workinprogress.kachok.engine.bencode.BInteger
import ru.workinprogress.kachok.engine.bencode.BString
import ru.workinprogress.kachok.engine.bencode.Bencode
import ru.workinprogress.kachok.engine.tracker.AnnounceRequest
import ru.workinprogress.kachok.engine.tracker.HttpTrackerClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement
import kotlin.concurrent.thread
import kotlin.io.path.writeText

/**
 * What the shipped application can do, run by the shipped application.
 *
 * The packaged build carries a `jlink` runtime cut to the modules `nativeDistributions` names, and
 * **nothing ever ran it**: `:ui:run` and all 559 tests use the full JDK, and the trimmed runtime
 * exists only inside `createDistributable`, whose next step was to zip it. It shipped once without
 * `java.net.http` and died on somebody's first announce with `NoClassDefFoundError`
 * ([B-78](../../../../../../../../docs/backlog/B-78-nothing-runs-the-packaged-application.md)).
 *
 * `kachok --preflight <report>` is how the artifact is asked. It goes through the real launcher, so
 * the runtime, the classpath and the JVM flags are the ones a person downloads — not a copy of them
 * assembled by the check. There is no `java` in the image to run anything else with: `jlink` strips
 * the launchers, and `runtime/` has no `bin/` at all.
 *
 * Each check **executes** its path rather than asking whether a module is present. A module named
 * in the image is present by construction — `jlink` would not have linked otherwise — so a check
 * that reads the boot layer passes for every image the build can produce and fails for none of
 * them.
 *
 * Not covered: that the window opens. That needs a display, and the goldens stand in for it.
 */
internal fun preflight(reportTo: Path?): Int {
    val lines = mutableListOf<String>()
    var failed: String? = null
    for ((name, check) in checks) {
        val line =
            try {
                "ok    $name — ${check()}"
            } catch (failure: Throwable) {
                failed = failed ?: name
                "FAIL  $name — ${failure::class.qualifiedName}: ${failure.message}"
            }
        lines += line
        println(line)
    }
    val verdict =
        if (failed == null) {
            "the packaged runtime carries every path the client takes"
        } else {
            "the packaged runtime cannot do: $failed"
        }
    lines += verdict
    println(verdict)
    // A file as well as the console, because the console is not something a build can point at:
    // the report is `checkDistributable`'s declared output, which is what lets Gradle skip the task
    // when nothing has changed and what a failed run leaves behind to read afterwards. (The
    // launcher's stdout does reach the caller on Windows too — measured, against the expectation
    // that a GUI subsystem executable would swallow it.)
    reportTo?.writeText(lines.joinToString("\n", postfix = "\n"))
    return if (failed == null) 0 else 1
}

private val checks: List<Pair<String, () -> String>> =
    listOf(
        "java.net.http, through the client that broke" to ::announceOverLoopback,
        "elliptic curve, agreed rather than named" to ::agreeOnACurve,
        "udp, the other half of a tracker" to ::datagramRoundTrip,
        "sun.misc.Unsafe, which jdk.unsupported carries" to ::unsafeIsLoadable,
        "sha-1, every piece the client verifies" to ::sha1OfAKnownVector,
    )

/**
 * A real announce, at the real class, against a socket pretending to be a tracker.
 *
 * `HttpClient` constructed directly would prove the module is linked. This proves the path the
 * `NoClassDefFoundError` was actually thrown on: `HttpTrackerClient` builds the URL, sends, and
 * parses a bencoded body, and every class it touches on the way has to be in the image.
 */
private fun announceOverLoopback(): String {
    val body =
        Bencode.encode(
            BDictionary(
                mapOf(
                    BString("interval") to BInteger(1800),
                    // No peers: the compact list has its own parser and its own tests, and this is
                    // asking whether the request left and the answer came back.
                    BString("peers") to BString(ByteArray(0)),
                ),
            ),
        )
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
        thread(isDaemon = true, name = "preflight-tracker") {
            runCatching {
                server.accept().use { socket ->
                    // Read the request line and headers, then answer. Not parsed: what is being
                    // measured is that a request arrived at all.
                    val input = socket.getInputStream().bufferedReader()
                    while (input.readLine().orEmpty().isNotEmpty()) {
                        // the request line and its headers, read to the blank line and discarded
                    }
                    socket.getOutputStream().apply {
                        write(
                            (
                                "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n" +
                                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                            ).toByteArray(),
                        )
                        write(body)
                        flush()
                    }
                }
            }
        }
        val response =
            runBlocking {
                HttpTrackerClient(Dispatchers.IO).announce(
                    "http://${server.inetAddress.hostAddress}:${server.localPort}/announce",
                    AnnounceRequest(
                        infoHash = InfoHash(ByteArray(InfoHash.SIZE) { it.toByte() }),
                        peerId = PeerId(ByteArray(PeerId.SIZE) { it.toByte() }),
                        port = 6881,
                        uploaded = 0,
                        downloaded = 0,
                        left = 1,
                    ),
                )
            }
        check(response.interval == 1800) { "the tracker answered interval ${response.interval}" }
        return "announced and read back interval ${response.interval}"
    }
}

/**
 * Two key pairs and a shared secret, on the curve every `https://` tracker negotiates.
 *
 * `jdk.crypto.ec` was added to the image once on the reasoning that a JCA provider is loaded by
 * name and cannot be found by `jdeps`; research §1.3e measured that the provider moved into
 * `java.base` on this JDK and removed it again. This is that measurement kept: it does the key
 * agreement rather than looking the provider up, so an image where the curve is gone fails here
 * instead of at somebody's first HTTPS announce.
 */
private fun agreeOnACurve(): String {
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(ECGenParameterSpec("secp256r1"))
    val ours = generator.generateKeyPair()
    val theirs = generator.generateKeyPair()
    val agreement = KeyAgreement.getInstance("ECDH")
    agreement.init(ours.private)
    agreement.doPhase(theirs.public, true)
    val secret = agreement.generateSecret()
    check(secret.size == 32) { "an secp256r1 secret is 32 bytes, got ${secret.size}" }
    return "secp256r1 via ${generator.provider.name}, 32-byte secret"
}

/** A datagram to itself: the UDP tracker path, which no HTTP check touches. */
private fun datagramRoundTrip(): String {
    DatagramChannel.open(StandardProtocolFamily.INET).use { channel ->
        channel.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val sent = byteArrayOf(0, 0, 4, 23, 39, 16, 25, -128)
        channel.send(ByteBuffer.wrap(sent), channel.localAddress)
        val received = ByteBuffer.allocate(sent.size)
        channel.receive(received)
        check(received.array().contentEquals(sent)) { "the datagram came back changed" }
        return "bound ${channel.localAddress} and round-tripped ${sent.size} bytes"
    }
}

/** Coroutines and the buffer pool reach for it; it is in `jdk.unsupported` and nowhere else. */
private fun unsafeIsLoadable(): String = Class.forName("sun.misc.Unsafe").name

/**
 * The digest, against a vector rather than against itself.
 *
 * `MessageDigest.getInstance("SHA-1")` succeeding says a provider answered. What a piece check
 * needs is the right answer, and the empty string's SHA-1 is the cheapest one that exists.
 */
private fun sha1OfAKnownVector(): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(ByteArray(0))
    val hex = digest.joinToString("") { "%02x".format(it) }
    check(hex == "da39a3ee5e6b4b0d3255bfef95601890afd80709") { "SHA-1 of nothing came out $hex" }
    return "SHA-1 of the empty string is its own known value"
}
