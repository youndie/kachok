package io.github.youndie.kachok.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.youndie.kachok.ui.icons.Glyph
import io.github.youndie.kachok.ui.icons.Icons
import io.github.youndie.kachok.ui.main.SortColumn
import io.github.youndie.kachok.ui.theme.KachokPalette
import io.github.youndie.kachok.ui.theme.MonoFigure
import io.github.youndie.kachok.ui.theme.RowName
import io.github.youndie.kachok.ui.theme.RowNameMono
import io.github.youndie.kachok.ui.theme.RowStateLabel
import io.github.youndie.kachok.ui.theme.warningColors

/**
 * The nine columns, at the widths the design lays them out at.
 *
 * Read off the design document rather than guessed: a table whose columns are nearly right is a
 * table where every number sits a few pixels from where the eye expects it, and no single cell
 * looks wrong. The name column is the one that stretches — the design gives it `minmax(0, 1fr)`
 * and every other column a fixed pixel width — so [name] is what it comes out at in the 859 px
 * list of the main window, not a width the row imposes.
 */
public object TorrentColumns {
    public val name: Dp = 199.dp
    public val size: Dp = 60.dp
    public val progress: Dp = 92.dp
    public val down: Dp = 70.dp
    public val up: Dp = 70.dp
    public val peers: Dp = 100.dp
    public val ratio: Dp = 40.dp
    public val eta: Dp = 52.dp
    public val state: Dp = 76.dp

    public val gap: Dp = 10.dp
    public val edge: Dp = 10.dp

    public val rowHeight: Dp = 28.dp

    /** The line under a row. There is no elevation in this design; there are hairlines. */
    public val hairline: Dp = 1.dp
}

/**
 * The seven states, each of which is allowed to say a different amount
 * (`docs/design/design-tokens.md` §5).
 *
 * **A glyph and a colour, never a colour alone.** The design is explicit about it, and the reason
 * is testable: the state column has to survive a monochrome screenshot and a colour-blind reader.
 */
public enum class TorrentState(
    public val label: String,
    public val glyph: String,
) {
    Metadata("Metadata", Icons.LINK),
    Checking("Checking", Icons.RULE),
    Downloading("Downloading", Icons.ARROW_DOWNWARD),
    Seeding("Seeding", Icons.ARROW_UPWARD),
    Paused("Paused", Icons.PAUSE),
    Stopping("Stopping", Icons.HOURGLASS_TOP),
    Error("Error", Icons.ERROR),
}

/**
 * What a row draws. Not `SessionState`: a row is one line of it, and several are one list.
 *
 * A data class for one reason: which row is selected is decided after the list is built and
 * filtered, so `copy(selected = …)` is the alternative to threading an index through three
 * mapIndexed calls that have to agree with each other.
 */
public data class TorrentRowModel(
    public val name: String,
    public val size: String,
    public val progress: Float?,
    public val percent: String,
    public val down: String,
    public val up: String,
    public val connected: Int,
    public val unchoked: Int,
    public val outstanding: Int,
    public val ratio: String,
    public val eta: String,
    public val state: TorrentState,
    public val selected: Boolean = false,
)

/**
 * The bar, custom for a reason the design writes down: 4 dp, no M3-Expressive wave and no stop
 * indicator, because it redraws at 1 Hz and a wave animating under a number that changes once a
 * second is decoration fighting the data.
 */
@Composable
private fun ProgressCell(model: TorrentRowModel) {
    val scheme = MaterialTheme.colorScheme
    val warning = MaterialTheme.warningColors
    Row(
        Modifier.width(TorrentColumns.progress),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(BAR_HEIGHT)
                .background(rowColor(model, RowCell.Track, scheme, warning), RoundedCornerShape(BAR_HEIGHT / 2)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(model.progress ?: METADATA_BAR_FRACTION)
                    .height(BAR_HEIGHT)
                    .background(rowColor(model, RowCell.Bar, scheme, warning), RoundedCornerShape(BAR_HEIGHT / 2)),
            )
        }
        Text(
            model.percent,
            style = MonoFigure,
            color = rowColor(model, RowCell.Percent, scheme, warning),
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(PERCENT_WIDTH),
        )
    }
}

