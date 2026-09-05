package ru.workinprogress.kachok.engine.storage

import ru.workinprogress.kachok.engine.metainfo.MetainfoParser
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The acceptance criteria of B-12's file half: files are created at their full length and are
 * sparse, so a fresh torrent costs a few blocks rather than its own size.
 */
class FileSetTest {
    private val threeFiles =
        "d4:infod5:filesld6:lengthi1000e4:pathl5:a.bineed6:lengthi1e4:pathl3:sub5:b.bineed6:lengthi99" +
            "9e4:pathl3:sub5:c.bineee4:name6:bundle12:piece lengthi512e6:pieces80:AAAAAAAAAAAAAAAAAAAABBB" +
            "BBBBBBBBBBBBBBBBBCCCCCCCCCCCCCCCCCCCCDDDDDDDDDDDDDDDDDDDDee"

    /** One file of 8 MiB, so that preallocation and sparseness differ by more than rounding. */
    private val eightMegabytes =
        "d4:infod6:lengthi8388608e4:name3:big12:piece " +
            "lengthi8388608e6:pieces20:AAAAAAAAAAAAAAAAAAAAee"

    private val metainfo = MetainfoParser.parse(threeFiles.encodeToByteArray())
    private val root: Path = Files.createTempDirectory("kachok-fileset")

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun aSingleFileTorrentIsAFileAndNotADirectoryOfThatName() {
        // BEP 3: `name` is the file in the single-file case. Reading it as a directory writes the
        // download one level too deep, into a directory named after the file it should have been —
        // which is what the first version of this class did, found by the end-to-end test.
        val single = MetainfoParser.parse(eightMegabytes.encodeToByteArray())
        FileSet.open(root, single).use { files ->
            assertEquals(root.resolve("big"), files.paths.single())
            assertTrue(Files.isRegularFile(files.paths.single()))
        }
    }

    @Test
    fun everyFileIsCreatedUnderTheTorrentNameWithItsFullLength() {
        FileSet.open(root, metainfo).use { files ->
            assertEquals(3, files.paths.size)
            assertTrue(files.paths[0].endsWith(Path.of("bundle", "a.bin")))
            assertTrue(files.paths[1].endsWith(Path.of("bundle", "sub", "b.bin")))
            metainfo.files.forEachIndexed { index, file ->
                assertEquals(file.length, Files.size(files.paths[index]), "length of ${files.paths[index]}")
            }
        }
    }

    /**
     * The point of not preallocating, asserted against a file big enough for the difference to be
     * visible: a sparse 8 MB file occupies nothing until blocks arrive, a preallocated one occupies
     * 8 MB.
     *
     * The check shells out to `du`. The JDK exposes no allocated-block count portably — the unix
     * attribute view on macOS offers `size` and not `blocks` — and a test that quietly skipped
     * would be indistinguishable from one that passed.
     */
    @Test
    fun aFreshFileIsSparseRatherThanPreallocated() {
        assertTrue(isUnix(), "phase 1 does not test Windows, and du is how this is measured")
        val big = MetainfoParser.parse(eightMegabytes.encodeToByteArray())
        FileSet.open(root, big).use { files ->
            assertEquals(8_388_608L, Files.size(files.paths[0]), "the file reports its full length")
            val allocated = allocatedKilobytes(files.paths[0])
            assertTrue(
                allocated < 512,
                "a fresh 8 MB file occupies ${allocated}K; it was preallocated rather than left sparse",
            )
        }
    }

    private fun allocatedKilobytes(path: Path): Long {
        val process = ProcessBuilder("du", "-k", path.toString()).redirectErrorStream(true).start()
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        check(process.waitFor() == 0) { "du failed: $output" }
        return output
            .substringBefore('\t')
            .trim()
            .split(Regex("\\s+"))
            .first()
            .toLong()
    }

    @Test
    fun anExistingFileIsKeptAndNotTruncated() {
        FileSet.open(root, metainfo).use { files ->
            files.channel(0).write(ByteBuffer.wrap("resume me".encodeToByteArray()), 0)
            files.flush()
        }
        FileSet.open(root, metainfo).use { files ->
            val read = ByteBuffer.allocate(9)
            files.channel(0).read(read, 0)
            assertEquals("resume me", read.array().decodeToString())
            assertEquals(1000L, Files.size(files.paths[0]))
        }
    }

    @Test
    fun channelsArePositionalAndSurviveWritesInAnyOrder() {
        FileSet.open(root, metainfo).use { files ->
            files.channel(2).write(ByteBuffer.wrap(byteArrayOf(9)), 998)
            files.channel(2).write(ByteBuffer.wrap(byteArrayOf(7)), 0)
            val read = ByteBuffer.allocate(1)
            files.channel(2).read(read, 998)
            assertEquals(9, read.array()[0])
            assertEquals(999L, Files.size(files.paths[2]))
        }
    }

    private fun isUnix(): Boolean =
        Files.getFileAttributeView(root, java.nio.file.attribute.PosixFileAttributeView::class.java) != null
}
