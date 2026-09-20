package io.github.youndie.kachok.engine.storage

import io.github.youndie.kachok.engine.bencode.BDictionary
import io.github.youndie.kachok.engine.bencode.BInteger
import io.github.youndie.kachok.engine.bencode.BList
import io.github.youndie.kachok.engine.bencode.BString
import io.github.youndie.kachok.engine.bencode.Bencode
import io.github.youndie.kachok.engine.metainfo.MetainfoParser
import io.github.youndie.kachok.engine.wire.PeerWire
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A file this client writes says where it came from.
 *
 * Every browser puts a `Zone.Identifier` stream on a download, and that is what makes Windows ask
 * its "unknown publisher" question before running a binary. A torrent client that writes an
 * executable without it and then opens it on a double-click has taken away a warning the operating
 * system would otherwise have given, about a file that came from strangers
 * ([B-93](../../../../../../../../docs/backlog/B-93-opening-a-downloaded-executable.md)).
 *
 * **What is asserted here is the decision, not the system call.** The stream is an NTFS alternate
 * data stream and this suite runs on Linux, so the mark itself is proved on Windows by hand and
 * recorded in the item; what a test can hold wherever it runs is *which* files are marked, with
 * what, and that nothing is marked where the mark would mean nothing.
 */
class MarkOfTheWebTest {
    private val root: Path = Files.createTempDirectory("kachok-motw")

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() = root.deleteRecursively()

    @Test
    fun theMarkIsTheOneABrowserWrites() {
        val written = mutableListOf<Pair<Path, String>>()
        val file = root.resolve("payload.bin")

        FileSet.markDownloaded(file, onWindows = true) { at, mark -> written.add(at to mark) }

        assertEquals(listOf(file to "[ZoneTransfer]\r\nZoneId=3\r\n"), written, "the mark is not the internet zone")
    }

    /** And nowhere else: on Linux and macOS the same view is an extended attribute nothing reads. */
    @Test
    fun nothingIsMarkedWhereTheMarkMeansNothing() {
        val written = mutableListOf<Path>()

        FileSet.markDownloaded(root.resolve("payload.bin"), onWindows = false) { at, _ -> written.add(at) }

        assertTrue(written.isEmpty(), "a Zone.Identifier was written where nothing would ever read it")
    }

    /**
     * Every file of a torrent is marked when it is created — and only then.
     *
     * The second half is the one worth a test: a resumed download opens files that already exist
     * and already carry the mark, and re-writing it on every start would be a write per file per
     * run for nothing.
     */
    @Test
    fun everyFileIsMarkedWhenItIsCreatedAndNotWhenItIsResumed() {
        val metainfo = MetainfoParser.parse(torrent())
        val marked = mutableListOf<String>()

        FileSet.open(root, metainfo) { marked.add(it.fileName.toString()) }.close()
        assertEquals(listOf("one.bin", "two.bin"), marked.sorted(), "not every file this client made was marked")

        marked.clear()
        FileSet.open(root, metainfo) { marked.add(it.fileName.toString()) }.close()
        assertTrue(marked.isEmpty(), "a resumed download marked files it did not create")
    }

    /**
     * Two files whose lengths add up to four blocks, so the shape is a real multi-file torrent.
     *
     * Built with `Bencode.encode` and not by hand, which this repository has written down as a trap
     * it has fallen into three times — and which this test fell into a fourth time before the
     * encoder went in.
     */
    private fun torrent(): ByteArray {
        val piece = PeerWire.BLOCK_SIZE

        fun file(
            name: String,
            length: Int,
        ) = BDictionary(
            mapOf(
                BString("length") to BInteger(length.toLong()),
                BString("path") to BList(listOf(BString(name))),
            ),
        )
        val info =
            BDictionary(
                mapOf(
                    BString("files") to BList(listOf(file("one.bin", piece * 3), file("two.bin", piece))),
                    BString("name") to BString("bundle"),
                    BString("piece length") to BInteger(piece.toLong()),
                    BString("pieces") to BString(ByteArray(4 * 20) { 'A'.code.toByte() }),
                ),
            )
        return Bencode.encode(BDictionary(mapOf(BString("info") to info)))
    }
}
