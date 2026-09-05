package ru.workinprogress.kachok.engine.metainfo

import ru.workinprogress.kachok.engine.PieceIndex
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The acceptance criteria of B-04 and the metainfo scenarios of feature-metainfo.
 *
 * **The fixtures are embedded, not loaded.** A Kotlin Multiplatform test source set has no
 * resources to read, so a `.torrent` fixture is a string in the source; every byte of these is
 * printable on purpose.
 *
 * **The fixtures and the expected hashes come from an independent implementation.** They were
 * produced by a throwaway Python bencoder plus `hashlib.sha1` over the `info` bytes it emitted —
 * no Kotlin involved — so a bug shared between this parser and its own encoder cannot make the
 * test pass. Generating them rather than writing them out is not fastidiousness: the first version
 * of these fixtures had hand-written length prefixes, two of which were wrong, and the decoder
 * refused all six tests before any of them could assert anything.
 */
class MetainfoParserTest {
    /**
     * `info` is written with `name` before `length`, which is not sorted order: a canonical
     * re-encoding of this dictionary produces different bytes, and therefore a different hash.
     */
    private val singleFile =
        "d8:announce27:http://tracker.example/annc4:infod4:name10:readme.txt6:lengthi1200e12:piece " +
            "lengthi512e6:pieces60:AAAAAAAAAAAAAAAAAAAABBBBBBBBBBBBBBBBBBBBCCCCCCCCCCCCCCCCCCCCee"

    private val singleFileInfoHash = "b9f1cc77410aac4f6169c7c129113c198d440625"

    private val multiFile =
        "d8:announce27:http://tracker.example/annc13:announce-listll27:http://tracker.example/anncel2" +
            "4:udp://other.example/anncee4:infod5:filesld6:lengthi1000e4:pathl5:a.bineed6:lengthi1e4:path" +
            "l3:sub5:b.bineed6:lengthi999e4:pathl3:sub5:c.bineee4:name6:bundle12:piece lengthi512e6:piece" +
            "s80:DDDDDDDDDDDDDDDDDDDDEEEEEEEEEEEEEEEEEEEEFFFFFFFFFFFFFFFFFFFFGGGGGGGGGGGGGGGGGGGGee"

    private val multiFileInfoHash = "705bbf1978aacddf68e10760bfd9a9c08b474e14"

    @Test
    fun infoHashIsComputedOverTheOriginalBytes() {
        val metainfo = MetainfoParser.parse(singleFile.encodeToByteArray())
        assertEquals(singleFileInfoHash, metainfo.infoHash.bytes.toHex())
        assertEquals("readme.txt", metainfo.name)
        assertEquals(512, metainfo.pieceLength)
        assertEquals(1200L, metainfo.totalLength)
        assertEquals(3, metainfo.pieceCount)
        assertFalse(metainfo.isPrivate)
        assertTrue(metainfo.isSingleFile, "`length` and no `files` is the single-file case")
        assertEquals(listOf(listOf("readme.txt")), metainfo.files.map { it.path })
    }

    @Test
    fun multiFileTorrentBecomesOneListWithCumulativeOffsets() {
        val metainfo = MetainfoParser.parse(multiFile.encodeToByteArray())
        assertEquals(multiFileInfoHash, metainfo.infoHash.bytes.toHex())
        assertEquals(2000L, metainfo.totalLength)
        assertEquals(4, metainfo.pieceCount)
        assertFalse(metainfo.isSingleFile, "`files` and no `length` is the multi-file case")
        assertEquals(listOf(1000L, 1L, 999L), metainfo.files.map { it.length })
        assertEquals(listOf(0L, 1000L, 1001L), metainfo.files.map { it.offset })
        assertEquals(
            listOf(listOf("a.bin"), listOf("sub", "b.bin"), listOf("sub", "c.bin")),
            metainfo.files.map { it.path },
        )
    }

    @Test
    fun theLastPieceIsShortAndItsLengthIsComputed() {
        val metainfo = MetainfoParser.parse(multiFile.encodeToByteArray())
        assertEquals(512, metainfo.pieceLengthAt(PieceIndex(0)))
        assertEquals(512, metainfo.pieceLengthAt(PieceIndex(2)))
        assertEquals(464, metainfo.pieceLengthAt(PieceIndex(3)))
        assertEquals(
            metainfo.totalLength,
            (0 until metainfo.pieceCount).sumOf { metainfo.pieceLengthAt(PieceIndex(it)).toLong() },
        )
    }

