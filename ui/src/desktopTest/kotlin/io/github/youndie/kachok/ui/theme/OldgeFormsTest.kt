package io.github.youndie.kachok.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.youndie.kachok.ui.add.AddTorrentDialog
import io.github.youndie.kachok.ui.add.designTorrentToAdd
import io.github.youndie.kachok.ui.main.EmptyState
import io.github.youndie.kachok.ui.remove.DELETE_DATA
import io.github.youndie.kachok.ui.remove.RemoveState
import io.github.youndie.kachok.ui.remove.RemoveTorrentDialog
import io.github.youndie.kachok.ui.session.Preferences
import io.github.youndie.kachok.ui.session.settingsOf
import io.github.youndie.kachok.ui.settings.SettingChange
import io.github.youndie.kachok.ui.settings.SettingKey
import io.github.youndie.kachok.ui.settings.SettingsScreen
import io.github.youndie.kachok.ui.theme.oldge.OldgeKit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B-178: under the oldge kit the settings fields, the segmented choice, *Browse…*, the empty state's
 * *Add a torrent* and the dialogs' frame are oldge-ui's components, and each still does what the
 * Material one does.
 */
@OptIn(ExperimentalTestApi::class)
class OldgeFormsTest {
    private val preferences =
        Preferences(directory = "~/Downloads", interfaceKit = InterfaceKit.Oldge)

    @Test
    fun aNumberTypedIntoTheFieldReachesTheSetting(): Unit =
        runComposeUiTest {
            val changes = settings()

            numberField(MAX_PEERS).performScrollTo().performTextReplacement("80")

            val typed = changes.filterIsInstance<SettingChange.Typed>().single()
            assertEquals(SettingKey.MaxPeers, typed.key)
            assertEquals("80", typed.text)
        }

    /**
     * The field draws its own label above its well, and the row has drawn the same words on its left
     * already: the field's is laid out above the field's bounds and is not on screen.
     */
    @Test
    fun theFieldDoesNotDrawItsLabelOverTheRow(): Unit =
        runComposeUiTest {
            settings()
            numberField(MAX_PEERS).performScrollTo()

            val labels = onAllNodesWithText(MAX_PEERS)
            val count = labels.fetchSemanticsNodes().size
            assertEquals(2, count, "the row's label and the field's")
            assertEquals(1, (0 until count).count { labels[it].isDisplayed() }, "only the row's is on screen")
        }

    @Test
    fun theSegmentedChoiceReportsTheChoiceAndNotTheChosen(): Unit =
        runComposeUiTest {
            val changes = settings()
            onNodeWithText(InterfaceKit.Oldge.label).performScrollTo().assertIsSelected()

            onNodeWithText(InterfaceKit.Oldge.label).performClick()
            onNodeWithText(InterfaceKit.Material.label).performClick()

            val chose = changes.filterIsInstance<SettingChange.Chose>().single()
            assertEquals(SettingKey.Interface, chose.key)
            assertEquals(InterfaceKit.Material.stored, chose.value)
        }

    @Test
    fun browseReportsThePressAndTheDirectoryIsNotTyped(): Unit =
        runComposeUiTest {
            val changes = settings()

            onNodeWithContentDescription("Browse").performScrollTo().performClick()
            assertEquals(SettingKey.SaveTo, changes.filterIsInstance<SettingChange.Browsed>().single().key)

            // Picked, never typed: a press on the well does not give it the keyboard.
            onNode(
                hasSetTextAction() and hasContentDescription(SAVE_TO),
            ).performScrollTo().performClick().assertIsNotFocused()
        }

    @Test
    fun addATorrentReportsThePress(): Unit =
        runComposeUiTest {
            var added = 0
            setContent { KachokTheme(kit = OldgeKit.Default) { EmptyState(onAdd = { added++ }) } }

            onNodeWithText("Add a torrent").performClick()

            assertEquals(1, added)
        }

    /** The add dialog in the library's window, at its own width, its body and *Browse…* answering. */
    @Test
    fun theAddDialogKeepsItsWidthAndItsBody(): Unit =
        runComposeUiTest {
            var browsed = 0
            var cancelled = 0
            setContent {
                KachokTheme(kit = OldgeKit.Default) {
                    AddTorrentDialog(
                        designTorrentToAdd,
                        Modifier.testTag(DIALOG),
                        onCancel = { cancelled++ },
                        onBrowse = { browsed++ },
                    )
                }
            }

            onNodeWithTag(DIALOG).assertWidthIsEqualTo(ADD_WIDTH)
            onNodeWithText("Add torrent").assertIsDisplayed()
            onNode(hasSetTextAction() and hasContentDescription(SAVE_TO)).assertIsDisplayed()
            onNodeWithText("Browse…").performClick()
            onNodeWithContentDescription("Cancel").performClick()

            assertEquals(1, browsed)
            assertEquals(1, cancelled)
        }

    @Test
    fun theRemoveDialogKeepsItsWidthAndItsBody(): Unit =
        runComposeUiTest {
            val toggled = mutableListOf<Boolean>()
            setContent {
                KachokTheme(kit = OldgeKit.Default) {
                    RemoveTorrentDialog(
                        RemoveState(name = "Sintel.tar", where = "~/Downloads", howMuch = "2.89 GiB on disk"),
                        Modifier.testTag(DIALOG),
                        onToggleData = { toggled += it },
                    )
                }
            }

            onNodeWithTag(DIALOG).assertWidthIsEqualTo(REMOVE_WIDTH)
            onNodeWithText("Remove torrent").assertIsDisplayed()
            onNodeWithText("Sintel.tar").assertIsDisplayed()
            onNodeWithContentDescription(DELETE_DATA).performClick()

            assertEquals(listOf(true), toggled)
        }

    private fun ComposeUiTest.settings(): List<SettingChange> {
        val changes = mutableListOf<SettingChange>()
        setContent {
            KachokTheme(kit = OldgeKit.Default) { SettingsScreen(settingsOf(preferences)) { changes += it } }
        }
        return changes
    }

    private fun ComposeUiTest.numberField(label: String): SemanticsNodeInteraction =
        onNode(hasSetTextAction() and hasContentDescription(label))

    private companion object {
        const val MAX_PEERS = "Connections to keep up"
        const val SAVE_TO = "Save to"
        const val DIALOG = "dialog"
        val ADD_WIDTH = 520.dp
        val REMOVE_WIDTH = 420.dp
    }
}
