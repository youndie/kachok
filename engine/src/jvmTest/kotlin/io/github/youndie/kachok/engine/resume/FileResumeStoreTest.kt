package io.github.youndie.kachok.engine.resume

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.io.EngineDispatchers
import io.github.youndie.kachok.engine.picker.Bitfield
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The acceptance criteria of B-23's file half. */
class FileResumeStoreTest {
    private val root: Path = Files.createTempDirectory("kachok-resume")
    private val infoHash = InfoHash(ByteArray(20) { it.toByte() })
    private val otherHash = InfoHash(ByteArray(20) { (it + 1).toByte() })
    private val dispatchers = EngineDispatchers()
    private val failures = mutableListOf<String>()

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() {
        dispatchers.close()
        root.deleteRecursively()
    }

    private fun store(
        path: Path = root.resolve("state").resolve("torrent.resume"),
        hash: InfoHash = infoHash,
    ) = FileResumeStore(path, hash, pieceCount = 20, dispatcher = dispatchers.io) { failures += it }

    private fun record(vararg have: Int): ResumeRecord {
        val verified = Bitfield(20)
        have.forEach { verified.set(it) }
        return ResumeRecord(infoHash, verified, uploaded = 1, downloaded = 2)
    }

    @Test
    fun aSavedRecordComesBack(): Unit =
        runBlocking {
            val target = root.resolve("state").resolve("torrent.resume")
            assertNull(store().load(), "there is nothing to load yet")
            store().save(record(1, 2, 3))

            val read = assertNotNull(store().load())
            assertEquals(3, read.verified.cardinality)
            assertTrue(Files.exists(target))
            assertTrue(failures.isEmpty(), "unexpected failures: $failures")
        }

    @Test
    fun theTemporaryFileIsNotLeftBehind(): Unit =
        runBlocking {
            val target = root.resolve("state").resolve("torrent.resume")
            store().save(record(1))
            store().save(record(1, 2))
            val siblings = Files.list(target.parent).use { it.toList() }
            assertEquals(listOf(target.fileName), siblings.map { it.fileName }, "a .tmp survived")
        }

    @Test
    fun anInterruptedSaveLeavesTheOldRecordIntact(): Unit =
        runBlocking {
            // The point of the temporary file: what a reader sees is the old record or the new one.
            // This stands in for the crash by making the target a directory, so the move cannot
            // complete — the old record here is the previous save, which must survive.
            val target = root.resolve("state").resolve("torrent.resume")
            store().save(record(1, 2, 3))
            val before = Files.readAllBytes(target)

            val blocked = root.resolve("state").resolve("blocked.resume")
            Files.createDirectories(blocked)
            FileResumeStore(blocked, infoHash, 20, dispatchers.io) { failures += it }.save(record(4))

            assertContentEquals(before, Files.readAllBytes(target), "an unrelated record was disturbed")
            assertTrue(failures.any { it.contains("cannot save") }, "the failure was not reported: $failures")
            assertTrue(
                Files.list(blocked.parent).use { list -> list.noneMatch { it.fileName.toString().endsWith(".tmp") } },
                "a temporary file was left behind after a failed save",
            )
        }

    @Test
    fun aRecordForAnotherTorrentIsIgnoredAndKept(): Unit =
        runBlocking {
            val target = root.resolve("state").resolve("torrent.resume")
            store().save(record(1))
            assertNull(store(hash = otherHash).load(), "a record for another torrent is not ours to use")
            assertTrue(Files.exists(target), "and not ours to delete either")
            assertTrue(failures.any { it.contains("another torrent") }, "the reason was not reported")
        }

    @Test
    fun anUnreadableFileIsReportedRatherThanThrown(): Unit =
        runBlocking {
            val target = root.resolve("state").resolve("corrupt.resume")
            Files.createDirectories(target.parent)
            Files.write(target, "not bencode".encodeToByteArray())
            assertNull(FileResumeStore(target, infoHash, 20, dispatchers.io) { failures += it }.load())
            assertTrue(failures.any { it.contains("ignoring") }, "failures were $failures")
        }
}
