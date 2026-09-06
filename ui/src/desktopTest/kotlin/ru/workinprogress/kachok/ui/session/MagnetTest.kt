package ru.workinprogress.kachok.ui.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.workinprogress.kachok.engine.io.EngineDispatchers
import ru.workinprogress.kachok.engine.metainfo.MagnetParser
import ru.workinprogress.kachok.engine.runtime.RuntimeOptions
import ru.workinprogress.kachok.engine.runtime.TorrentSet
import ru.workinprogress.kachok.engine.runtime.fetchMetainfo
import ru.workinprogress.kachok.swarm.LocalSwarm
import ru.workinprogress.kachok.ui.list.TorrentState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The acceptance criterion of
 * [B-55](../../../../../../../../docs/backlog/B-55-magnets-in-the-window.md): a magnet becomes a
 * *Metadata* row, then a downloading one.
 *
 * Everything below the window is real, including the part that is easy to fake: the peer serves
 * the `info` dictionary over BEP 9 on a socket, so what is proved is that the fetcher, the
 * extension handshake and the info-hash check agree with a peer rather than with a stub.
 */
class MagnetTest {
    private val root: Path = Files.createTempDirectory("kachok-magnet")
    private var swarm: LocalSwarm? = null

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() {
        swarm?.close()
        root.deleteRecursively()
    }

    private fun magnetFor(local: LocalSwarm): String {
        val hex =
            local.metainfo.infoHash.bytes
                .joinToString("") { (it.toInt() and BYTE).toString(HEX).padStart(2, '0') }
        return "magnet:?xt=urn:btih:$hex&dn=payload.bin&tr=${local.trackerUrl}"
    }

    /** Before the fetch returns there is a row, and it says only what a magnet knows. */
    @Test
    fun aMagnetIsARowBeforeItIsATorrent() {
        val link = MagnetParser.parse("magnet:?xt=urn:btih:${"ab".repeat(HASH_BYTES)}&dn=archlinux.iso")
        val row = magnetRow(link)
        assertEquals(TorrentState.Metadata, row.state)
        assertEquals("archlinux.iso", row.name)
        assertEquals(Figures.DASH, row.size)
        assertEquals(Figures.DASH, row.ratio)
        assertEquals(Figures.DASH, row.eta)
        assertNull(row.progress, "an indeterminate bar, not a percentage of nothing")
    }

    /** And with no `dn`, the info hash is what the name column falls back to. */
    @Test
    fun aMagnetWithNoNameShowsItsHashWhereTheNameGoes() {
        val link = MagnetParser.parse("magnet:?xt=urn:btih:${"ab".repeat(HASH_BYTES)}")
        assertEquals("abab…abab", magnetRow(link).name)
    }

    @Test
    fun aMagnetBecomesATorrentAndDownloadsIt(): Unit =
        runBlocking {
            val local = LocalSwarm.start(delayPerBlockMillis = 5, serveMetadata = true).also { swarm = it }
            val dispatchers = EngineDispatchers()
            val job = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + dispatchers.io + job)
            val set = TorrentSet(dispatchers, scope)
            try {
                val link = MagnetParser.parse(magnetFor(local))
                // The row a person sees while this is happening.
                assertEquals(TorrentState.Metadata, magnetRow(link).state)

                val metainfo =
                    withTimeout(30.seconds) {
                        fetchMetainfo(link, scope, dispatchers, set.listenPort)
                    }
                assertContentEquals(
                    local.metainfo.infoHash.bytes,
                    metainfo.infoHash.bytes,
                    "the metadata hashes to the hash the magnet named",
                )
                assertEquals(local.metainfo.totalLength, metainfo.totalLength)
                assertEquals(local.metainfo.pieceCount, metainfo.pieceCount)

                val runtime =
                    set.add(metainfo, RuntimeOptions(directory = root)).also {
                        it.restore()
                        it.start(scope)
                    }
                withTimeout(30.seconds) {
                    while (!runtime.state.value.isComplete) delay(SAMPLE)
                }
                val state = runtime.state.value
                val row = rowOf(state, ratesOf(state))
                assertEquals(TorrentState.Seeding, row.state)
                assertEquals("payload.bin", row.name, "the name came out of the metainfo, not the magnet")
                assertTrue(local.served > 0, "and the content came off the wire after it")
                assertContentEquals(
                    local.content,
                    Files.readAllBytes(root.resolve("payload.bin")),
                    "byte for byte",
                )
            } finally {
                set.torrents.forEach { it.stop() }
                set.close()
                job.cancelAndJoin()
                dispatchers.close()
            }
        }

    private companion object {
        val SAMPLE = 50.milliseconds
        const val BYTE = 0xFF
        const val HEX = 16
        const val HASH_BYTES = 20
    }
}
