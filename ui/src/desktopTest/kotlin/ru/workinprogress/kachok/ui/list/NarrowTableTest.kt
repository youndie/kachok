package ru.workinprogress.kachok.ui.list

import androidx.compose.ui.unit.dp
import ru.workinprogress.kachok.ui.main.SortColumn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which columns go, and the order they go in.
 *
 * The design asks for "the lesser columns" and does not name them. The order here is derived first —
 * ETA and RATIO are arithmetic on other columns — then the swarm's side of the download. What is
 * never given up is what the window is for.
 */
class NarrowTableTest {
    @Test
    fun aWideTableKeepsEveryColumn() {
        assertEquals(SortColumn.entries.toSet(), NarrowTable.columnsFor(1_200.dp))
    }

    /** Narrower and narrower, and never a column back. */
    @Test
    fun columnsAreGivenUpInOneOrderAndNeverReturnWhileNarrowing() {
        val widths = listOf(1_200, 900, 800, 700, 600, 500, 400).map { it.dp }
        val sets = widths.map { NarrowTable.columnsFor(it) }
        sets.zipWithNext().forEach { (wider, narrower) ->
            assertTrue(narrower.size <= wider.size, "a column came back on a narrower table")
            assertTrue(wider.containsAll(narrower), "a different column was kept, not a subset")
        }
    }

    @Test
    fun theFirstToGoIsTheOneThatCanBeWorkedOut() {
        val wide = NarrowTable.columnsFor(1_200.dp)
        val narrower = (1_200 downTo 300).map { NarrowTable.columnsFor(it.dp) }.first { it != wide }
        assertEquals(wide - SortColumn.Eta, narrower, "ETA is arithmetic and should go first")
    }

    /**
     * Four are never given up at any width.
     *
     * Which torrent, how far, how fast, whether anything is wrong. A table without them is a list
     * of names with a scrollbar.
     */
    @Test
    fun theFourThatAnswerWhatTheWindowIsForAreNeverDropped() {
        (200..1_400 step 20).forEach { width ->
            val visible = NarrowTable.columnsFor(width.dp)
            assertTrue(
                visible.containsAll(NarrowTable.alwaysDrawn),
                "at $width dp the table lost one of ${NarrowTable.alwaysDrawn}: $visible",
            )
        }
    }

    /** And it stops giving them up once there is nothing left that may go. */
    @Test
    fun anImpossiblyNarrowTableKeepsTheFourAndNoMore() {
        assertEquals(NarrowTable.alwaysDrawn, NarrowTable.columnsFor(100.dp))
    }
}
