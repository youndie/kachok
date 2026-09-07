package io.github.youndie.kachok.ui.session

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.metainfo.MagnetLink
import io.github.youndie.kachok.engine.session.SessionState
import io.github.youndie.kachok.ui.list.TorrentRowModel
import io.github.youndie.kachok.ui.list.TorrentState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * What the UI knows about a torrent that `SessionState` does not.
 *
 * Three of the design's seven states are not in the engine at all, and the design says so about
 * two of them. This is the honest place for them: a lifecycle the surface owns, rather than three
 * fields quietly added to the engine's state for the sake of one screen.
 */
internal enum class Lifecycle {
    /** A magnet, before `MetadataFetcher` returns. There is no `SessionState` yet at all. */
    Fetching,

    Running,

    /** Between `Command.Stop` and the session's job ending: up to ten seconds of real work. */
    Stopping,
}

/** Bytes a second, each derived from the counter that only goes up. */
internal class Rates(
    val down: Long = 0,
    val up: Long = 0,
)

/**
 * What this torrent is transferring, summed from the peers doing it.
 *
 * **Off the wire and not off the piece counter.** `SessionState.downloaded` is *verified* bytes and
 * advances one whole piece at a time: at 60 KiB/s with 256 KiB pieces a piece lands every four
 * seconds, and a delta of it reads `0 0 0 250`. On a live download the row's rate column sat at
 * zero while the progress bar moved, which is one number on screen contradicting another beside
 * it — found by watching, not by a test.
 *
 * The engine already measures this correctly and for its own reasons: every peer carries a
 * `RateMeter` of five one-second buckets, which the choker uses to decide whom to unchoke. Summing
 * them is the whole of it, and there is nothing left for the surface to compute.
 *
 * The cost is that a piece which then fails its hash was counted in the rate for five seconds. That
 * is what every client does, and it is the lesser of the two lies: a rate is a rate, and how many
 * pieces failed is its own figure in the details panel.
 */
internal fun ratesOf(state: SessionState): Rates =
    Rates(
        down = state.peers.sumOf { it.downBytesPerSecond },
        up = state.peers.sumOf { it.upBytesPerSecond },
    )

/**
 * Which of the design's seven states this session is in.
 *
 * **One of them is the surface's own.** *Stopping* has no `SessionState` behind it: the engine's
 * stop is a command, and there is no state to observe it by, so the window keeps that one itself.
 * Every other state here is read out of the session.
 *
 * The order matters: a degraded session is *Error* whatever else is true of it, because that is
 * the one thing a person has to act on.
 */
internal fun stateOf(
    state: SessionState,
    lifecycle: Lifecycle,
): TorrentState =
    when {
        state.sessionError != null -> TorrentState.Error

        lifecycle == Lifecycle.Fetching -> TorrentState.Metadata

        lifecycle == Lifecycle.Stopping -> TorrentState.Stopping

        // Above *Checking* and *Seeding*: a paused torrent is paused whatever else it was doing,
        // and a complete one that is paused is still not uploading.
        state.paused -> TorrentState.Paused

        state.verifyingOf > 0 && state.verifiedPieces < state.verifyingOf -> TorrentState.Checking

        state.isComplete -> TorrentState.Seeding

        else -> TorrentState.Downloading
    }

/**
 * A magnet that has been said yes to and has no torrent yet.
 *
 * Not a `SessionState` with the fields blanked: there is no session, and inventing one to blank it
 * would be the row claiming a torrent the engine has not opened. The design's *Metadata* state is
 * exactly this — the info hash where the name will be, an indeterminate bar, and no size, ratio or
 * ETA to have an opinion about.
 */
internal fun magnetRow(
    link: MagnetLink,
    selected: Boolean = false,
): TorrentRowModel =
    TorrentRowModel(
        name = link.displayName ?: shortHash(link.infoHash),
        size = Figures.DASH,
        progress = null,
        percent = Figures.DASH,
        down = Figures.rate(0),
        up = Figures.rate(0),
        connected = 0,
        unchoked = 0,
        outstanding = 0,
        ratio = Figures.DASH,
        eta = Figures.DASH,
        state = TorrentState.Metadata,
        selected = selected,
    )

/**
 * A torrent this client remembers and cannot open, as one row.
 *
 * The alternative is to leave it out of the list, and that is the version somebody loses a month of
 * seeding to: a torrent that disappears without a word is indistinguishable from one that was never
 * there, and the person has no reason to go looking
 * ([B-81](../../../../../../../../docs/backlog/B-81-the-torrent-list-survives-a-restart.md)). The
 * name comes out of the remembered entry rather than the metainfo, which is exactly what is
 * missing.
 */
internal fun brokenRow(
    name: String,
    selected: Boolean = false,
): TorrentRowModel =
    TorrentRowModel(
        name = name,
        size = Figures.DASH,
        progress = null,
        percent = Figures.DASH,
        down = Figures.rate(0),
        up = Figures.rate(0),
        connected = 0,
        unchoked = 0,
        outstanding = 0,
        ratio = Figures.DASH,
        eta = Figures.DASH,
        state = TorrentState.Error,
        selected = selected,
    )

private fun shortHash(infoHash: InfoHash): String =
    infoHash.bytes
        .joinToString("") { (it.toInt() and BYTE).toString(HEX).padStart(2, '0') }
        .let { "${it.take(ENDS)}…${it.takeLast(ENDS)}" }

private const val BYTE = 0xFF
private const val HEX = 16
private const val ENDS = 4

/** One `SessionState` as one row of the list. */
internal fun rowOf(
    state: SessionState,
    rates: Rates,
    lifecycle: Lifecycle = Lifecycle.Running,
    selected: Boolean = false,
): TorrentRowModel {
    val torrentState = stateOf(state, lifecycle)
    val metadata = torrentState == TorrentState.Metadata
    return TorrentRowModel(
        name = state.name,
        size = if (metadata) Figures.DASH else Figures.bytes(state.totalLength),
        progress = if (metadata) null else Figures.fraction(state.downloaded, state.totalLength),
        percent =
            when {
                metadata -> {
                    Figures.DASH
                }

                // Checking shows its own progress, which is a different fraction of a different
                // whole: pieces verified, not bytes downloaded.
                torrentState == TorrentState.Checking -> {
                    Figures.percent(state.verifiedPieces.toLong(), state.verifyingOf.toLong())
                }

                else -> {
                    Figures.percent(state.downloaded, state.totalLength)
                }
            },
        down = Figures.rate(rates.down),
        up = Figures.rate(rates.up),
        connected = state.connectedPeers,
        unchoked = state.unchokedPeers,
        outstanding = state.outstandingRequests,
        ratio = if (metadata) Figures.DASH else Figures.ratio(state.uploaded, state.downloaded),
        eta = etaOf(state, rates, torrentState),
        state = torrentState,
        selected = selected,
    )
}

/**
 * `∞` when there is nothing left to want, a countdown when there is a rate to divide by, and a
 * dash when there is not.
 *
 * A dash rather than a guess: an ETA computed from a rate of zero is infinity, and drawing that
 * next to a download that is merely between blocks would be the client claiming it will never
 * finish once a second.
 */
private fun etaOf(
    state: SessionState,
    rates: Rates,
    torrentState: TorrentState,
): String =
    when {
        torrentState == TorrentState.Seeding -> Figures.FOREVER
        torrentState != TorrentState.Downloading -> Figures.DASH
        rates.down <= 0 -> Figures.DASH
        else -> Figures.eta((state.left / rates.down).seconds)
    }
