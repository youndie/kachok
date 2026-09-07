package io.github.youndie.kachok.ui.session

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.session.SessionState
import io.github.youndie.kachok.engine.session.TrackerView
import io.github.youndie.kachok.ui.details.Complaint
import io.github.youndie.kachok.ui.details.DetailsField
import io.github.youndie.kachok.ui.details.DetailsSection
import io.github.youndie.kachok.ui.details.DetailsState
import io.github.youndie.kachok.ui.details.DetailsTab
import io.github.youndie.kachok.ui.details.FieldTone
import io.github.youndie.kachok.ui.details.FileRow
import io.github.youndie.kachok.ui.details.PeerRow
import io.github.youndie.kachok.ui.details.TrackerRow

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
    /** Every file's absolute path, in the torrent's own order, from the `FileSet` that opened it. */
    paths: List<String> = emptyList(),
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
                        DetailsField(
                            "Info hash",
                            shortHash(state),
                            copyable = true,
                            // What the button copies is the whole forty, which is the reason the
                            // button exists — the panel shows ten of them.
                            copyText = hex(state.infoHash),
                        ),
                        DetailsField("Total length", Figures.bytes(state.totalLength)),
                        DetailsField("Piece length", Figures.bytes(pieceLength)),
                        DetailsField("Save to", directory, path = true),
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
                        // No badge any more. It said the engine counts totals and nothing per
                        // second, which stopped being true when the peer list arrived: these are
                        // the peers' own five-second meters added up
                        // ([B-77](../../../../../../../../docs/backlog/B-77-the-rate-column-reads-zero.md)).
                        DetailsField(
                            "Speed down / up",
                            "${Figures.rate(rates.down)} / ${Figures.rate(rates.up)}",
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
        peers = peersOf(state),
        files = filesOf(state, paths),
        sequential = state.sequential,
        filesSummary = filesSummaryOf(state),
        trackers = trackersOf(state),
        trackersSummary = trackersSummaryOf(state),
        dht = dhtLine(state),
    )
}

/**
 * The design's tracker cards, in the torrent's own order.
 *
 * **Not-tried is a status and not an absence.** BEP 12 has a client use the first tracker that
 * answers, so a torrent with three trackers normally has one that worked and two nobody touched.
 * Hiding those two would make a three-tracker torrent look like a one-tracker torrent.
 */
private fun trackersOf(state: SessionState): List<TrackerRow> =
    state.trackers.map { tracker ->
        TrackerRow(
            url = tracker.url,
            status =
                when (tracker.status) {
                    TrackerView.Status.Working -> "working"
                    TrackerView.Status.Failed -> "failed"
                    TrackerView.Status.NotTried -> "not tried"
                },
            tone =
                when (tracker.status) {
                    TrackerView.Status.Working -> FieldTone.Good
                    TrackerView.Status.Failed -> FieldTone.Warning
                    TrackerView.Status.NotTried -> FieldTone.Plain
                },
            detail = trackerDetail(tracker),
            message = tracker.message,
        )
    }

private fun trackerDetail(tracker: TrackerView): String {
    val parts = mutableListOf<String>()
    tracker.lastAnnounceSecondsAgo?.let { parts += "${Figures.ago(it)} ago" }
    if (tracker.status == TrackerView.Status.Working) {
        parts += "${tracker.peers} ${if (tracker.peers == 1) "peer" else "peers"}"
        tracker.nextAnnounceInSeconds?.let { parts += "next in ${Figures.ago(it)}" }
    }
    // A tracker nobody has reached says nothing rather than a row of dashes: the status word beside
    // it already carries the whole of what is known.
    return parts.joinToString(" · ")
}

/**
 * `214 nodes · announced 6 m ago · next in 9 m`, or null when the DHT is off.
 *
 * The times are absent until the first pass has announced — a routing table with nodes in it has
 * not necessarily said anything yet, and a card claiming otherwise would be inventing a timestamp.
 */
private fun dhtLine(state: SessionState): String? {
    if (state.dhtNodes <= 0) return null
    val parts = mutableListOf("${state.dhtNodes} nodes")
    state.dhtAnnouncedSecondsAgo?.let { parts += "announced ${Figures.ago(it)} ago" }
    state.dhtNextInSeconds?.let { parts += "next in ${Figures.ago(it)}" }
    return parts.joinToString(" · ")
}

