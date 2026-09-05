package ru.workinprogress.kachok.ui.session

import ru.workinprogress.kachok.ui.list.TorrentRowModel

/**
 * What the toolbar's filter field selects.
 *
 * **The name or the state, one rule.** A person with sixteen torrents asks two questions of a
 * list — *where is the Debian one* and *what is stalled* — and the second is not a substring of
 * any name. Matching the state's own label answers it without a second control and without a
 * syntax: `paus` finds the paused ones because that is what their rows say.
 *
 * Rejected: filtering the `TorrentSet`. The list is a view; a filter that removed a torrent from
 * the set would stop downloading it.
 *
 * Rejected: matching the size, rate or ratio cells. They are numbers wearing units, and `1` would
 * match `1.20 KiB`, `24 988` and `0.14` at once — a filter that matches everything is a filter
 * nobody trusts.
 */
internal fun TorrentRowModel.matches(filter: String): Boolean {
    val wanted = filter.trim()
    if (wanted.isEmpty()) return true
    return name.contains(wanted, ignoreCase = true) || state.label.contains(wanted, ignoreCase = true)
}
