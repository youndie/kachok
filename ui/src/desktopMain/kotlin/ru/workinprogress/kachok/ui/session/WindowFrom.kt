package ru.workinprogress.kachok.ui.session

import ru.workinprogress.kachok.ui.add.AddTorrentState
import ru.workinprogress.kachok.ui.details.DetailsState
import ru.workinprogress.kachok.ui.list.TorrentRowModel
import ru.workinprogress.kachok.ui.list.TorrentState
import ru.workinprogress.kachok.ui.main.MainWindowState
import ru.workinprogress.kachok.ui.main.SessionStatus
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
 * The paused count is always zero today and the phrase is still there, because the design says
 * *paused* is planned and a status line that silently drops the word is a status line that stops
 * being a promise ([PAUSED_IS_PLANNED]).
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
): MainWindowState =
    MainWindowState(
        torrents = rows,
        details = details,
        adding = adding,
        settings = settings,
        status = statusOf(rows, rates, listenPort, dhtNodes, heapUsedBytes, heapMaxBytes),
        degradedSummary =
            sessionError?.let {
                if (rows.size == 1) "${rows.first().name} is degraded." else "One session is degraded."
            },
        degradedDetail = sessionError.orEmpty(),
    )
