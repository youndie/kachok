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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.workinprogress.kachok.ui.icons.Glyph
import ru.workinprogress.kachok.ui.icons.Icons
import ru.workinprogress.kachok.ui.list.TorrentState
import ru.workinprogress.kachok.ui.theme.ChromeButton
import ru.workinprogress.kachok.ui.theme.ChromeText
import ru.workinprogress.kachok.ui.theme.KachokPalette

/**
 * What a toolbar control asks for when it is pressed.
 *
 * An enum rather than the label, so the `when` that handles them is exhaustive and adding a control
 * without handling it does not compile. The four toolbar buttons that quietly did nothing for a
 * release were a `when` on strings with an `else ->` at the bottom
 * ([B-56](../../../../../../../../docs/backlog/B-56-dead-toolbar-controls.md)).
 */
internal enum class ToolbarCommand {
    AddTorrent,
    PasteMagnet,
    Pause,
    Resume,
    Remove,
    ToggleDetails,
    ToggleSettings,
}

/**
 * One toolbar control: what it draws, what it is called, and what it asks for.
 *
 * **[command] and [disabledBecause] are the two halves of one fact** and exactly one of them is
 * set. A control with no command cannot be pressed, and a control that can be pressed has
 * somewhere for the press to go — `ToolbarStateTest` asserts that of every one of them, which is
 * the check that was missing.
 */
internal class ToolbarAction(
    val glyph: String,
    val label: String,
    val command: ToolbarCommand? = null,
    /** Why this cannot be used yet. Null when it can. */
    val disabledBecause: String? = null,
    val active: Boolean = false,
) {
    init {
        require((command == null) != (disabledBecause == null)) {
            "$label must either do something or say why it does not"
        }
    }

    val enabled: Boolean get() = disabledBecause == null
}

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
            .clickable(enabled = action.enabled, onClick = onClick)
            // A glyph and nothing else. Without this the button has no name at all — not to a
            // screen reader, and not to a test, which is why the bar's guard could only ever click
            // the one control that happens to carry text.
            .semantics {
                contentDescription = action.label
                if (!action.enabled) disabled()
            },
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
            .semantics { contentDescription = "Add torrent" }
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
    val addTorrent: ToolbarAction = ToolbarAction(Icons.ADD, "Add torrent", ToolbarCommand.AddTorrent),
    val pasteMagnet: ToolbarAction = ToolbarAction(Icons.LINK, "Paste magnet", ToolbarCommand.PasteMagnet),
    // Pause, Resume and Remove are decided by [forSelection]; they default to the state a window
    // with nothing selected is in. Force re-check is still waiting on the engine, and is greyed
    // rather than hidden — which is what the design does with *Resume* — and greyed rather than
    // live-and-inert, which is what all four of these were.
    val pause: ToolbarAction =
        ToolbarAction(Icons.PAUSE, "Pause", disabledBecause = NOTHING_SELECTED),
    val resume: ToolbarAction =
        ToolbarAction(Icons.PLAY_ARROW, "Resume", disabledBecause = NOTHING_SELECTED),
    val remove: ToolbarAction =
        ToolbarAction(Icons.DELETE, "Remove…", disabledBecause = NOTHING_SELECTED),
    val recheck: ToolbarAction =
        ToolbarAction(Icons.RESTART_ALT, "Force re-check", disabledBecause = NO_RECHECK_COMMAND),
    val filter: String = "Filter",
    val details: ToolbarAction =
        ToolbarAction(Icons.RIGHT_PANEL_OPEN, "Details panel", ToolbarCommand.ToggleDetails),
    val settings: ToolbarAction = ToolbarAction(Icons.TUNE, "Settings", ToolbarCommand.ToggleSettings),
) {
    /** Every control on the bar, in the order it is drawn. */
    val all: List<ToolbarAction>
        get() = listOf(addTorrent, pasteMagnet, pause, resume, remove, recheck, details, settings)

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
            details = ToolbarAction(details.glyph, details.label, details.command, active = open),
            settings =
                ToolbarAction(
                    this.settings.glyph,
                    this.settings.label,
                    this.settings.command,
                    active = settings,
                ),
        )

    /**
     * What the two transport buttons do, which depends on the row that is selected.
     *
     * A torrent that is already paused cannot be paused, and one that is running cannot be
     * resumed — and neither can be done to nothing. Each case says which it is, because "greyed"
     * with no reason is the state that had a person clicking four dead buttons.
     */
    fun forSelection(selected: TorrentState?): ToolbarState =
        ToolbarState(
            addTorrent = addTorrent,
            pasteMagnet = pasteMagnet,
            pause =
                when (selected) {
                    null -> ToolbarAction(Icons.PAUSE, "Pause", disabledBecause = NOTHING_SELECTED)
                    TorrentState.Paused -> ToolbarAction(Icons.PAUSE, "Pause", disabledBecause = ALREADY_PAUSED)
                    else -> ToolbarAction(Icons.PAUSE, "Pause", ToolbarCommand.Pause)
                },
            resume =
                when (selected) {
                    null -> ToolbarAction(Icons.PLAY_ARROW, "Resume", disabledBecause = NOTHING_SELECTED)
                    TorrentState.Paused -> ToolbarAction(Icons.PLAY_ARROW, "Resume", ToolbarCommand.Resume)
                    else -> ToolbarAction(Icons.PLAY_ARROW, "Resume", disabledBecause = NOT_PAUSED)
                },
            remove =
                if (selected == null) {
                    ToolbarAction(Icons.DELETE, "Remove…", disabledBecause = NOTHING_SELECTED)
                } else {
                    // Enabled for a paused or degraded torrent too: removing one is often exactly
                    // what a person wants to do about it.
                    ToolbarAction(Icons.DELETE, "Remove…", ToolbarCommand.Remove)
                },
            recheck = recheck,
            filter = filter,
            details = details,
            settings = settings,
        )

    private companion object {
        const val NOTHING_SELECTED =
            "There is no torrent selected to do this to."
        const val ALREADY_PAUSED =
            "This torrent is already paused."
        const val NOT_PAUSED =
            "This torrent is running; there is nothing to resume."
        const val NO_RECHECK_COMMAND =
            "The engine verifies on start-up and has no command to do it again (B-59)."
    }
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
        AddTorrentButton { onAction(state.addTorrent) }
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
