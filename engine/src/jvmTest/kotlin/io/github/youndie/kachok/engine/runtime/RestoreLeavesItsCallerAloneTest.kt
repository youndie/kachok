package io.github.youndie.kachok.engine.runtime

import io.github.youndie.kachok.engine.bencode.BDictionary
import io.github.youndie.kachok.engine.bencode.BInteger
import io.github.youndie.kachok.engine.bencode.BString
import io.github.youndie.kachok.engine.bencode.Bencode
import io.github.youndie.kachok.engine.io.EngineDispatchers
import io.github.youndie.kachok.engine.metainfo.Metainfo
import io.github.youndie.kachok.engine.metainfo.MetainfoParser
import io.github.youndie.kachok.engine.wire.PeerWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * `restore()` reads and hashes what is on the disk, and it must not do it on the thread that asked.
 *
 * The window calls it from its composition, which on the desktop is the AWT event thread. With the
 * check running there, a person's torrents — a hundred gigabytes of them — held that thread for
 * minutes: the title bar was drawn, nothing under it ever was, and no click was answered. Read off
 * a thread dump taken while the window was blank
 * ([B-115](../../../../../../../../docs/backlog/B-115-the-startup-check-runs-on-the-window-s-thread.md)),
 * which is the only way to see it — nothing throws, and the window looks like a rendering fault.
 *
 * The property, stated so that no measurement of "how long" is needed: **give the caller one
 * thread, start the check on it, and then take that thread away.** A check that needs it cannot
 * get through the file; a check that was handed to the engine's own dispatcher gets through it
 * regardless. What is waited for is the *session's* count of verified pieces and not the
 * caller's coroutine, because that coroutine ends by resuming on the thread being held —
 * which a correct fix does not change and a test that waited for it could never tell apart.
 */
class RestoreLeavesItsCallerAloneTest {
    private val root: Path = Files.createTempDirectory("kachok-restore")
    private val dispatchers = EngineDispatchers()
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private var set: TorrentSet? = null

    /** Sixty-four pieces, so the check is a real walk over the file and not one hash. */
    private val content = ByteArray(64 * PeerWire.BLOCK_SIZE) { (it * 31 and 0xFF).toByte() }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun clean() {
        set?.close()
        scope.cancel()
        dispatchers.close()
        root.deleteRecursively()
    }

    private fun metainfo(): Metainfo {
        val digest = MessageDigest.getInstance("SHA-1")
        val pieces = content.size / PeerWire.BLOCK_SIZE
        val hashes = ByteArray(pieces * Metainfo.HASH_SIZE)
        (0 until pieces).forEach { index ->
            digest.reset()
            digest.update(content, index * PeerWire.BLOCK_SIZE, PeerWire.BLOCK_SIZE)
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
        return MetainfoParser.parse(
            Bencode.encode(
                BDictionary(
                    mapOf(
                        BString("announce") to BString("http://tracker.invalid/announce"),
                        BString("info") to info,
                    ),
                ),
            ),
        )
    }

    @Test
    fun aRestoreDoesNotNeedTheThreadThatAskedForIt() =
        runBlocking {
            val torrent = metainfo()
            // The whole file, so every piece is read back and hashed: no resume record vouches for
            // any of it, which is exactly the case a person's first start after an update is.
            Files.write(root.resolve("payload.bin"), content)
            val open = TorrentSet(dispatchers = dispatchers, scope = scope).also { set = it }
            val runtime = open.add(torrent, RuntimeOptions(directory = root, port = 0))

            val one = Executors.newSingleThreadExecutor { Thread(it, "window") }
            try {
                val window = CoroutineScope(one.asCoroutineDispatcher() + SupervisorJob())
                val restoring = window.launch { runtime.restore() }
                // Queued behind it on that one thread, and holding it for longer than the wait
                // below. With the check running there, its own continuations queue behind this and
                // it cannot finish; with the check on the engine's dispatcher, this is irrelevant.
                window.launch {
                    try {
                        Thread.sleep(THREAD_HELD_MILLIS)
                    } catch (taken: InterruptedException) {
                        // The teardown takes the thread back before the sleep is over, which is the
                        // normal end of this coroutine and not a failure anybody has to hear about.
                        Thread.currentThread().interrupt()
                    }
                }

                withTimeout(WAIT_MILLIS) {
                    while (runtime.state.value.completedPieces < torrent.pieceCount) delay(20)
                }
                restoring.cancel()
            } finally {
                one.shutdownNow()
            }
        }

    private companion object {
        const val THREAD_HELD_MILLIS = 3_000L
        const val WAIT_MILLIS = 2_000L
    }
}
