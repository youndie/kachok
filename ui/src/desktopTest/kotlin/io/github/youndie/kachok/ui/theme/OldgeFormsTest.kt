package io.github.youndie.kachok.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
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
import kotlin.test.assertTrue

/**
 * B-178: under the oldge kit the settings fields, the segmented choice, *Browse…*, the empty state's
 * *Add a torrent* and the dialogs' frame are oldge-ui's components, and each still does what the
 * Material one does. B-182: each uses the library's own answer to what the kit had worked around.
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
     * The row draws the label on its left, so the field draws none above its well (B-182): the field
     * is the well's 32 dp and no taller. The label still names the field, and is heard once.
     */
    @Test
    fun theFieldDoesNotDrawItsLabelOverTheRow(): Unit =
        runComposeUiTest {
            setContent {
                KachokTheme(kit = OldgeKit.Default) {
                    Box(Modifier.testTag(FIELD)) {
                        KachokTheme.kit.SettingField(MAX_PEERS, "80", 80.dp, null, false, false) {}
                    }
                }
            }

            onNodeWithTag(FIELD).assertHeightIsEqualTo(WELL)
            numberField(MAX_PEERS).assertIsDisplayed()
            assertEquals(0, onAllNodesWithText(MAX_PEERS).fetchSemanticsNodes().size, "the label is the field's name")
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

            // Picked, never typed: the well is read-only (B-182). It takes focus, so its path can be
            // selected and copied, and it is not editable.
            folderWell(SAVE_TO)
                .performScrollTo()
                .performClick()
                .assertIsFocused()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, false))
        }

    /** A choice that cannot be changed is drawn and heard disabled, not only deaf to the press. */
    @Test
    fun aChoiceThatCannotChangeIsDisabled(): Unit =
        runComposeUiTest {
            var chosen = 0
            setContent {
                KachokTheme(kit = OldgeKit.Default) {
                    KachokTheme.kit.Segmented("Mode", listOf("One", "Two"), 0, false) { chosen++ }
                }
            }

            onNodeWithText("Two").assertIsNotEnabled().performClick()
            onNodeWithText("One").assertIsNotEnabled()
            assertEquals(0, chosen)
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
            folderWell(SAVE_TO).assertIsDisplayed()
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
            onNodeWithText(DELETE_DATA).performClick()

            assertEquals(listOf(true), toggled)
        }

    /**
     * *Remove and delete* is the library's *Danger* button, red, where the *Remove* that keeps the
     * files is the lime *Primary* (B-182). Read off the pixels: the variant has no semantics.
     */
    @Test
    fun removeThatDeletesTheDataIsTheDangerButton(): Unit =
        runComposeUiTest {
            var state by mutableStateOf(RemoveState(name = "Sintel.tar", where = "~/Downloads", howMuch = "2.89 GiB"))
            setContent { KachokTheme(kit = OldgeKit.Default) { RemoveTorrentDialog(state) } }

            assertTrue(redShare("Remove") < RED_SHARE, "the keep-the-files Remove is not red")
            state = state.withData(true)
            waitForIdle()
            assertTrue(redShare("Remove and delete") >= RED_SHARE, "the delete-the-data Remove is red")
        }

    /** A row's bar is the library's well, named by the row and carrying how far along it is. */
    @Test
    fun theRowBarIsNamedByTheRowAndCarriesItsProgress(): Unit =
        runComposeUiTest {
            setContent {
                KachokTheme(kit = OldgeKit.Default) {
                    KachokTheme.kit.ProgressBar(0.4f, "Sintel.tar", Color.Gray, Color.Red, Modifier.width(120.dp))
                }
            }

            onNodeWithContentDescription("Sintel.tar")
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ProgressBarRangeInfo,
                        ProgressBarRangeInfo(
                            0.4f,
                            0f..1f,
                        ),
                    ),
                )
        }

    /** The share of the [label]led button's pixels in which red leads green and blue by a margin. */
    private fun ComposeUiTest.redShare(label: String): Float {
        val pixels = onNodeWithContentDescription(label).captureToImage().toPixelMap()
        var red = 0
        for (x in 0 until pixels.width) {
            for (y in 0 until pixels.height) {
                val c = pixels[x, y]
                if (c.red - c.green > RED_LEAD && c.red - c.blue > RED_LEAD) red++
            }
        }
        return red.toFloat() / (pixels.width * pixels.height)
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

    /** A folder's well: a text field, editable or not, named by its label. */
    private fun ComposeUiTest.folderWell(label: String): SemanticsNodeInteraction =
        onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsEditable) and hasContentDescription(label))

    private companion object {
        const val MAX_PEERS = "Connections to keep up"
        const val SAVE_TO = "Save to"
        const val DIALOG = "dialog"
        val ADD_WIDTH = 520.dp
        val REMOVE_WIDTH = 420.dp
        const val FIELD = "field"
        val WELL = 32.dp
        const val RED_LEAD = 0.25f
        const val RED_SHARE = 0.3f
    }
}
