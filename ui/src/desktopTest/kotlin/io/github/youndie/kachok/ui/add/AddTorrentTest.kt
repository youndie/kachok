package io.github.youndie.kachok.ui.add

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.youndie.kachok.ui.theme.KachokTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rule of [B-56](../../../../../../../../docs/backlog/B-56-dead-toolbar-controls.md) applied to
 * the last screen that had not been asked: a control either does something, or says on itself why
 * it does not.
 */
@OptIn(ExperimentalTestApi::class)
class AddTorrentTest {
    /**
     * Nothing in this dialog is drawn live and connected to nothing.
     *
     * One is left: *Add paused*, drawn before the engine had a paused state and still waiting on
     * the dialog to pass the choice through. The file ticks
     * ([B-67](../../../../../../../../docs/backlog/B-67-per-file-selection.md)) and *Sequential
     * download* ([B-65](../../../../../../../../docs/backlog/B-65-sequential-download.md)) used to
     * be here and are live now.
     *
     * The failure this catches is somebody deleting a badge because the control "looks finished",
     * which is how three screens in this window got the way they were.
     *
     * *Start immediately* carries no badge and needs none: it is the option already chosen, and
     * pressing the selected radio does nothing in any dialog ever written.
     */
    @Test
    fun everyControlWaitingOnTheEngineWearsTheBadge(): Unit =
        runComposeUiTest {
            setContent { KachokTheme { AddTorrentDialog(designTorrentToAdd) } }
            assertEquals(
                PLANNED_CONTROLS,
                onAllNodesWithText("planned").fetchSemanticsNodes().size,
                "add-paused is the one control still waiting on something",
            )
            onNodeWithText("Add paused").assertIsDisplayed()
            onNodeWithText("Sequential download").assertIsDisplayed()
        }

    /** And the one that stopped waiting reports, like every other control in this dialog. */
    @Test
    fun theSequentialTickLeavesTheDialog(): Unit =
        runComposeUiTest {
            val asked = mutableListOf<Boolean>()
            setContent { KachokTheme { AddTorrentDialog(designTorrentToAdd, onSequential = { asked += it }) } }
            onNodeWithContentDescription(AddTorrentState.SEQUENTIAL).performClick()
            assertEquals(listOf(true), asked, "the box is off, so a click asks for on")
        }

    private companion object {
        const val PLANNED_CONTROLS = 1
    }
}
