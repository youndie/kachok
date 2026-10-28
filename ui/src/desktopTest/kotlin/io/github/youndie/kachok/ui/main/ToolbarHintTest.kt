package io.github.youndie.kachok.ui.main

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.youndie.kachok.ui.list.TorrentState
import io.github.youndie.kachok.ui.theme.KachokTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B-144: resting the pointer on an icon button says what it does, and for a greyed one why it is
 * greyed. The names were in the semantics tree all along; this is the half a person with a mouse
 * can see.
 */
@OptIn(ExperimentalTestApi::class)
class ToolbarHintTest {
    @Test
    fun aHoveredButtonSaysWhatItDoes(): Unit =
        runComposeUiTest {
            setContent { KachokTheme { Toolbar(ToolbarState().forSelection(TorrentState.Downloading)) } }
            assertEquals(0, onAllNodesWithText("Force re-announce").fetchSemanticsNodes().size, "shown before a hover")

            onNodeWithContentDescription("Force re-announce").performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(HINT_DELAY_MILLIS + 200L)

            onNodeWithText("Force re-announce").assertExists("the hover drew no tooltip")
        }

    @Test
    fun aGreyedButtonsHintSaysWhyItIsGreyed(): Unit =
        runComposeUiTest {
            val bar = ToolbarState().forSelection(TorrentState.Paused)
            setContent { KachokTheme { Toolbar(bar) } }

            onNodeWithContentDescription("Pause").performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(HINT_DELAY_MILLIS + 200L)

            onNodeWithText(bar.pause.hint).assertExists("the tooltip did not carry the reason")
        }
}
