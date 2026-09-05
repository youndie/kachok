package ru.workinprogress.kachok.ui.session

import ru.workinprogress.kachok.engine.session.SessionState
import ru.workinprogress.kachok.ui.main.SortColumn
import ru.workinprogress.kachok.ui.main.SortOrder

/** One sampled torrent: what the session says, and the rate the surface worked out. */
internal class Sample(
    val state: SessionState,
    val rates: Rates,
)

/**
 * The list in the order the column header claims it is in.
 *
 * **Sorted on the values, never on the strings the cells show.** `14.6 GiB` sorts before
 * `3.70 GiB` as text and after it as a size, and every column in this table except the name is a
 * number wearing a unit. The header was drawn sortable and sorted nothing for a release
 * ([B-56](../../../../../../../../docs/backlog/B-56-dead-toolbar-controls.md)); sorting the rows
 * would have replaced one wrong answer with another.
 *
 * The comparison is always ascending and reversed at the end rather than written twice, so
 * "descending" cannot disagree with "ascending" about ties.
 */
internal fun List<Sample>.inOrder(order: SortOrder): List<Sample> {
    val ascending =
        when (order.column) {
            // The one column that is text, and it is compared as a person reads it rather than as
            // the machine stores it: `Sintel` and `alpine` are not thirty-two apart.
            SortColumn.Name -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.state.name })

            SortColumn.Size -> sortedBy { it.state.totalLength }

            SortColumn.Progress -> sortedBy { fraction(it.state) }

            SortColumn.Down -> sortedBy { it.rates.down }

            SortColumn.Up -> sortedBy { it.rates.up }

            SortColumn.Peers -> sortedBy { it.state.connectedPeers }

            SortColumn.Ratio -> sortedBy { ratio(it.state) }

            // Infinity last, whichever way round the list is: a seeding torrent has no arrival
            // time, and putting it first would bury every download that has one.
            SortColumn.Eta -> sortedBy { secondsLeft(it) }

            // By what the state *is* rather than by its name: the seven are already in the order
            // a torrent passes through them, and alphabetical would interleave them at random.
            SortColumn.State -> sortedBy { stateOf(it.state, Lifecycle.Running).ordinal }
        }
    return if (order.ascending) ascending else ascending.reversed()
}

/** Which way a click leaves the header: a new column starts ascending, the same one turns over. */
internal fun SortOrder.clicked(column: SortColumn): SortOrder =
    if (column == this.column) SortOrder(column, !ascending) else SortOrder(column, ascending = true)

private fun fraction(state: SessionState): Double =
    if (state.totalLength <= 0) 0.0 else state.downloaded.toDouble() / state.totalLength

private fun ratio(state: SessionState): Double =
    if (state.downloaded <= 0) 0.0 else state.uploaded.toDouble() / state.downloaded

/**
 * Seconds until this finishes, or `Long.MAX_VALUE` for the ones that never will.
 *
 * A completed torrent and a stalled one both have no arrival time, and both belong at the end for
 * the same reason: the question the column answers is "what finishes next".
 */
private fun secondsLeft(sample: Sample): Long =
    if (sample.state.isComplete || sample.rates.down <= 0) {
        Long.MAX_VALUE
    } else {
        sample.state.left / sample.rates.down
    }
