package io.github.youndie.kachok.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.youndie.kachok.ui.icons.Icons
import io.github.youndie.kachok.ui.list.TorrentState
import io.github.youndie.kachok.ui.theme.KachokTheme

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
    Recheck,
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
    /** A toggle's state, or null for a control that is not a toggle. */
    val active: Boolean? = null,
) {
    init {
        require((command == null) != (disabledBecause == null)) {
            "$label must either do something or say why it does not"
        }
    }

    val enabled: Boolean get() = disabledBecause == null

    /** What the tooltip says: the name, and for a greyed control why it is greyed (B-144). */
    val hint: String get() = disabledBecause?.let { "$label — $it" } ?: label
}

/**
 * One icon control, drawn by the kit (B-176), with the hint that says what it does — and for a
 * greyed one, why.
 */
@Composable
private fun IconAction(
    action: ToolbarAction,
    onClick: () -> Unit,
) {
    Hint(action.hint) {
        KachokTheme.kit.ToolButton(action.glyph, action.label, action.enabled, action.active, onClick)
    }
}

/** What the toolbar is showing. The commands behind it are the caller's; this draws them. */
internal class ToolbarState(
    val addTorrent: ToolbarAction = ToolbarAction(Icons.ADD, "Add torrent", ToolbarCommand.AddTorrent),
    val pasteMagnet: ToolbarAction = ToolbarAction(Icons.LINK, "Paste magnet", ToolbarCommand.PasteMagnet),
    // All four are decided by [forSelection]; they default to the state a window with nothing
    // selected is in. They used to be greyed with an engine gap named on each — greyed rather than
    // hidden, which is what the design does with *Resume*, and greyed rather than live-and-inert,
    // which is what all four of them were.
    val pause: ToolbarAction =
        ToolbarAction(Icons.PAUSE, "Pause", disabledBecause = NOTHING_SELECTED),
    val resume: ToolbarAction =
        ToolbarAction(Icons.PLAY_ARROW, "Resume", disabledBecause = NOTHING_SELECTED),
    val remove: ToolbarAction =
        ToolbarAction(Icons.DELETE, "Remove…", disabledBecause = NOTHING_SELECTED),
    val recheck: ToolbarAction =
        ToolbarAction(Icons.RESTART_ALT, "Force re-check", disabledBecause = NOTHING_SELECTED),
    val filter: String = "",
    val details: ToolbarAction =
        ToolbarAction(Icons.RIGHT_PANEL_OPEN, "Details panel", ToolbarCommand.ToggleDetails, active = false),
    val settings: ToolbarAction =
        ToolbarAction(Icons.TUNE, "Settings", ToolbarCommand.ToggleSettings, active = false),
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
            recheck =
                when (selected) {
                    null -> {
                        ToolbarAction(Icons.RESTART_ALT, "Force re-check", disabledBecause = NOTHING_SELECTED)
                    }

                    // Not for a magnet: there is no torrent yet, so there is nothing to check the
                    // disk against.
                    TorrentState.Metadata -> {
                        ToolbarAction(Icons.RESTART_ALT, "Force re-check", disabledBecause = NO_METAINFO_YET)
                    }

                    TorrentState.Checking -> {
                        ToolbarAction(Icons.RESTART_ALT, "Force re-check", disabledBecause = ALREADY_CHECKING)
                    }

                    else -> {
                        ToolbarAction(Icons.RESTART_ALT, "Force re-check", ToolbarCommand.Recheck)
                    }
                },
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
        const val NO_METAINFO_YET =
            "There is no torrent yet, so there is nothing to check the disk against."
        const val ALREADY_CHECKING =
            "This torrent is being checked right now."
        // No *Force re-announce* here any more (B-184): neither kit had a glyph that said it, and
        // the Trackers tab's *Re-announce* is the same command beside the trackers it is sent to.
    }
}

@Composable
internal fun Toolbar(
    state: ToolbarState,
    modifier: Modifier = Modifier,
    onAction: (ToolbarAction) -> Unit = {},
    onFilter: (String) -> Unit = {},
    /**
     * The window is under 800 dp, so the filter gives up most of its width.
     *
     * At 600 dp the bar's fixed contents — the button, the controls and a 220 dp field — came to
     * more than the window, and the last glyph was drawn half off the edge. The field is the only
     * thing here that can be smaller without becoming a different control.
     */
    narrow: Boolean = false,
) {
    Bar(
        height = Chrome.toolbarHeight,
        background = KachokTheme.colors.surface,
        line = KachokTheme.colors.raisedHigh,
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KachokTheme.kit.ToolMainButton(state.addTorrent.glyph, state.addTorrent.label, compact = narrow) {
            onAction(state.addTorrent)
        }
        IconAction(state.pasteMagnet) { onAction(state.pasteMagnet) }
        ToolbarSeparator()
        listOf(state.pause, state.resume, state.remove, state.recheck).forEach { action ->
            IconAction(action) { onAction(action) }
        }
        Spacer()
        KachokTheme.kit.FilterField(
            state.filter,
            placeholder = "Filter",
            narrow = narrow,
            onFilter = onFilter,
        )
        ToolbarSeparator()
        IconAction(state.details) { onAction(state.details) }
        IconAction(state.settings) { onAction(state.settings) }
    }
}