    @Test
    fun piecesOfTheWrongLengthAreRefused() {
        val bad =
            "d4:infod6:lengthi1200e4:name1:x12:piece " +
                "lengthi512e6:pieces30:ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZee"
        val thrown = assertFailsWith<MetainfoException> { MetainfoParser.parse(bad.encodeToByteArray()) }
        assertContains(thrown.message ?: "", "pieces")
        assertContains(thrown.message ?: "", "30")
    }

    @Test
    fun aPieceCountThatContradictsTheTotalLengthIsRefused() {
        // 1200 bytes at a piece length of 512 need three hashes; this file carries two.
        val bad =
            "d4:infod6:lengthi1200e4:name1:x12:piece " +
                "lengthi512e6:pieces40:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAee"
        val thrown = assertFailsWith<MetainfoException> { MetainfoParser.parse(bad.encodeToByteArray()) }
        assertContains(thrown.message ?: "", "need 3")
    }

    @Test
    fun pieceHashesAreReadByIndexWithoutCopying() {
        val metainfo = MetainfoParser.parse(singleFile.encodeToByteArray())
        assertTrue(metainfo.pieceHash(PieceIndex(0)).contentEquals(ByteArray(20) { 'A'.code.toByte() }))
        assertTrue(metainfo.pieceHashMatches(PieceIndex(1), ByteArray(20) { 'B'.code.toByte() }))
        assertFalse(metainfo.pieceHashMatches(PieceIndex(1), ByteArray(20) { 'C'.code.toByte() }))
        assertFalse(metainfo.pieceHashMatches(PieceIndex(1), ByteArray(19) { 'B'.code.toByte() }))
    }

    @Test
    fun trackersAreAnnouncePlusTheFlattenedListInOrder() {
        val metainfo = MetainfoParser.parse(multiFile.encodeToByteArray())
        assertEquals(
            listOf("http://tracker.example/annc", "udp://other.example/annc"),
            metainfo.trackers,
        )
    }

    @Test
    fun aPathComponentThatWouldEscapeTheDownloadDirectoryIsRefused() {
        val fixtures =
            mapOf(
                ".." to "d4:infod5:filesld6:lengthi10e4:pathl2:..eee4:name1:x12:piece " +
                    "lengthi512e6:pieces20:AAAAAAAAAAAAAAAAAAAAee",
                "." to "d4:infod5:filesld6:lengthi10e4:pathl1:.eee4:name1:x12:piece " +
                    "lengthi512e6:pieces20:AAAAAAAAAAAAAAAAAAAAee",
                "empty" to "d4:infod5:filesld6:lengthi10e4:pathl0:eee4:name1:x12:piece " +
                    "lengthi512e6:pieces20:AAAAAAAAAAAAAAAAAAAAee",
                "a/b" to "d4:infod5:filesld6:lengthi10e4:pathl3:a/beee4:name1:x12:piece " +
                    "lengthi512e6:pieces20:AAAAAAAAAAAAAAAAAAAAee",
            )
        fixtures.forEach { (label, source) ->
            val thrown =
                assertFailsWith<MetainfoException>("component '$label' should be refused") {
                    MetainfoParser.parse(source.encodeToByteArray())
                }
            assertContains(thrown.message ?: "", "path component")
        }
    }

    @Test
    fun aTorrentWithBothLengthAndFilesIsRefused() {
        val bad =
            "d4:infod6:lengthi10e5:filesld6:lengthi10e4:pathl1:aeee4:name1:x12:piece " +
                "lengthi512e6:pieces20:AAAAAAAAAAAAAAAAAAAAee"
        val thrown = assertFailsWith<MetainfoException> { MetainfoParser.parse(bad.encodeToByteArray()) }
        assertContains(thrown.message ?: "", "both")
    }

    @Test
    fun aFileWithNoInfoDictionaryIsRefused() {
        val thrown =
            assertFailsWith<MetainfoException> {
                MetainfoParser.parse("d8:announce3:abce".encodeToByteArray())
            }
        assertContains(thrown.message ?: "", "info")
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
