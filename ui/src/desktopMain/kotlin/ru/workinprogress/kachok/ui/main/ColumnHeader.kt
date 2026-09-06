package ru.workinprogress.kachok.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ru.workinprogress.kachok.ui.icons.Glyph
import ru.workinprogress.kachok.ui.icons.Icons
import ru.workinprogress.kachok.ui.list.TorrentColumns

/**
 * The table's nine columns.
 *
 * Public because [ru.workinprogress.kachok.ui.list.TorrentRow] is: a row is told which columns to
 * draw, and the vocabulary for saying so has to be as visible as the row.
 */
public enum class SortColumn { Name, Size, Progress, Down, Up, Peers, Ratio, Eta, State }

/** Which column the list is sorted by, and which way. There is always one. */
internal class SortOrder(
    val column: SortColumn = SortColumn.Name,
    val ascending: Boolean = true,
)

/**
 * The nine column heads, over the nine columns of the row.
 *
 * The widths come from [TorrentColumns] rather than from a second list here: a header whose columns
 * are its own copy of the row's is a header that drifts by one number and puts every heading half a
 * cell from what it names.
 *
 * The sorted column is the only one in `onSurface`; the arrow beside it is `primary`. Both, rather
 * than the arrow alone — a single arrow at 13 sp is not a difference anybody finds while scanning.
 */
@Composable
internal fun ColumnHeader(
    sort: SortOrder,
    modifier: Modifier = Modifier,
    onSort: (SortColumn) -> Unit = {},
    visible: Set<SortColumn> = SortColumn.entries.toSet(),
) {
    Bar(
        height = Chrome.headerHeight,
        background = MaterialTheme.colorScheme.surfaceVariant,
        line = MaterialTheme.colorScheme.outlineVariant,
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(TorrentColumns.gap),
    ) {
        Head("NAME", SortColumn.Name, sort, null, onSort)
        if (SortColumn.Size in visible) {
            Head("SIZE", SortColumn.Size, sort, TorrentColumns.size, onSort, TextAlign.End)
        }
        Head("PROGRESS", SortColumn.Progress, sort, TorrentColumns.progress, onSort)
        Head("DOWN KIB/S", SortColumn.Down, sort, TorrentColumns.down, onSort, TextAlign.End, TIGHT)
        if (SortColumn.Up in visible) {
            Head("UP KIB/S", SortColumn.Up, sort, TorrentColumns.up, onSort, TextAlign.End, TIGHT)
        }
        if (SortColumn.Peers in visible) {
            Head("PEERS · OUT", SortColumn.Peers, sort, TorrentColumns.peers, onSort, TextAlign.End)
        }
        if (SortColumn.Ratio in visible) {
            Head("RATIO", SortColumn.Ratio, sort, TorrentColumns.ratio, onSort, TextAlign.End)
        }
        if (SortColumn.Eta in visible) {
            Head("ETA", SortColumn.Eta, sort, TorrentColumns.eta, onSort, TextAlign.End)
        }
        Head("STATE", SortColumn.State, sort, TorrentColumns.state, onSort)
    }
}

@Composable
private fun RowScope.Head(
    text: String,
    column: SortColumn,
    sort: SortOrder,
    width: Dp?,
    onSort: (SortColumn) -> Unit,
    align: TextAlign = TextAlign.Start,
    tracking: androidx.compose.ui.unit.TextUnit = WIDE,
) {
    val sorted = sort.column == column
    // The name column stretches, exactly as it does in the row; every other one is fixed.
    val cell = if (width == null) Modifier.weight(1f) else Modifier.width(width)
    Row(
        // Named, because "PROGRESS" is also a section head in the details panel and a test that
        // clicks "the one that says PROGRESS" is a test that clicks whichever came first. The
        // direction is in the name too: the arrow beside the label is the only thing that says
        // which way the list runs, and a glyph is not something a reader — or a test — can read.
        cell
            .semantics {
                contentDescription = "column $text"
                // The direction goes in the *state*, not the name: a reader — or a test — looking
                // for "column NAME" must find it whichever way the list happens to run, and the
                // arrow beside the label is the only other thing that says which way that is.
                if (sorted) stateDescription = if (sort.ascending) "ascending" else "descending"
            }.clickable { onSort(column) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (align == TextAlign.End) Arrangement.End else Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = tracking),
            color =
                if (sorted) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            maxLines = 1,
        )
        if (sorted) {
            Glyph(
                if (sort.ascending) Icons.ARROW_UPWARD else Icons.ARROW_DOWNWARD,
                size = SORT_GLYPH,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** The tracking the design gives a column head, and the tighter one the two long ones get. */
private val WIDE = 0.05.em

private val TIGHT = 0.03.em

private val SORT_GLYPH = 13.sp