/** `3 trackers + DHT`, which is the design's own line. */
private fun trackersSummaryOf(state: SessionState): String {
    val count = state.trackers.size
    val trackers = "$count ${if (count == 1) "tracker" else "trackers"}"
    return if (state.dhtNodes > 0) "$trackers + DHT" else trackers
}

/**
 * The design's file rows, in the torrent's own order.
 *
 * **The percentage is of the file.** `verifiedBytes` already accounts for the piece that straddles
 * two files, so this is division and nothing more — the mistake it avoids is counting the pieces
 * that touch a file, which makes a 700-byte file complete the moment its neighbour's piece lands.
 *
 * A zero-length file is 100%: there is nothing to fetch, and `0/0` is the one division this has to
 * answer rather than compute.
 */
private fun filesOf(
    state: SessionState,
    paths: List<String>,
): List<FileRow> =
    state.files.mapIndexed { at, file ->
        FileRow(
            name = file.path,
            size = Figures.bytes(file.length),
            progress =
                when {
                    !file.wanted -> "skip"
                    file.length == 0L -> "100%"
                    else -> "${(file.verifiedBytes * PERCENT / file.length)}%"
                },
            wanted = file.wanted,
            // Bytes and not the rounded percentage: 99.6% prints as `100%`, and a file opened on
            // the strength of that number would be the truncated one this refuses to hand over.
            complete = file.wanted && file.verifiedBytes >= file.length,
            // By position, because that is the order the `FileSet` opened them in and the order
            // the metainfo lists them in. A shorter list means the metainfo has not arrived.
            path = paths.getOrNull(at),
        )
    }

/**
 * `9 files · 3.70 GiB · 8 wanted`, which is the design's own summary line.
 *
 * The size is the torrent's, not the sum of the rows. They are the same number in any real torrent
 * — the files *are* the torrent — and taking it from `totalLength` means the line agrees with the
 * header two rows above it whatever the file list happens to hold.
 */
private fun filesSummaryOf(state: SessionState): String {
    if (state.files.isEmpty()) return "No files"
    val count = state.files.size
    val wanted = state.files.count { it.wanted }
    return "$count ${if (count == 1) "file" else "files"} · ${Figures.bytes(state.totalLength)} · $wanted wanted"
}

private const val PERCENT = 100

/**
 * The design's peer rows, sorted by rate.
 *
 * **Descending, which is the design's own note**: the handful the engine unchoked sit at the top
 * and the twenty choking us sit below, so the top of the list is the part worth reading.
 *
 * **Ties keep the engine's order, which is the order the peers connected in.** `sortedByDescending`
 * is stable, and the session's own table is a `LinkedHashMap`, so twenty idle peers stay put
 * instead of reshuffling every second. Breaking ties on the address was the first version and is
 * wrong twice over: it is a text sort of IPv4, which puts `5.181.190.7` after `45.83.220.66`, and
 * it reorders the design's own reference.
 */
private fun peersOf(state: SessionState): List<PeerRow> =
    state.peers
        .sortedByDescending { it.downBytesPerSecond }
        .map { peer ->
            PeerRow(
                address = peer.address,
                // A client that said nothing usable is a dash, the same one every unknown figure in
                // this window uses — not an empty cell, which reads as a rendering fault.
                client = peer.client.takeIf { it.isNotBlank() && it != "unknown" } ?: Figures.DASH,
                unchoked = !peer.choking,
                interested = peer.interested,
                rate = Figures.rate(peer.downBytesPerSecond),
            )
        }

/**
 * `2b3a…c7f1`.
 *
 * Forty hex characters do not fit in a 340 px panel and nobody reads the middle twenty-eight; the
 * copy button beside it is what the whole hash is for.
 */
private fun shortHash(state: SessionState): String =
    hex(state.infoHash).let { "${it.take(HASH_ENDS)}…${it.takeLast(HASH_ENDS)}" }

private fun hex(infoHash: InfoHash): String =
    infoHash.bytes.joinToString("") { (it.toInt() and BYTE).toString(HEX).padStart(2, '0') }

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
