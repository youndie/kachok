package ru.workinprogress.kachok.ui.main

import ru.workinprogress.kachok.ui.list.TorrentState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The guard [B-56](../../../../../../../../docs/backlog/B-56-dead-toolbar-controls.md) exists for:
 * a control that can be pressed has somewhere for the press to go.
 *
 * Four toolbar buttons looked available and did nothing for a release. Nothing caught it — the
 * goldens drew them correctly, the tests clicked the two that worked, and the handler was a `when`
 * on labels with an `else ->` at the bottom that swallowed the rest.
 *
 * Two things stop it happening again: `ToolbarAction` refuses to exist with neither a command nor a
 * reason, and the handler now switches on a `ToolbarCommand`, so adding one without handling it
 * does not compile. This asserts the first and names the second.
 */
class ToolbarStateTest {
    private val toolbar = ToolbarState()

    /** In every selection, not only the default one. */
    @Test
    fun everyControlEitherDoesSomethingOrSaysWhyItDoesNot() {
        everySelection.forEach { selection ->
            toolbar.forSelection(selection).all.forEach { action ->
                assertTrue(
                    (action.command == null) != (action.disabledBecause == null),
                    "$selection / ${action.label}: command=${action.command} disabled=${action.disabledBecause}",
                )
            }
        }
    }

    @Test
    fun everyEnabledControlCarriesACommand() {
        assertEquals(
            listOf("Add torrent", "Paste magnet", "Details panel", "Settings"),
            toolbar
                .forSelection(null)
                .all
                .filter { it.enabled }
                .map { it.label },
            "with nothing selected there is nothing to pause or resume",
        )
        assertEquals(
            listOf("Add torrent", "Paste magnet", "Pause", "Remove…", "Details panel", "Settings"),
            toolbar
                .forSelection(TorrentState.Downloading)
                .all
                .filter { it.enabled }
                .map { it.label },
        )
        assertEquals(
            listOf("Add torrent", "Paste magnet", "Resume", "Remove…", "Details panel", "Settings"),
            toolbar
                .forSelection(TorrentState.Paused)
                .all
                .filter { it.enabled }
                .map { it.label },
        )
    }

    /**
     * And no command is unreachable.
     *
     * The set is taken across every selection, because Pause and Resume are enabled in different
     * ones and a per-selection assertion can never see both.
     */
    @Test
    fun everyCommandIsReachableFromSomeSelection() {
        assertEquals(
            ToolbarCommand.entries.toSet(),
            everySelection
                .flatMap { toolbar.forSelection(it).all }
                .mapNotNull { it.command }
                .toSet(),
        )
    }

    /** Pausing a paused torrent, and resuming a running one, are the two nonsense cases. */
    @Test
    fun neitherTransportButtonOffersWhatTheTorrentIsAlreadyDoing() {
        val paused = toolbar.forSelection(TorrentState.Paused)
        assertEquals(null, paused.pause.command, "a paused torrent was offered a pause")
        assertTrue(
            paused.pause.disabledBecause
                .orEmpty()
                .contains("already"),
            paused.pause.disabledBecause.orEmpty(),
        )
        val running = toolbar.forSelection(TorrentState.Seeding)
        assertEquals(null, running.resume.command, "a running torrent was offered a resume")
        assertEquals(ToolbarCommand.Pause, running.pause.command)
    }

    /**
     * And every disabled one says which engine change it is waiting for, by name.
     *
     * A greyed button with no reason is the same defect one step quieter: nobody can tell whether
     * it is broken, unimplemented, or off because of what is selected.
     */
    @Test
    fun everyDisabledControlNamesTheItemThatWouldEnableIt() {
        val waitingOnTheEngine = listOf("Force re-check")
        everySelection.forEach { selection ->
            toolbar.forSelection(selection).all.filter { !it.enabled }.forEach { action ->
                val reason = action.disabledBecause.orEmpty()
                assertTrue(reason.length > SHORT, "$selection / ${action.label}: $reason")
                // Two kinds of "off", and they are not interchangeable. A control waiting on an
                // engine change names the item, because the reason outlives this window; one that
                // is off because of what is selected must not, because there is nothing to build.
                if (action.label in waitingOnTheEngine) {
                    assertTrue(
                        Regex("B-\\d\\d").containsMatchIn(reason),
                        "${action.label} names no item: $reason",
                    )
                }
            }
        }
        assertEquals(
            waitingOnTheEngine,
            everySelection
                .flatMap { toolbar.forSelection(it).all }
                .filter { !it.enabled && Regex("B-\\d\\d").containsMatchIn(it.disabledBecause.orEmpty()) }
                .map { it.label }
                .distinct(),
            "a control blamed an engine gap for something the selection decides",
        )
    }

    /** Toggling a panel must not lose the command that toggles it back. */
    @Test
    fun theTogglesKeepTheirCommandsWhenTheyChangeState() {
        val open = toolbar.withDetails(open = true, settings = true)
        assertEquals(ToolbarCommand.ToggleDetails, open.details.command)
        assertEquals(ToolbarCommand.ToggleSettings, open.settings.command)
        assertTrue(open.details.active && open.settings.active)
        assertTrue(open.all.all { it.enabled == toolbar.all.single { was -> was.label == it.label }.enabled })
    }

    /** Null is a selection: it is the window with an empty list, or one nobody has clicked. */
    private val everySelection: List<TorrentState?> = listOf(null) + TorrentState.entries

    private companion object {
        const val SHORT = 20
    }
}
