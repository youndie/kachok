package ru.workinprogress.kachok.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ru.workinprogress.kachok.ui.details.DetailsPanel
import ru.workinprogress.kachok.ui.details.DetailsState
import ru.workinprogress.kachok.ui.details.DetailsTab
import ru.workinprogress.kachok.ui.list.TorrentRow
import ru.workinprogress.kachok.ui.list.TorrentRowModel

/**
 * What the window is showing.
 *
 * A single object rather than a dozen parameters, because the shell's whole job is to render one
 * consistent picture of the session: a banner that contradicts the status bar is worse than either
 * alone, and two of them cannot disagree if there is one of them.
 */
internal class MainWindowState(
    val torrents: List<TorrentRowModel>,
    val status: SessionStatus,
    val sort: SortOrder = SortOrder(),
    val toolbar: ToolbarState = ToolbarState(),
    val degradedSummary: String? = null,
    val degradedDetail: String = "",
    /** Null when the panel is closed, which is also what the toolbar's toggle then says. */
    val details: DetailsState? = null,
)

/**
 * Toolbar, banner, header, list, status bar — in that order, top to bottom, with the list taking
 * whatever is left.
 *
 * The banner sits *between* the toolbar and the header rather than over the list, so it pushes the
 * table down instead of covering the first row of it. Covering a row would mean the one torrent
 * most likely to be the broken one is the one you cannot see.
 */
@Composable
internal fun MainWindow(
    state: MainWindowState,
    modifier: Modifier = Modifier,
    onSort: (SortColumn) -> Unit = {},
    onAction: (ToolbarAction) -> Unit = {},
    onTab: (DetailsTab) -> Unit = {},
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // The toggle says what the panel is doing rather than carrying its own opinion: two
        // places recording "the panel is open" is one place for it to be wrong.
        Toolbar(state.toolbar.withDetails(state.details != null), onAction = onAction)
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                // The banner is inside the list's column rather than across the window: it is one
                // session's complaint, and the panel beside it is showing a torrent that may not
                // be the one complaining.
                if (state.degradedSummary != null) {
                    DegradedBanner(state.degradedSummary, state.degradedDetail)
                }
                ColumnHeader(state.sort, onSort = onSort)
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(state.torrents) { torrent -> TorrentRow(torrent) }
                }
            }
            state.details?.let { DetailsPanel(it, onTab = onTab) }
        }
        StatusBar(state.status)
    }
}
