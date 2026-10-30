package io.github.youndie.kachok.ui.list

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.youndie.kachok.ui.theme.KachokColors
import io.github.youndie.kachok.ui.theme.KachokTheme

/**
 * Every colour in a row, as a function rather than as a composable.
 *
 * The design's row is not "a table in `onSurface` with a coloured state column": each of the
 * twelve things a row draws takes its colour from the state, and the same cell is a different
 * colour in two different states. That is a rule, and a rule that only exists inside `@Composable`
 * code can only be checked by taking a screenshot of it — which is how a per-state colour ends up
 * being tested by eye and stays wrong for a release.
 *
 * So the rule lives here, takes the scheme as an argument, and
 * `RowColorsTest` checks all twelve cells of all seven states against the hexes read out of
 * `docs/design/kachok Phase 2 Desktop.dc.html`. The golden then proves the row actually calls it.
 */
internal enum class RowCell {
    Glyph,
    Name,
    Size,
    Bar,
    Track,
    Percent,
    Down,
    Up,
    Peers,
    Ratio,
    Eta,
    Label,
}

/**
 * How loudly a figure is allowed to speak.
 *
 * The design has three levels for numbers where Material 3 has two roles, and which level a cell
 * gets depends on the state rather than on the cell: 4 312 KiB/s down is the headline of a
 * downloading row and a frozen leftover on a stopping one, and it is drawn differently in each.
 */
internal enum class Emphasis {
    /** The number this state is about. */
    Live,

    /** True, but not what the row is saying. */
    Figure,

    /** Zero, absent, or paused. */
    Muted,
}

/** The em dash the design writes where a value does not exist yet. */
internal const val DASH: String = "—"

private val ZERO = io.github.youndie.kachok.ui.session.Figures.NO_SPEED

/**
 * Two states rewrite every emphasis in their row.
 *
 * *Paused* drops the whole row — the design says so in as many words — and *Queued* with it: a
 * torrent waiting for a turn is not transferring either, and differs from a paused one only in who
 * decided (B-151). *Stopping* demotes the
 * headline but keeps the rest: its numbers are the last ones the session saw, and a frozen figure
 * that still looks live is the row telling a lie for up to ten seconds.
 */
private fun TorrentState.settle(emphasis: Emphasis): Emphasis =
    when (this) {
        TorrentState.Paused, TorrentState.Queued -> Emphasis.Muted
        TorrentState.Stopping -> if (emphasis == Emphasis.Live) Emphasis.Figure else emphasis
        else -> emphasis
    }

/**
 * Which of the three levels a figure cell speaks at.
 *
 * `Live` is never asked of a cell whose value is zero or absent: the design dims those in every
 * state, including the ones where the number would otherwise be the headline.
 */
internal fun TorrentRowModel.emphasisOf(cell: RowCell): Emphasis =
    state.settle(
        when (cell) {
            RowCell.Size -> {
                if (size == DASH) Emphasis.Muted else Emphasis.Figure
            }

            RowCell.Down -> {
                when {
                    down == ZERO -> Emphasis.Muted
                    state == TorrentState.Downloading -> Emphasis.Live
                    else -> Emphasis.Figure
                }
            }

            RowCell.Up -> {
                when {
                    up == ZERO -> Emphasis.Muted
                    state == TorrentState.Seeding -> Emphasis.Live
                    else -> Emphasis.Figure
                }
            }

            // The diagnosis cell: bright when there are peers, and bright on an error row even at
            // zero, because there a zero is not an absence, it is the symptom.
            RowCell.Peers -> {
                if (connected > 0 || state == TorrentState.Error) Emphasis.Live else Emphasis.Muted
            }

            RowCell.Ratio -> {
                if (ratio == DASH) Emphasis.Muted else Emphasis.Figure
            }

            // An ETA is a figure only while it is a countdown. A seeding row's ∞ is correct rather
            // than informative, and the design dims it with the dashes.
            RowCell.Eta -> {
                if (state == TorrentState.Downloading && eta != DASH) Emphasis.Figure else Emphasis.Muted
            }

            RowCell.Percent -> {
                when (state) {
                    TorrentState.Metadata -> Emphasis.Muted
                    TorrentState.Seeding -> Emphasis.Figure
                    else -> Emphasis.Live
                }
            }

            else -> {
                Emphasis.Live
            }
        },
    )

private fun Emphasis.color(
    state: TorrentState,
    scheme: KachokColors,
): Color =
    if (state == TorrentState.Error) {
        // An error row has its own two tones: what says what happened, and everything else.
        if (this == Emphasis.Live) scheme.error else scheme.errorFigure
    } else {
        when (this) {
            Emphasis.Live -> scheme.text
            Emphasis.Figure -> scheme.textMuted
            Emphasis.Muted -> scheme.textFaint
        }
    }

/**
 * The dimmed level of a selected row, which the design never draws.
 *
 * Its one selected row is a downloading torrent with a figure in every cell, so there is no
 * `#889390` to copy. Derived rather than invented: on the ordinary surface the three levels are
 * `onSurface` at 1.0, 0.85 and 0.6 — measured, within four counts of 255 on every channel — so the
 * dimmed one here is `onPrimaryContainer` at the same 0.6.
 */
