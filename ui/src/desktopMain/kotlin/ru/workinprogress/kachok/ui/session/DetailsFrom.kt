package ru.workinprogress.kachok.ui.session

import ru.workinprogress.kachok.engine.session.SessionState
import ru.workinprogress.kachok.ui.details.Complaint
import ru.workinprogress.kachok.ui.details.DetailsField
import ru.workinprogress.kachok.ui.details.DetailsSection
import ru.workinprogress.kachok.ui.details.DetailsState
import ru.workinprogress.kachok.ui.details.DetailsTab
import ru.workinprogress.kachok.ui.details.FieldTone

/**
 * What the torrent is, from what the session knows.
 *
 * Every line of *Overview* reads out of `SessionState` or out of the metainfo beside it. Two do
 * not, and both carry the design's `planned` badge rather than being quietly omitted: the two
 * speeds, which the engine has no per-second counter for, and the save directory, which is the
 * runtime's option rather than the session's state.
 *
 * The `planned` badge marks a field whose *number* has no source, not one whose shape is undecided
 * — which is why the field is still drawn.
 */
internal fun detailsOf(
    state: SessionState,
    rates: Rates,
    pieceLength: Long,
    directory: String,
    lifecycle: Lifecycle = Lifecycle.Running,
    tab: DetailsTab = DetailsTab.Overview,
): DetailsState {
    val torrentState = stateOf(state, lifecycle)
    return DetailsState(
        name = state.name,
        state = torrentState,
        stateLabel = torrentState.label,
        summary = "${Figures.bytes(state.downloaded)} of ${Figures.bytes(state.totalLength)}",
        sections =
            listOf(
                DetailsSection(
                    "IDENTITY",
                    listOf(
                        DetailsField("Info hash", shortHash(state), copyable = true),
                        DetailsField("Total length", Figures.bytes(state.totalLength)),
                        DetailsField("Piece length", Figures.bytes(pieceLength)),
                        DetailsField("Save to", directory),
                    ),
                ),
                DetailsSection(
                    "PROGRESS",
                    listOf(
                        DetailsField("Pieces", "${grouped(state.completedPieces)} / ${grouped(state.pieceCount)}"),
                        DetailsField("Downloaded", Figures.bytes(state.downloaded)),
                        DetailsField("Left", Figures.bytes(state.left)),
                        DetailsField("Uploaded", Figures.bytes(state.uploaded)),
                        DetailsField("Ratio", Figures.ratio(state.uploaded, state.downloaded)),
                        // The engine counts totals and nothing per second; these two are the
                        // surface's own arithmetic, and the badge is the design saying so.
                        DetailsField(
                            "Speed down / up",
                            "${Figures.rate(rates.down)} / ${Figures.rate(rates.up)}",
                            planned = true,
                        ),
                    ),
                ),
                DetailsSection(
                    "SWARM",
                    listOf(
                        DetailsField("Connected peers", grouped(state.connectedPeers)),
                        // The two numbers that say whether the download can actually move: a peer
                        // that has not unchoked us serves nothing, and a request not in flight
                        // fetches nothing. Green when they are non-zero rather than always.
                        DetailsField("Unchoked by them", grouped(state.unchokedPeers), tone(state.unchokedPeers)),
                        DetailsField(
                            "Requests in flight",
                            grouped(state.outstandingRequests),
                            tone(state.outstandingRequests),
                        ),
                        DetailsField("Known peers", grouped(state.knownPeers)),
                        DetailsField("Extended (BEP 10)", grouped(state.extendedPeers)),
                        DetailsField("DHT nodes", grouped(state.dhtNodes)),
                    ),
                ),
                DetailsSection(
                    "INTEGRITY",
                    listOf(
                        // A hash failure is not an error and not nothing: a peer sent rubbish and
                        // the piece was thrown away, which is the system working and worth seeing.
                        DetailsField(
                            "Hash failures",
                            grouped(state.hashFailures),
                            if (state.hashFailures > 0) FieldTone.Warning else FieldTone.Plain,
                        ),
                        DetailsField(
                            "Verified on start-up",
                            "${grouped(state.verifiedPieces)} / ${grouped(state.verifyingOf)}",
                        ),
                    ),
                ),
            ),
        complaints =
            listOfNotNull(
                state.trackerError?.let { Complaint("TRACKER", it, warning = true) },
                state.lastPeerError?.let { Complaint("LAST PEER", it, warning = false) },
            ),
        sessionError = state.sessionError,
        tab = tab,
    )
}

/**
 * `2b3a…c7f1`.
 *
 * Forty hex characters do not fit in a 340 px panel and nobody reads the middle twenty-eight; the
 * copy button beside it is what the whole hash is for.
 */
private fun shortHash(state: SessionState): String {
    val hex = state.infoHash.bytes.joinToString("") { (it.toInt() and BYTE).toString(HEX).padStart(2, '0') }
    return "${hex.take(HASH_ENDS)}…${hex.takeLast(HASH_ENDS)}"
}

/** `1 384`, the same grouping the rates use, because they sit in the same column. */
private fun grouped(value: Int): String =
    value
        .toString()
        .reversed()
        .chunked(GROUP)
        .joinToString(" ")
        .reversed()

private fun tone(value: Int): FieldTone = if (value > 0) FieldTone.Good else FieldTone.Plain

private const val BYTE = 0xFF
private const val HEX = 16
private const val HASH_ENDS = 4
private const val GROUP = 3
