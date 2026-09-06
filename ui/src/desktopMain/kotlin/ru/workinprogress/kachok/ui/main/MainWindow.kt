package ru.workinprogress.kachok.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp
import ru.workinprogress.kachok.ui.add.AddTorrentDialog
import ru.workinprogress.kachok.ui.add.AddTorrentState
import ru.workinprogress.kachok.ui.add.ClipboardMagnetPrompt
import ru.workinprogress.kachok.ui.add.DropOverlay
import ru.workinprogress.kachok.ui.details.DetailsPanel
import ru.workinprogress.kachok.ui.details.DetailsState
import ru.workinprogress.kachok.ui.details.DetailsTab
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
) {
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            // The toggle says what the panel is doing rather than carrying its own opinion: two
            // places recording "the panel is open" is one place for it to be wrong.
            Toolbar(
                state.toolbar.withDetails(state.details != null, settings = state.settings != null),
                onAction = onAction,
                onFilter = onFilter,
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
                            ColumnHeader(state.sort, onSort = onSort)
                            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                                itemsIndexed(state.torrents) { at, torrent ->
                                    TorrentRow(torrent, onSelect = { onSelect(at) })
                                }
                            }
                        }
                    }
                }
                if (state.torrents.isNotEmpty() && state.settings == null) {
                    state.details?.let { DetailsPanel(it, onTab = onTab, onCopy = onCopy, onAnnounce = onAnnounce) }
                }
            }
            StatusBar(state.status)
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