/**
 * `connected/unchoked · outstanding`, in one colour.
 *
 * One colour for the whole cell, which is what every one of the design's twenty-eight peers cells
 * does — see the deviation recorded in
 * [B-47](../../../../../../../../docs/backlog/B-47-torrent-row-and-states.md).
 */
internal fun peersText(model: TorrentRowModel): String = "${model.connected}/${model.unchoked} · ${model.outstanding}"

@Composable
private fun RowScope.Figure(
    text: String,
    width: Dp,
    color: Color,
) {
    Text(
        text,
        style = MonoFigure,
        color = color,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.width(width),
    )
}

@Composable
private fun ColumnScope.Cells(
    model: TorrentRowModel,
    visible: Set<SortColumn>,
) {
    val scheme = MaterialTheme.colorScheme
    val warning = MaterialTheme.warningColors

    fun color(cell: RowCell) = rowColor(model, cell, scheme, warning)
    Row(
        Modifier
            .fillMaxWidth()
            .height(TorrentColumns.rowHeight)
            .padding(horizontal = TorrentColumns.edge),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TorrentColumns.gap),
    ) {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
        ) {
            Glyph(model.state.glyph, tint = color(RowCell.Glyph))
            Text(
                model.name,
                // A magnet is an info hash until the metadata arrives, and a hash is not a name:
                // mono, so the digits line up and nobody reads it as a title.
                style = if (model.state == TorrentState.Metadata) RowNameMono else RowName,
                color = color(RowCell.Name),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (SortColumn.Size in visible) Figure(model.size, TorrentColumns.size, color(RowCell.Size))
        ProgressCell(model)
        Figure(model.down, TorrentColumns.down, color(RowCell.Down))
        if (SortColumn.Up in visible) Figure(model.up, TorrentColumns.up, color(RowCell.Up))
        if (SortColumn.Peers in visible) {
            Figure(peersText(model), TorrentColumns.peers, color(RowCell.Peers))
        }
        if (SortColumn.Ratio in visible) Figure(model.ratio, TorrentColumns.ratio, color(RowCell.Ratio))
        if (SortColumn.Eta in visible) Figure(model.eta, TorrentColumns.eta, color(RowCell.Eta))
        Row(
            Modifier.width(TorrentColumns.state),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                model.state.label,
                style = RowStateLabel,
                color = color(RowCell.Label),
                maxLines = 1,
            )
        }
    }
}

@Composable
public fun TorrentRow(
    model: TorrentRowModel,
    modifier: Modifier = Modifier,
    onSelect: (() -> Unit)? = null,
    /**
     * Which columns this row draws.
     *
     * The row is told rather than measuring: every row and the header have to agree, and nine
     * independent measurements of the same width is nine chances to disagree by a pixel.
     */
    visible: Set<SortColumn> = SortColumn.entries.toSet(),
) {
    // Selection is a ground, and error is the only *state* allowed to colour a whole row, because
    // it is the only one that is not going to fix itself. Selection wins where they meet: it is
    // the one the person just did.
    val tint =
        when {
            model.selected -> MaterialTheme.colorScheme.primaryContainer
            model.state == TorrentState.Error -> KachokPalette.errorRowTint
            else -> Color.Transparent
        }
    val hairline =
        if (model.selected) KachokPalette.primaryContainerHigh else KachokPalette.rowHairline
    Column(
        modifier
            .fillMaxWidth()
            .background(tint)
            .then(if (onSelect == null) Modifier else Modifier.clickable(onClick = onSelect)),
    ) {
        Cells(model, visible)
        Box(
            Modifier
                .fillMaxWidth()
                .height(TorrentColumns.hairline)
                .background(hairline),
        )
    }
}

/** The gap between a glyph and its text, and between the bar and its percentage. */
private val CELL_GAP = 7.dp

private val BAR_HEIGHT = 4.dp

/** Wide enough for `100%` and no wider, so every percentage ends on the same pixel. */
private val PERCENT_WIDTH = 29.dp
