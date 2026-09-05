package ru.workinprogress.kachok.ui.main

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

    @Test
    fun everyControlEitherDoesSomethingOrSaysWhyItDoesNot() {
        toolbar.all.forEach { action ->
            assertTrue(
                (action.command == null) != (action.disabledBecause == null),
                "${action.label}: command=${action.command} disabled=${action.disabledBecause}",
            )
        }
    }

    @Test
    fun everyEnabledControlCarriesACommand() {
        val enabled = toolbar.all.filter { it.enabled }
        assertEquals(
            listOf("Add torrent", "Paste magnet", "Details panel", "Settings"),
            enabled.map { it.label },
        )
        assertEquals(ToolbarCommand.entries.toSet(), enabled.mapNotNull { it.command }.toSet())
    }

    /**
     * And every disabled one says which engine change it is waiting for, by name.
     *
     * A greyed button with no reason is the same defect one step quieter: nobody can tell whether
     * it is broken, unimplemented, or off because of what is selected.
     */
    @Test
    fun everyDisabledControlNamesTheItemThatWouldEnableIt() {
        val disabled = toolbar.all.filter { !it.enabled }
        assertEquals(listOf("Pause", "Resume", "Remove…", "Force re-check"), disabled.map { it.label })
        disabled.forEach { action ->
            val reason = action.disabledBecause.orEmpty()
            assertTrue(reason.length > SHORT, "${action.label}: $reason")
            assertTrue(Regex("B-\\d\\d").containsMatchIn(reason), "${action.label} names no item: $reason")
        }
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

    private companion object {
        const val SHORT = 20
    }
}
