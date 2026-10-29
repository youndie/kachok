package io.github.youndie.kachok.engine.runtime

import io.github.youndie.kachok.engine.bencode.BDictionary
import io.github.youndie.kachok.engine.bencode.BInteger
import io.github.youndie.kachok.engine.bencode.BString
import io.github.youndie.kachok.engine.bencode.Bencode
import io.github.youndie.kachok.engine.hex
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
import java.security.MessageDigest
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Resume records kept in the set's own directory rather than beside the downloads
 * ([B-150](../../../../../../../../docs/backlog/B-150-resume-records-live-with-the-client.md)).
 *
 * A download folder is the person's; a `.resume` beside every film in it is litter. What is
 * asserted: the record is written where the set says and not in the folder, a record still beside
 * the files from before is trusted and then taken away, and removing a torrent with its data takes
 * the record with it.
 */
class TorrentSetResumeDirectoryTest {
    private val root: Path = Files.createTempDirectory("kachok-resume")
    private val downloads: Path = root.resolve("downloads")
    private val records: Path = root.resolve("resume")

    private val content = ByteArray(40_000) { (it * 13 and 0xFF).toByte() }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun metainfo(): Metainfo {
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
                    BString("name") to BString("film.mkv"),
                    BString("piece length") to BInteger(PeerWire.BLOCK_SIZE.toLong()),
                    BString("pieces") to BString(hashes),
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
        resumeDirectory: Path?,
        body: suspend (TorrentSet, CoroutineScope) -> T,
    ): T =
        runBlocking {
            val dispatchers = EngineDispatchers()
            val job = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + dispatchers.io + job)
            val set = TorrentSet(dispatchers, scope, SetOptions(resumeDirectory = resumeDirectory))
            try {
                body(set, scope)
            } finally {
                set.close()
                job.cancelAndJoin()
                dispatchers.close()
            }
        }

    /** The torrent added, checked and started paused, which is a session that can be stopped. */
    private suspend fun running(
        set: TorrentSet,
        scope: CoroutineScope,
    ): TorrentRuntime {
        val runtime = set.add(metainfo(), RuntimeOptions(directory = downloads, port = 0))
        runtime.restore()
        runtime.start(scope, paused = true)
        withTimeout(TIMEOUT) { runtime.state.first { it.paused } }
        return runtime
    }

    /** Stops it and waits for the stop, which is what writes the record. */
    private suspend fun stopped(runtime: TorrentRuntime) {
        runtime.stop()
        withTimeout(TIMEOUT) { runtime.awaitStopped() }
        runtime.close()
    }

    private fun onDisk() {
        Files.createDirectories(downloads)
        Files.write(downloads.resolve("film.mkv"), content)
    }

    private fun recordsBesideTheFiles(): List<String> =
        downloads.listDirectoryEntries().map { it.name }.filter { it.endsWith(".resume") }

    @Test
    fun theRecordIsKeptInTheSetsDirectoryAndNotBesideTheFiles(): Unit =
        withSet(records) { set, scope ->
            onDisk()
            val runtime = running(set, scope)
            stopped(runtime)

            assertEquals(records.resolve("${runtime.metainfo.infoHash.hex()}.resume"), runtime.resumePath)
            assertTrue(Files.exists(runtime.resumePath), "the record was not written to the set's directory")
            assertEquals(emptyList(), recordsBesideTheFiles(), "a record was written beside the download")
        }

    @Test
    fun aRecordBesideTheFilesIsTrustedAndThenTakenAway() {
        onDisk()
        // The record as an older client left it: beside the files.
        withSet(resumeDirectory = null) { set, scope -> stopped(running(set, scope)) }
        val legacy = downloads.resolve(TorrentRuntime.resumeName(metainfo()))
        assertTrue(Files.exists(legacy), "the fixture wrote no record beside the files")

        withSet(records) { set, scope ->
            // A byte the record vouches for is changed. A check that trusted the record does not read
            // it and calls the torrent complete; one that ignored the record would find the piece bad.
            Files.write(downloads.resolve("film.mkv"), content.copyOf().also { it[0] = (it[0] + 1).toByte() })
            val runtime = set.add(metainfo(), RuntimeOptions(directory = downloads, port = 0))
            runtime.restore()
            assertTrue(runtime.state.value.isComplete, "the record beside the files was not read")
            runtime.start(scope, paused = true)
            withTimeout(TIMEOUT) { runtime.state.first { it.paused } }
            stopped(runtime)

            assertTrue(Files.exists(runtime.resumePath), "the record did not reach the set's directory")
            assertFalse(Files.exists(legacy), "the old record stayed beside the files")
        }
    }

    @Test
    fun removingATorrentWithItsDataTakesTheRecordAndWithoutItKeepsIt(): Unit =
        withSet(records) { set, scope ->
            onDisk()
            val kept = running(set, scope)
            set.remove(kept)
            withTimeout(TIMEOUT) { kept.awaitStopped() }
            assertTrue(Files.exists(kept.resumePath), "a plain removal took the record, so a re-add hashes it all")

            val forgotten = running(set, scope)
            set.remove(forgotten, forgetProgress = true)
            assertFalse(Files.exists(forgotten.resumePath), "the record outlived the data it vouched for")
        }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
