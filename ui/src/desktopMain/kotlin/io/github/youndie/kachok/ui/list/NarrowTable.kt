package io.github.youndie.kachok.ui.list

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.youndie.kachok.ui.main.SortColumn

/**
 * Which columns the table can afford at a given width, and the order it gives them up in.
 *
 * The design's own note asks for this and does not say which go. The order here is *derived first*:
 * ETA and RATIO are arithmetic on other columns, so a reader who loses them can still work them
 * out; PEERS · OUT and UP KIB/S are the swarm's side of a download rather than the download's; SIZE
 * goes last of the five, because it is neither derivable nor about the swarm and is the one a
 * reader would miss.
 *
 * **Name, PROGRESS, DOWN KIB/S and STATE are never dropped.** They are what the window is for —
 * which torrent, how far, how fast, and whether anything is wrong — and a table without them is a
 * list of names with a scrollbar.
 *
 * Rejected: a horizontal scrollbar under the table. It keeps every column and makes the window
 * useless at exactly the width where it appears.
 */
internal object NarrowTable {
    /** Given up in this order, first to last, until what is left fits. */
    private val droppable =
        listOf(SortColumn.Eta, SortColumn.Ratio, SortColumn.Peers, SortColumn.Up, SortColumn.Size)

    /** Never given up: the four that answer what the window is for. */
    private val kept = setOf(SortColumn.Name, SortColumn.Progress, SortColumn.Down, SortColumn.State)

    /** Below this the details panel stops being a column and becomes an overlay. */
    val panelBecomesAnOverlay: Dp = 800.dp

    /** The narrowest the name may get before a column is given up instead of squeezing it further. */
    private val minimumName: Dp = 120.dp

    fun columnsFor(available: Dp): Set<SortColumn> {
        var visible = SortColumn.entries.toSet()
        droppable.forEach { column ->
            if (fits(available, visible)) return visible
            visible = visible - column
        }
        return visible
    }

    /**
     * Whether the fixed columns plus a readable name fit in [available].
     *
     * The name is the flexible one — it takes what is left — so "fits" means what is left is still
     * wide enough to read a torrent's name in.
     */
    private fun fits(
        available: Dp,
        visible: Set<SortColumn>,
    ): Boolean = available - fixedWidth(visible) >= minimumName

    private fun fixedWidth(visible: Set<SortColumn>): Dp {
        val widths =
            visible.mapNotNull { column ->
                when (column) {
                    SortColumn.Name -> null
                    SortColumn.Size -> TorrentColumns.size
                    SortColumn.Progress -> TorrentColumns.progress
                    SortColumn.Down -> TorrentColumns.down
                    SortColumn.Up -> TorrentColumns.up
                    SortColumn.Peers -> TorrentColumns.peers
                    SortColumn.Ratio -> TorrentColumns.ratio
                    SortColumn.Eta -> TorrentColumns.eta
                    SortColumn.State -> TorrentColumns.state
                }
            }
        // Two edges, one gap between every pair of columns including the name.
        return TorrentColumns.edge * 2 + widths.fold(0.dp) { total, width -> total + width } +
            TorrentColumns.gap * visible.size
    }

    /** Asserted rather than assumed: the four that are never dropped are the four named above. */
    val alwaysDrawn: Set<SortColumn> get() = kept
}
