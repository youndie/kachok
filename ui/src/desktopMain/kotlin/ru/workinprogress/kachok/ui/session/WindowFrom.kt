package ru.workinprogress.kachok.ui.session

import ru.workinprogress.kachok.ui.add.AddTorrentState
import ru.workinprogress.kachok.ui.details.DetailsState
import ru.workinprogress.kachok.ui.list.TorrentRowModel
import ru.workinprogress.kachok.ui.list.TorrentState
import ru.workinprogress.kachok.ui.main.MainWindowState
import ru.workinprogress.kachok.ui.main.SessionStatus
import ru.workinprogress.kachok.ui.main.SortOrder
import ru.workinprogress.kachok.ui.main.ToolbarState
import ru.workinprogress.kachok.ui.settings.SettingsState

/** The totals the status bar carries, all six of them derived from what is on screen. */
internal fun statusOf(
    rows: List<TorrentRowModel>,
    rates: Rates,
    listenPort: Int,
    dhtNodes: Int?,
    heapUsedBytes: Long,
    heapMaxBytes: Long,
): SessionStatus =
    SessionStatus(
        down = "${Figures.rate(rates.down)} KiB/s",
        up = "${Figures.rate(rates.up)} KiB/s",
        torrents = torrentsLine(rows),
        // Null is "not asked for" and zero is "asked for and nothing answered yet"; the status bar
        // draws those differently, so they must not arrive here as the same value.
        dht = dhtNodes?.let { "DHT $it nodes" },
        port = "port $listenPort listening",
        heap = Figures.heap(heapUsedBytes, heapMaxBytes),
    )

/**
 * `16 torrents, 7 seeding, 2 paused`.
 *
 * The paused count was always zero while the engine had no paused state; the phrase was kept
 * anyway, because a status line that silently drops a word is one that stops being a promise. It
 * counts something now ([B-57](../../../../../../../../docs/backlog/B-57-a-paused-torrent.md)).
 */
private fun torrentsLine(rows: List<TorrentRowModel>): String {
    val seeding = rows.count { it.state == TorrentState.Seeding }
    val paused = rows.count { it.state == TorrentState.Paused }
    return "${rows.size} ${if (rows.size == 1) "torrent" else "torrents"}, $seeding seeding, $paused paused"
}

/**
 * The whole window, from one running torrent.
 *
 * A list of one for now: the engine is one `Session` per torrent and nothing above it holds
 * several, which is this item's first finding rather than a surprise — the list, the status bar
 * and the counts are all built for many and are given one.
 */
internal fun windowOf(
    rows: List<TorrentRowModel>,
    rates: Rates,
    listenPort: Int,
    dhtNodes: Int?,
    heapUsedBytes: Long,
    heapMaxBytes: Long,
    sessionError: String? = null,
    details: DetailsState? = null,
    adding: AddTorrentState? = null,
    settings: SettingsState? = null,
    sort: SortOrder = SortOrder(),
): MainWindowState =
    MainWindowState(
        torrents = rows,
        sort = sort,
        details = details,
        adding = adding,
        settings = settings,
        status = statusOf(rows, rates, listenPort, dhtNodes, heapUsedBytes, heapMaxBytes),
        // What Pause and Resume may do is decided by the row that is selected, so the bar is built
        // from the list rather than defaulted and left.
        toolbar = ToolbarState().forSelection(rows.firstOrNull { it.selected }?.state),
        degradedSummary =
            sessionError?.let {
                if (rows.size == 1) "${rows.first().name} is degraded." else "One session is degraded."
            },
        degradedDetail = sessionError.orEmpty(),
    )
