package ru.workinprogress.kachok.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.workinprogress.kachok.ui.add.AddTorrentDialog
import ru.workinprogress.kachok.ui.add.AddTorrentState
import ru.workinprogress.kachok.ui.add.ClipboardMagnetPrompt
import ru.workinprogress.kachok.ui.add.DropOverlay
import ru.workinprogress.kachok.ui.details.Details
import ru.workinprogress.kachok.ui.details.DetailsPanel
import ru.workinprogress.kachok.ui.details.DetailsState
import ru.workinprogress.kachok.ui.details.DetailsTab
import ru.workinprogress.kachok.ui.details.FileRow
import ru.workinprogress.kachok.ui.list.NarrowTable
import ru.workinprogress.kachok.ui.list.TorrentRow
import ru.workinprogress.kachok.ui.list.TorrentRowModel
import ru.workinprogress.kachok.ui.remove.RemoveState
import ru.workinprogress.kachok.ui.remove.RemoveTorrentDialog
import ru.workinprogress.kachok.ui.settings.SettingChange
import ru.workinprogress.kachok.ui.settings.SettingsScreen
import ru.workinprogress.kachok.ui.settings.SettingsState

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
    /** What was just dropped, pasted or opened, and is waiting for a decision. */
    val adding: AddTorrentState? = null,
    /** A magnet noticed on the clipboard when the window came back into focus. */
    val clipboardMagnet: String? = null,
    /** The files hovering over the window right now. Empty means nothing is being dragged. */
    val dropping: List<String> = emptyList(),
    /** Non-null while the settings screen is open, which is instead of the list rather than over it. */
    val settings: SettingsState? = null,
    /** The question behind *Remove…*, waiting for an answer. */
    val removing: RemoveState? = null,
    /**
     * How many torrents the filter is keeping out of [torrents].
     *
     * Kept as a count rather than derived, because the window does not hold the unfiltered list —
     * and an empty table with this above zero means something different from an empty table.
     */
    val hiddenByFilter: Int = 0,
    /** Where the details panel's edge has been dragged to, within the design's 280–520 dp. */
    val detailsWidth: Dp = Details.width,
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
    onSelect: (Int) -> Unit = {},
    onCancelAdd: () -> Unit = {},
    onConfirmAdd: () -> Unit = {},
    onClipboardAdd: () -> Unit = {},
    onClipboardDismiss: () -> Unit = {},
    onAddTorrent: () -> Unit = {},
    onBrowse: () -> Unit = {},
    onSetting: (SettingChange) -> Unit = {},
    onCopy: (String) -> Unit = {},
    onShowDegraded: () -> Unit = {},
    onCancelRemove: () -> Unit = {},
    onToggleRemoveData: (Boolean) -> Unit = {},
    onConfirmRemove: () -> Unit = {},
    onFilter: (String) -> Unit = {},
    onAddFile: (Int, Boolean) -> Unit = { _, _ -> },
    onAnnounce: () -> Unit = {},
    /** A file in the *Files* tab was double-clicked; returns what to say, or null when it opened. */
    onOpenFile: (FileRow) -> String? = { null },
    /** The *Files* tab's order control, which is not the add dialog's tick of the same name. */
    onSequentialOrder: (Boolean) -> Unit = {},
    onSequential: (Boolean) -> Unit = {},
    onResizeDetails: (Dp) -> Unit = {},
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Two decisions, both from the window's own width and neither from the display's: a window
        // dragged narrow on a wide screen is the case this is for.
        val narrow = maxWidth < NarrowTable.panelBecomesAnOverlay
        val panelOpen = state.details != null && state.torrents.isNotEmpty() && state.settings == null
        // What the table has left after the panel takes its share — and the whole width when the
        // panel is over it rather than beside it.
        val forTable = if (panelOpen && !narrow) maxWidth - state.detailsWidth else maxWidth
        val visible = NarrowTable.columnsFor(forTable)
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            // The toggle says what the panel is doing rather than carrying its own opinion: two
            // places recording "the panel is open" is one place for it to be wrong.
            Toolbar(
                state.toolbar.withDetails(state.details != null, settings = state.settings != null),
                onAction = onAction,
                onFilter = onFilter,
                narrow = narrow,
            )
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    // The banner is inside the list's column rather than across the window: it is one
                    // session's complaint, and the panel beside it is showing a torrent that may not
                    // be the one complaining.
                    if (state.degradedSummary != null) {
                        DegradedBanner(state.degradedSummary, state.degradedDetail, onShow = onShowDegraded)
                    }
                    when {
                        state.settings != null -> {
                            SettingsScreen(state.settings, Modifier.weight(1f), onSetting)
                        }

                        // An empty list with a filter on it is not an empty client; saying
                        // "nothing downloading" here would contradict the status bar below.
                        state.torrents.isEmpty() && state.hiddenByFilter > 0 -> {
                            NoMatches(
                                state.toolbar.filter,
                                state.hiddenByFilter,
                                Modifier.weight(1f),
                                onClear = { onFilter("") },
                            )
                        }

                        // Nine column heads over nothing is a table that looks broken; this looks
                        // like a place to start.
                        state.torrents.isEmpty() -> {
                            EmptyState(Modifier.weight(1f), onAdd = onAddTorrent)
                        }

                        else -> {
                            ColumnHeader(state.sort, onSort = onSort, visible = visible)
                            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                                // **Keyed by name, not by position.** Without a key a lazy list
                                // identifies a row by where it is, so sorting by a column hands
                                // every row's state to a different torrent — and at 300 ms a sample
                                // that reorders is three times as many chances to do it.
                                itemsIndexed(state.torrents, key = { _, torrent -> torrent.name }) { at, torrent ->
                                    TorrentRow(torrent, onSelect = { onSelect(at) }, visible = visible)
                                }
                            }
                        }
                    }
                }
                // Beside the list only while there is room for both. Below 800 dp it goes over the
                // list instead — see the overlay after this Row.
                if (panelOpen && !narrow) {
                    state.details.let {
                        DetailsPanel(
                            it,
                            onTab = onTab,
                            onCopy = onCopy,
                            onAnnounce = onAnnounce,
                            onOpenFile = onOpenFile,
                            onSequential = onSequentialOrder,
                            width = state.detailsWidth,
                            onResize = onResizeDetails,
                        )
                    }
                }
            }
            StatusBar(state.status)
        }
        // The panel, over the list rather than beside it. Right-aligned and full height, so the
        // gesture that opens and closes it is the same toolbar toggle at every width.
        if (panelOpen && narrow) {
            state.details.let {
                DetailsPanel(
                    it,
                    Modifier
                        .align(
                            Alignment.CenterEnd,
                        ).padding(top = Chrome.toolbarHeight, bottom = Chrome.statusHeight),
                    onTab = onTab,
                    onCopy = onCopy,
                    onAnnounce = onAnnounce,
                    onOpenFile = onOpenFile,
                    onSequential = onSequentialOrder,
                    width = state.detailsWidth,
                    onResize = onResizeDetails,
                )
            }
        }
        // Over everything, in the order a person meets them: a drag is happening now, a dialog is
        // waiting for an answer, a clipboard offer is neither and sits at the bottom.
        if (state.dropping.isNotEmpty()) DropOverlay(state.dropping)
        state.clipboardMagnet?.let { link ->
            // Above the status bar, not over it. The prompt is anchored to the bottom of the window
            // and the status bar is the bottom of the window; without the bar's own height in the
            // padding it covered the rates and the port.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = Chrome.statusHeight + 10.dp),
            ) {
                ClipboardMagnetPrompt(link, onAdd = onClipboardAdd, onDismiss = onClipboardDismiss)
            }
        }
        state.adding?.let { adding ->
            Box(
                Modifier.fillMaxSize().background(SCRIM),
                contentAlignment = Alignment.Center,
            ) {
                AddTorrentDialog(
                    adding,
                    onCancel = onCancelAdd,
                    onAdd = onConfirmAdd,
                    onBrowse = onBrowse,
                    onFile = onAddFile,
                    onSequential = onSequential,
                )
            }
        }
        state.removing?.let { removing ->
            Box(
                Modifier.fillMaxSize().background(SCRIM),
                contentAlignment = Alignment.Center,
            ) {
                RemoveTorrentDialog(
                    removing,
                    onCancel = onCancelRemove,
                    onToggleData = onToggleRemoveData,
                    onRemove = onConfirmRemove,
                )
            }
        }
    }
}

/** Dark enough that the window behind the dialog is context rather than competition. */
private val SCRIM = Color.Black.copy(alpha = 0.45f)
