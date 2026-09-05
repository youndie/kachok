package ru.workinprogress.kachok.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.workinprogress.kachok.ui.icons.Glyph
import ru.workinprogress.kachok.ui.icons.Icons
import ru.workinprogress.kachok.ui.theme.ChromeButton
import ru.workinprogress.kachok.ui.theme.ChromeText
import ru.workinprogress.kachok.ui.theme.KachokPalette

/** One toolbar action: what it draws, what it is called, and whether it can be used right now. */
internal class ToolbarAction(
    val glyph: String,
    val label: String,
    val enabled: Boolean = true,
    val active: Boolean = false,
)

/**
 * A 28 dp icon button.
 *
 * Three states and no more, because the design draws three: available, unavailable, and on. An
 * unavailable one is dimmed rather than hidden — the design's *Resume* is greyed while a torrent is
 * running, so the toolbar does not reshuffle every time a download starts.
 */
@Composable
private fun IconAction(
    action: ToolbarAction,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val background = if (action.active) scheme.primaryContainer else Color.Transparent
    val tint =
        when {
            action.active -> KachokPalette.primaryBright
            action.enabled -> KachokPalette.onSurfaceMuted
            else -> scheme.onSurfaceVariant
        }
    Box(
        Modifier
            .size(Chrome.controlHeight)
            .background(background, RoundedCornerShape(CONTROL_RADIUS))
            .clickable(enabled = action.enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(action.glyph, size = ACTION_GLYPH, tint = tint)
    }
}

/**
 * The only tonal button in the whole window.
 *
 * `FilledTonalButton`'s own geometry is 40 dp high with a full-height corner radius; the design
 * asks for 28 dp and 4 dp, which is every dimension the component has, so this is drawn rather
 * than configured.
 */
@Composable
private fun AddTorrentButton(onClick: () -> Unit) {
    Row(
        Modifier
            .height(Chrome.controlHeight)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(CONTROL_RADIUS))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Glyph(Icons.ADD, size = BUTTON_GLYPH, tint = KachokPalette.primaryBright)
        Text("Add torrent", style = ChromeButton, color = KachokPalette.primaryBright, maxLines = 1)
    }
}

/**
 * The filter box.
 *
 * An outlined box rather than `OutlinedTextField`, which reserves room for a floating label and a
 * supporting line and cannot be 28 dp high with either. It types nothing yet; the field arrives
 * with the shell's behaviour in [B-52](../../../../../../../../docs/backlog/B-52-ui-on-the-real-engine.md).
 */
@Composable
private fun FilterField(text: String) {
    Row(
        Modifier
            .height(Chrome.controlHeight)
            .width(FILTER_WIDTH)
            .border(Chrome.hairline, MaterialTheme.colorScheme.outline, RoundedCornerShape(CONTROL_RADIUS))
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Glyph(Icons.SEARCH, size = SEARCH_GLYPH, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = ChromeText, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** What the toolbar is showing. The commands behind it are the caller's; this draws them. */
internal class ToolbarState(
    val pasteMagnet: ToolbarAction = ToolbarAction(Icons.LINK, "Paste magnet"),
    val pause: ToolbarAction = ToolbarAction(Icons.PAUSE, "Pause"),
    val resume: ToolbarAction = ToolbarAction(Icons.PLAY_ARROW, "Resume", enabled = false),
    val remove: ToolbarAction = ToolbarAction(Icons.DELETE, "Remove…"),
    val recheck: ToolbarAction = ToolbarAction(Icons.RESTART_ALT, "Force re-check"),
    val filter: String = "Filter",
    val details: ToolbarAction = ToolbarAction(Icons.RIGHT_PANEL_OPEN, "Details panel"),
    val settings: ToolbarAction = ToolbarAction(Icons.TUNE, "Settings"),
) {
    /** The same state with both toggles set from whether their screens are actually there. */
    fun withDetails(
        open: Boolean,
        settings: Boolean = false,
    ): ToolbarState =
        ToolbarState(
            pasteMagnet = pasteMagnet,
            pause = pause,
            resume = resume,
            remove = remove,
            recheck = recheck,
            filter = filter,
            details = ToolbarAction(details.glyph, details.label, active = open),
            settings = ToolbarAction(this.settings.glyph, this.settings.label, active = settings),
        )
}

@Composable
internal fun Toolbar(
    state: ToolbarState,
    modifier: Modifier = Modifier,
    onAction: (ToolbarAction) -> Unit = {},
) {
    Bar(
        height = Chrome.toolbarHeight,
        background = MaterialTheme.colorScheme.surface,
        line = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AddTorrentButton { onAction(ADD_TORRENT) }
        IconAction(state.pasteMagnet) { onAction(state.pasteMagnet) }
        ToolbarSeparator()
        listOf(state.pause, state.resume, state.remove, state.recheck).forEach { action ->
            IconAction(action) { onAction(action) }
        }
        Spacer()
        FilterField(state.filter)
        ToolbarSeparator()
        IconAction(state.details) { onAction(state.details) }
        IconAction(state.settings) { onAction(state.settings) }
    }
}

/** The tonal button's identity when it reports itself, so a caller has one `when` and not two. */
internal val ADD_TORRENT: ToolbarAction = ToolbarAction(Icons.ADD, "Add torrent")

private val ACTION_GLYPH = 18.sp

private val BUTTON_GLYPH = 17.sp

private val SEARCH_GLYPH = 16.sp

/**
 * 220, not the design's `width: 200px`.
 *
 * CSS measures that width inside the border and the padding; the box on screen is 200 + 18 + 2,
 * and 200 here would be a field twenty pixels narrower than the one in the picture. Every other
 * dimension in the design happens to have no padding or border to add.
 */
private val FILTER_WIDTH = 220.dp

private val CONTROL_RADIUS = 4.dp
