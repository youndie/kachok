package ru.workinprogress.kachok.ui.add

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import ru.workinprogress.kachok.ui.theme.KachokTheme
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
     * Three of its controls wait on an engine change — the file ticks and *Sequential download* on
     * [B-67](../../../../../../../../docs/backlog/B-67-per-file-selection.md), *Add paused* on
     * [B-57](../../../../../../../../docs/backlog/B-57-a-paused-torrent.md) — and each says so with
     * the design's badge. The failure this catches is somebody deleting a badge because the control
     * "looks finished", which is how three screens in this window got the way they were.
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
                "the file list, sequential download and add-paused carry one each",
            )
            onNodeWithText("Add paused").assertIsDisplayed()
            onNodeWithText("Sequential download").assertIsDisplayed()
        }

    private companion object {
        const val PLANNED_CONTROLS = 3
    }
}