private const val SELECTED_MUTED_ALPHA = 0.6f

/**
 * The same colour, drawn on `primaryContainer` instead of on the surface.
 *
 * Selection is a change of ground, not a wash over the row: every distinction the row had survives
 * it, because each neutral role has a counterpart that reads on the tint. `warning` and `error`
 * fall through unchanged — they are what the state *means*, they are legible on the container, and
 * a selected row that stopped saying "this one is broken" would be selection deleting information.
 *
 * All twelve cells of the design's own selected row come out of this mapping; none of them is
 * listed anywhere as a special case. The accent as a fill and the accent as ink both brighten to
 * the same [KachokColors.accentBright]: the ink that reads on the surface is not the one that reads
 * on the container (B-164).
 */
private fun Color.onContainer(scheme: KachokColors): Color =
    when (this) {
        scheme.accent, scheme.accentText -> scheme.accentBright
        scheme.text -> scheme.onAccentContainer
        scheme.textMuted -> scheme.selectedFigure
        scheme.textFaint -> scheme.onAccentContainer.copy(alpha = SELECTED_MUTED_ALPHA)
        scheme.divider -> scheme.selectedTrack
        else -> this
    }

/**
 * The colour of a state's name, wherever it is written.
 *
 * The details panel says the same word the row's last column does, so it says it in the same
 * colour — a header that called a stopping torrent something other than what the list calls it
 * would be two answers to one question.
 */
@Composable
internal fun stateLabelColor(state: TorrentState): Color = labelColor(state, KachokTheme.colors)

private fun labelColor(
    state: TorrentState,
    scheme: KachokColors,
): Color =
    when (state) {
        TorrentState.Metadata -> scheme.textMuted
        TorrentState.Checking, TorrentState.Stopping -> scheme.warning
        TorrentState.Downloading -> scheme.text
        TorrentState.Seeding -> scheme.accentText
        TorrentState.Paused, TorrentState.Queued -> scheme.textFaint
        TorrentState.Error -> scheme.error
    }

/** The proportion of the bar a metadata row fills: it has no percentage, so it shows a position. */
internal const val METADATA_BAR_FRACTION: Float = 0.34f

/** The design's indeterminate fill: the primary role held back, because there is no figure yet. */
private const val METADATA_BAR_ALPHA = 0.55f

/**
 * Selection recolours the row rather than tinting behind it.
 *
 * The design's selected row keeps every distinction it had — the headline figure is still brighter
 * than the merely-true one — in a palette drawn on `primaryContainer` instead of on the surface. A
 * background tint alone would leave `onSurfaceVariant` text on a teal ground, which is the one
 * combination in this palette that cannot be read.
 */
internal fun rowColor(
    model: TorrentRowModel,
    cell: RowCell,
    scheme: KachokColors,
): Color {
    val plain = plainRowColor(model, cell, scheme)
    return if (model.selected) plain.onContainer(scheme) else plain
}

private fun plainRowColor(
    model: TorrentRowModel,
    cell: RowCell,
    scheme: KachokColors,
): Color {
    val state = model.state
    return when (cell) {
        RowCell.Glyph -> {
            when (state) {
                TorrentState.Metadata, TorrentState.Paused, TorrentState.Queued -> scheme.textFaint
                TorrentState.Checking, TorrentState.Stopping -> scheme.warning
                TorrentState.Downloading, TorrentState.Seeding -> scheme.accentText
                TorrentState.Error -> scheme.error
            }
        }

        RowCell.Name -> {
            when (state) {
                // A magnet has no name; the info hash it shows instead is a figure, not a title.
                TorrentState.Metadata -> scheme.textMuted

                TorrentState.Paused, TorrentState.Queued -> scheme.textFaint

                TorrentState.Error -> scheme.error

                else -> scheme.text
            }
        }

        RowCell.Bar -> {
            when (state) {
                TorrentState.Metadata -> scheme.accent.copy(alpha = METADATA_BAR_ALPHA)
                TorrentState.Checking -> scheme.warning
                TorrentState.Downloading, TorrentState.Seeding -> scheme.accent
                TorrentState.Paused, TorrentState.Queued -> scheme.textFaint
                TorrentState.Stopping -> scheme.stoppingBar
                TorrentState.Error -> scheme.error
            }
        }

        RowCell.Track -> {
            if (state == TorrentState.Error) scheme.errorTrack else scheme.divider
        }

        // Checking is the one state whose percentage is its own progress rather than the
        // download's, so it is drawn in the role that says "not downloading".
        RowCell.Percent -> {
            if (state == TorrentState.Checking) {
                scheme.warning
            } else {
                model.emphasisOf(cell).color(state, scheme)
            }
        }

        RowCell.Label -> {
            labelColor(state, scheme)
        }

        else -> {
            model.emphasisOf(cell).color(state, scheme)
        }
    }
}
