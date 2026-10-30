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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B-151: no more downloads at once than the set allows; the rest wait in the order they came, and
 * a seed never waits.
 */
class TorrentSetQueueTest {
    private val root: Path = Files.createTempDirectory("kachok-queue")

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() {
        root.deleteRecursively()
    }

    /** A torrent whose data is nowhere, so it stays a download for as long as the test runs. */
    private fun torrent(name: String): Metainfo {
        val info =
            BDictionary(
                mapOf(
                    BString("length") to BInteger(PeerWire.BLOCK_SIZE.toLong() * 2),
                    BString("name") to BString(name),
                    BString("piece length") to BInteger(PeerWire.BLOCK_SIZE.toLong()),
                    BString("pieces") to BString(ByteArray(2 * Metainfo.HASH_SIZE) { (it + name.length).toByte() }),
                ),
            )
        return MetainfoParser.parse(
            Bencode.encode(
                BDictionary(
                    mapOf(
                        BString("announce") to BString("http://127.0.0.1:1/annc"),
                        BString("info") to info,
                    ),
                ),
            ),
        )
    }

    private fun <T> withSet(
        limits: SetLimits,
        body: suspend (TorrentSet, CoroutineScope) -> T,
    ): T =
        runBlocking {
            val dispatchers = EngineDispatchers()
            val job = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + dispatchers.io + job)
            val set = TorrentSet(dispatchers, scope, SetOptions(limits = limits))
            try {
                body(set, scope)
            } finally {
                set.close()
                job.cancelAndJoin()
                dispatchers.close()
            }
        }

    private suspend fun started(
        set: TorrentSet,
        scope: CoroutineScope,
        name: String,
    ): TorrentRuntime {
        val runtime = set.add(torrent(name), RuntimeOptions(directory = root, port = 0))
        runtime.restore()
        runtime.start(scope)
        return runtime
    }

    @Test
    fun aDownloadOverTheLimitWaitsFromItsFirstMomentAndGetsTheNextTurn(): Unit =
        withSet(SetLimits(maxActiveDownloads = 1)) { set, scope ->
            val first = started(set, scope, "first.bin")
            val second = started(set, scope, "second.bin")

            assertFalse(first.state.value.queued, "the first download was held")
            assertTrue(second.state.value.queued, "the second started rather than waiting")
            assertEquals(QueueStatus(active = 1, queued = 1, maxActive = 1), set.queueStatus)

            first.pause()
            withTimeout(TIMEOUT) { second.state.first { !it.queued && !it.paused } }
            assertEquals(QueueStatus(active = 1, queued = 0, maxActive = 1), set.queueStatus)
        }

    @Test
    fun raisingTheLimitReleasesAndLoweringItHoldsTheNewest(): Unit =
        withSet(SetLimits(maxActiveDownloads = 1)) { set, scope ->
            val first = started(set, scope, "first.bin")
            val second = started(set, scope, "second.bin")
            assertTrue(second.state.value.queued)

            set.limit(SetLimits(maxActiveDownloads = 2))
            withTimeout(TIMEOUT) { second.state.first { !it.queued } }

            set.limit(SetLimits(maxActiveDownloads = 1))
            withTimeout(TIMEOUT) { second.state.first { it.queued } }
            assertFalse(first.state.value.queued, "the older download lost its turn to the newer")

            set.limit(SetLimits())
            withTimeout(TIMEOUT) { second.state.first { !it.queued } }
            assertEquals(SetLimits(), set.limits.value)
        }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
