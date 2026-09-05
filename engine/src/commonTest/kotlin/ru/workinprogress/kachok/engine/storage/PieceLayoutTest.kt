package ru.workinprogress.kachok.engine.storage

import ru.workinprogress.kachok.engine.PieceIndex
import ru.workinprogress.kachok.engine.metainfo.MetainfoParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The acceptance criteria of B-12's mapping half.
 *
 * The torrent is three files of deliberately awkward lengths — 1000, 1 and 999 bytes at a piece
 * length of 512 — so that a piece starts mid-file, a whole file falls inside one piece, and the
 * last piece is short. Every fixture was emitted by an independent bencoder.
 */
class PieceLayoutTest {
    private val threeFiles =
        "d4:infod5:filesld6:lengthi1000e4:pathl5:a.bineed6:lengthi1e4:pathl3:sub5:b.bineed6:lengthi99" +
            "9e4:pathl3:sub5:c.bineee4:name6:bundle12:piece lengthi512e6:pieces80:AAAAAAAAAAAAAAAAAAAABBB" +
            "BBBBBBBBBBBBBBBBBCCCCCCCCCCCCCCCCCCCCDDDDDDDDDDDDDDDDDDDDee"

    private val withEmptyFile =
        "d4:infod5:filesld6:lengthi600e4:pathl5:a.bineed6:lengthi0e4:pathl9:empty.bineed6:lengthi400e" +
            "4:pathl5:b.bineee4:name9:withempty12:piece " +
            "lengthi512e6:pieces40:DDDDDDDDDDDDDDDDDDDDEEEEEEEEEEEEEEEEEEEEee"

    private val layout = PieceLayout(MetainfoParser.parse(threeFiles.encodeToByteArray()))

    @Test
    fun aPieceInsideOneFileIsOneSpan() {
        val spans = layout.spansOfPiece(PieceIndex(0))
        assertEquals(1, spans.size)
        assertEquals(0, spans[0].file)
        assertEquals(0L, spans[0].position)
        assertEquals(512, spans[0].length)
    }

    @Test
    fun aPieceCrossingAFileBoundaryIsSeveralSpans() {
        // Piece 1 is bytes 512..1023: 488 bytes of a.bin, then the whole 1-byte b.bin, then 23 of
        // c.bin. Three files in one piece is the case that makes the gathering write worth having.
        val spans = layout.spansOfPiece(PieceIndex(1))
        assertEquals(listOf(0, 1, 2), spans.map { it.file })
        assertEquals(listOf(512L, 0L, 0L), spans.map { it.position })
        assertEquals(listOf(488, 1, 23), spans.map { it.length })
        assertEquals(512, spans.sumOf { it.length })
    }

    @Test
    fun everyPieceMapsToSpansSummingToItsLength() {
        val metainfo = layout.metainfo
        (0 until metainfo.pieceCount).forEach { index ->
            val piece = PieceIndex(index)
            val spans = layout.spansOfPiece(piece)
            assertEquals(
                metainfo.pieceLengthAt(piece),
                spans.sumOf { it.length },
                "piece $index",
            )
            assertTrue(spans.isNotEmpty())
        }
    }

    @Test
    fun theSpansOfATorrentCoverEveryByteOfEveryFileExactlyOnce() {
        val metainfo = layout.metainfo
        val written = LongArray(metainfo.files.size)
        (0 until metainfo.pieceCount).forEach { index ->
            layout.spansOfPiece(PieceIndex(index)).forEach { span ->
                assertEquals(
                    written[span.file],
                    span.position,
                    "spans of file ${span.file} must be contiguous and in order",
                )
                written[span.file] += span.length
            }
        }
        assertEquals(metainfo.files.map { it.length }, written.toList())
    }

    @Test
    fun theLastPieceIsShortAndStillMapped() {
        val last = PieceIndex(layout.metainfo.pieceCount - 1)
        assertEquals(464, layout.metainfo.pieceLengthAt(last))
        assertEquals(464, layout.spansOfPiece(last).sumOf { it.length })
    }

    @Test
    fun aBlockInsideAPieceIsMappedOnItsOwn() {
        // The writer maps blocks, not only whole pieces. Piece 1 starts at 512, so 62 bytes from
        // its 450th cross all three files: 38 left in a.bin, the single byte of b.bin, 23 of c.bin.
        val spans = layout.spans(PieceIndex(1), begin = 450, length = 62)
        assertEquals(listOf(0, 1, 2), spans.map { it.file })
        assertEquals(962L, spans[0].position)
        assertEquals(listOf(38, 1, 23), spans.map { it.length })
        assertEquals(62, spans.sumOf { it.length })
    }

    @Test
    fun aBlockThatDoesNotFitItsPieceIsRefused() {
        assertFailsWith<IllegalArgumentException> { layout.spans(PieceIndex(0), 0, 513) }
        assertFailsWith<IllegalArgumentException> { layout.spans(PieceIndex(0), -1, 10) }
        assertFailsWith<IllegalArgumentException> { layout.spans(PieceIndex(0), 0, 0) }
    }

    @Test
    fun aZeroLengthFileCoversNothingAndIsSteppedOver() {
        val withEmpty = PieceLayout(MetainfoParser.parse(withEmptyFile.encodeToByteArray()))
        val spans = withEmpty.spansOfPiece(PieceIndex(0))
        // 512 bytes all inside a.bin; the empty file starts at 600 and is not reached.
        assertEquals(listOf(0), spans.map { it.file })
        val second = withEmpty.spansOfPiece(PieceIndex(1))
        // Bytes 512..999: the last 88 bytes of a.bin, then all 400 of b.bin. The empty file sits
        // between them at offset 600 and contributes no span at all.
        assertEquals(listOf(0, 2), second.map { it.file })
        assertEquals(88, second[0].length)
        assertEquals(400, second[1].length)
        assertEquals(488, second.sumOf { it.length })
    }
}
