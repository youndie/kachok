package ru.workinprogress.kachok.ui.remove

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import ru.workinprogress.kachok.ui.theme.KachokTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The dialog behind the ellipsis, and the one thing it must never do quietly.
 *
 * Removing a torrent is undoable — add it again and the engine finds what is on disk. Deleting the
 * data is not, so the two must never be one press apart by accident: the checkbox is off, and the
 * button says which of the two it is about to do.
 */
@OptIn(ExperimentalTestApi::class)
class RemoveTorrentTest {
    private val state =
        RemoveState(name = "Sintel-2010-1080p-DCP.tar", where = "~/Downloads", howMuch = "2.89 GiB on disk")

    @Test
    fun deletingTheDataIsOffUntilSomebodyAsksForIt() {
        assertEquals(false, state.deleteData, "a remove dialog that opens armed is the accident")
        assertEquals(true, state.withData(true).deleteData)
        assertEquals("Sintel-2010-1080p-DCP.tar", state.withData(true).name, "the toggle lost the torrent")
    }

    /** What the button says is what will happen. */
    @Test
    fun theButtonNamesWhichOfTheTwoThingsItDoes() =
        runComposeUiTest {
            setContent { KachokTheme { RemoveTorrentDialog(state) } }
            onNodeWithText("Remove").assertIsDisplayed()
            assertEquals(
                0,
                onAllNodesWithText("cannot be undone", substring = true).fetchSemanticsNodes().size,
                "nothing irreversible is being offered yet",
            )
        }

    @Test
    fun tickingTheBoxChangesTheButtonAndSaysWhy() =
        runComposeUiTest {
            setContent { KachokTheme { RemoveTorrentDialog(state.withData(true)) } }
            onNodeWithText("Remove and delete").assertIsDisplayed()
            assertEquals(
                1,
                onAllNodesWithText("cannot be undone", substring = true).fetchSemanticsNodes().size,
            )
        }

    /** Every control in it reports, which is the guard three screens in this window needed. */
    @Test
    fun everyControlLeavesTheDialog() =
        runComposeUiTest {
            var cancelled = 0
            var removed = 0
            val toggles = mutableListOf<Boolean>()
            setContent {
                KachokTheme {
                    RemoveTorrentDialog(
                        state,
                        onCancel = { cancelled++ },
                        onToggleData = { toggles += it },
                        onRemove = { removed++ },
                    )
                }
            }
            onNodeWithContentDescription(DELETE_DATA).performClick()
            onNodeWithContentDescription("Cancel").performClick()
            onNodeWithContentDescription("Remove").performClick()
            assertEquals(listOf(true), toggles, "the checkbox reported the value it would become")
            assertEquals(1, cancelled)
            assertEquals(1, removed)
        }

    /** The checkbox names a directory, because "delete the data" is unanswerable without one. */
    @Test
    fun theDialogSaysWhatWouldGoAndFromWhere() =
        runComposeUiTest {
            setContent { KachokTheme { RemoveTorrentDialog(state) } }
            onNodeWithText("Sintel-2010-1080p-DCP.tar").assertIsDisplayed()
            onNodeWithText("2.89 GiB on disk").assertIsDisplayed()
            onNodeWithText("~/Downloads").assertIsDisplayed()
            assertTrue(DELETE_DATA.isNotBlank())
        }
}
