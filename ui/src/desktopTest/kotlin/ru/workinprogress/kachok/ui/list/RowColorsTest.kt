package ru.workinprogress.kachok.ui.list

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import ru.workinprogress.kachok.ui.theme.KachokDarkColors
import ru.workinprogress.kachok.ui.theme.KachokWarning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The acceptance criteria of [B-47](../../../../../../../../docs/backlog/B-47-torrent-row-and-states.md):
 * every cell of every state, at the colour the design draws it.
 *
 * The hexes are transcribed from the row markup of `docs/design/kachok Phase 2 Desktop.dc.html` —
 * the seven states of the main window, plus the unselected *downloading* row further down the same
 * list, because the design draws the first one selected and a selected row is a different palette
 * (and [B-48](../../../../../../../../docs/backlog/B-48-main-window-shell.md)'s problem). Nothing
 * here is derived; every value was read out of a `color:` or `background:` in that file.
 *
 * The golden proves the row calls this; this proves what it says. A screenshot alone cannot: a
 * figure at 12 px never reaches its full colour anywhere in its antialiased glyph, so "is this cell
 * `#BEC9C6` or `#DDE4E1`" is not a question pixels answer reliably.
 */
class RowColorsTest {
    private fun expected(state: TorrentState): Map<RowCell, Long> =
        when (state) {
            TorrentState.Metadata -> {
                mapOf(
                    RowCell.Glyph to 0xFF889390,
                    RowCell.Name to 0xFFBEC9C6,
                    RowCell.Size to 0xFF889390,
                    RowCell.Track to 0xFF2A3331,
                    RowCell.Percent to 0xFF889390,
                    RowCell.Down to 0xFF889390,
                    RowCell.Up to 0xFF889390,
                    RowCell.Peers to 0xFFDDE4E1,
                    RowCell.Ratio to 0xFF889390,
                    RowCell.Eta to 0xFF889390,
                    RowCell.Label to 0xFFBEC9C6,
                )
            }

            TorrentState.Checking -> {
                mapOf(
                    RowCell.Glyph to 0xFFF3C46B,
                    RowCell.Name to 0xFFDDE4E1,
                    RowCell.Size to 0xFFBEC9C6,
                    RowCell.Bar to 0xFFF3C46B,
                    RowCell.Track to 0xFF2A3331,
                    RowCell.Percent to 0xFFF3C46B,
                    RowCell.Down to 0xFF889390,
                    RowCell.Up to 0xFF889390,
                    RowCell.Peers to 0xFF889390,
                    RowCell.Ratio to 0xFFBEC9C6,
                    RowCell.Eta to 0xFF889390,
                    RowCell.Label to 0xFFF3C46B,
                )
            }

            TorrentState.Downloading -> {
                mapOf(
                    RowCell.Glyph to 0xFF4FD9C2,
                    RowCell.Name to 0xFFDDE4E1,
                    RowCell.Size to 0xFFBEC9C6,
                    RowCell.Bar to 0xFF4FD9C2,
                    RowCell.Track to 0xFF2A3331,
                    RowCell.Percent to 0xFFDDE4E1,
                    RowCell.Down to 0xFFDDE4E1,
                    RowCell.Up to 0xFFBEC9C6,
                    RowCell.Peers to 0xFFDDE4E1,
                    RowCell.Ratio to 0xFFBEC9C6,
                    RowCell.Eta to 0xFFBEC9C6,
                    RowCell.Label to 0xFFDDE4E1,
                )
            }

            TorrentState.Seeding -> {
                mapOf(
                    RowCell.Glyph to 0xFF4FD9C2,
                    RowCell.Name to 0xFFDDE4E1,
                    RowCell.Size to 0xFFBEC9C6,
                    RowCell.Bar to 0xFF4FD9C2,
                    RowCell.Track to 0xFF2A3331,
                    RowCell.Percent to 0xFFBEC9C6,
                    RowCell.Down to 0xFF889390,
                    RowCell.Up to 0xFFDDE4E1,
                    RowCell.Peers to 0xFFDDE4E1,
                    RowCell.Ratio to 0xFFBEC9C6,
                    RowCell.Eta to 0xFF889390,
                    RowCell.Label to 0xFF4FD9C2,
                )
            }

            TorrentState.Paused -> {
                mapOf(
                    RowCell.Glyph to 0xFF889390,
                    RowCell.Name to 0xFF889390,
                    RowCell.Size to 0xFF889390,
                    RowCell.Bar to 0xFF889390,
                    RowCell.Track to 0xFF2A3331,
                    RowCell.Percent to 0xFF889390,
                    RowCell.Down to 0xFF889390,
                    RowCell.Up to 0xFF889390,
                    RowCell.Peers to 0xFF889390,
                    RowCell.Ratio to 0xFF889390,
                    RowCell.Eta to 0xFF889390,
                    RowCell.Label to 0xFF889390,
                )
            }

            TorrentState.Stopping -> {
                mapOf(
                    RowCell.Glyph to 0xFFF3C46B,
                    RowCell.Name to 0xFFDDE4E1,
                    RowCell.Size to 0xFFBEC9C6,
                    RowCell.Bar to 0xFF4A5654,
                    RowCell.Track to 0xFF2A3331,
                    RowCell.Percent to 0xFFBEC9C6,
                    RowCell.Down to 0xFFBEC9C6,
                    RowCell.Up to 0xFF889390,
                    RowCell.Peers to 0xFFBEC9C6,
                    RowCell.Ratio to 0xFFBEC9C6,
                    RowCell.Eta to 0xFF889390,
                    RowCell.Label to 0xFFF3C46B,
                )
            }

            TorrentState.Error -> {
                mapOf(
                    RowCell.Glyph to 0xFFFFB4AB,
                    RowCell.Name to 0xFFFFB4AB,
                    RowCell.Size to 0xFFE5A9A1,
                    RowCell.Bar to 0xFFFFB4AB,
                    RowCell.Track to 0xFF3A2320,
                    RowCell.Percent to 0xFFFFB4AB,
                    RowCell.Down to 0xFFE5A9A1,
                    RowCell.Up to 0xFFE5A9A1,
                    RowCell.Peers to 0xFFFFB4AB,
                    RowCell.Ratio to 0xFFE5A9A1,
                    RowCell.Eta to 0xFFE5A9A1,
                    RowCell.Label to 0xFFFFB4AB,
                )
            }
        }

    private fun color(
        model: TorrentRowModel,
        cell: RowCell,
    ) = rowColor(model, cell, KachokDarkColors, KachokWarning)

    private fun Color.hex(): String = "%08X".format(toArgb())

    @Test
    fun everyCellOfEveryStateIsTheColourTheDesignDrawsIt() {
        designRows.forEach { model ->
            expected(model.state).forEach { (cell, argb) ->
                assertEquals(
                    Color(argb).hex(),
                    color(model, cell).hex(),
                    "${model.state} $cell",
                )
            }
        }
        assertEquals(7, designRows.size, "one row per state, or the table above is not a table")
    }

    /**
     * The metadata bar is the one fill the design does not give a hex: it is the primary role held
     * back, because the row has no percentage behind it and a full-strength fill would claim one.
     */
    @Test
    fun aMetadataRowsBarIsTheHeldBackPrimary() {
        val metadata = designRows.single { it.state == TorrentState.Metadata }
        assertEquals(
            Color(0xFF4FD9C2).copy(alpha = 0.55f),
            color(metadata, RowCell.Bar),
        )
        assertEquals(null, metadata.progress, "a metadata row has no percentage to draw")
        assertTrue(METADATA_BAR_FRACTION > 0f && METADATA_BAR_FRACTION < 1f, "and shows a position instead")
    }

    /**
     * The two claims the acceptance criteria make in words: a magnet says less than a download,
     * and a completed torrent's zero outstanding requests is not a stall.
     */
    @Test
    fun aMetadataRowShowsNoSizeNoRatioAndNoEta() {
        val metadata = designRows.single { it.state == TorrentState.Metadata }
        listOf(metadata.size, metadata.ratio, metadata.eta).forEach { assertEquals(DASH, it) }
        listOf(RowCell.Size, RowCell.Ratio, RowCell.Eta).forEach { cell ->
            assertEquals(Emphasis.Muted, metadata.emphasisOf(cell), "$cell")
        }
    }

    @Test
    fun aSeedingRowsZeroOutstandingIsNotDrawnAsAStall() {
        val seeding = designRows.single { it.state == TorrentState.Seeding }
        assertEquals(0, seeding.outstanding)
        assertEquals(
            Emphasis.Live,
            seeding.emphasisOf(RowCell.Peers),
            "31 peers and nothing left to ask for is calm, not stalled",
        )
        val checking = designRows.single { it.state == TorrentState.Checking }
        assertEquals(
            Emphasis.Muted,
            checking.emphasisOf(RowCell.Peers),
            "no peers at all is the dimmed case, and it is a different one",
        )
    }

    /** *Stopping* freezes its numbers, so none of them may still be drawn as live. */
    @Test
    fun aStoppingRowHasNoLiveFigureLeft() {
        val stopping = designRows.single { it.state == TorrentState.Stopping }
        RowCell.entries.forEach { cell ->
            assertTrue(
                stopping.emphasisOf(cell) != Emphasis.Live,
                "$cell is still live on a row whose numbers stopped moving",
            )
        }
    }

    /**
     * The one selected row the design draws, cell by cell.
     *
     * Selection is a change of ground rather than a wash over the row, and this is the evidence:
     * all twelve of these come out of mapping each neutral role to its counterpart on
     * `primaryContainer`, with nothing listed as a special case
     * ([B-48](../../../../../../../../docs/backlog/B-48-main-window-shell.md)).
     */
    @Test
    fun aSelectedRowIsTheSameRowDrawnOnTheContainer() {
        val downloading = designRows.single { it.state == TorrentState.Downloading }
        val selected =
            TorrentRowModel(
                name = downloading.name,
                size = downloading.size,
                progress = downloading.progress,
                percent = downloading.percent,
                down = downloading.down,
                up = downloading.up,
                connected = downloading.connected,
                unchoked = downloading.unchoked,
                outstanding = downloading.outstanding,
                ratio = downloading.ratio,
                eta = downloading.eta,
                state = downloading.state,
                selected = true,
            )
        mapOf(
            RowCell.Glyph to 0xFF71F6DE,
            RowCell.Name to 0xFFDDF3EF,
            RowCell.Size to 0xFFB7E8E0,
            RowCell.Bar to 0xFF71F6DE,
            RowCell.Track to 0xFF00302A,
            RowCell.Percent to 0xFFDDF3EF,
            RowCell.Down to 0xFFDDF3EF,
            RowCell.Up to 0xFFB7E8E0,
            RowCell.Peers to 0xFFDDF3EF,
            RowCell.Ratio to 0xFFB7E8E0,
            RowCell.Eta to 0xFFB7E8E0,
            RowCell.Label to 0xFFDDF3EF,
        ).forEach { (cell, argb) ->
            assertEquals(Color(argb).hex(), color(selected, cell).hex(), "selected $cell")
        }
    }

    /** The peers cell is one string in one colour, which is what all 28 of the design's are. */
    @Test
    fun thePeersCellReadsAsTheDesignWritesIt() {
        assertEquals("24/4 · 61", peersText(designRows.single { it.state == TorrentState.Downloading }))
        assertEquals("0/0 · 0", peersText(designRows.single { it.state == TorrentState.Paused }))
    }
}
