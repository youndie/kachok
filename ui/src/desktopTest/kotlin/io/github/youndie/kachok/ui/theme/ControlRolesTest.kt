package io.github.youndie.kachok.ui.theme

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.youndie.kachok.ui.add.AddTorrentDialog
import io.github.youndie.kachok.ui.add.AddTorrentState
import io.github.youndie.kachok.ui.add.ClipboardMagnetPrompt
import io.github.youndie.kachok.ui.add.designTorrentToAdd
import io.github.youndie.kachok.ui.details.DetailsPanel
import io.github.youndie.kachok.ui.details.DetailsTab
import io.github.youndie.kachok.ui.list.TorrentState
import io.github.youndie.kachok.ui.main.ColumnHeader
import io.github.youndie.kachok.ui.main.DegradedBanner
import io.github.youndie.kachok.ui.main.EmptyState
import io.github.youndie.kachok.ui.main.NoMatches
import io.github.youndie.kachok.ui.main.SortColumn
import io.github.youndie.kachok.ui.main.SortOrder
import io.github.youndie.kachok.ui.main.Toolbar
import io.github.youndie.kachok.ui.main.ToolbarState
import io.github.youndie.kachok.ui.main.designDetails
import io.github.youndie.kachok.ui.remove.DELETE_DATA
import io.github.youndie.kachok.ui.remove.RemoveState
import io.github.youndie.kachok.ui.remove.RemoveTorrentDialog
import io.github.youndie.kachok.ui.session.Preferences
import io.github.youndie.kachok.ui.session.settingsOf
import io.github.youndie.kachok.ui.settings.SettingsScreen
import io.github.youndie.kachok.ui.theme.material.MaterialKit
import io.github.youndie.kachok.ui.theme.oldge.OldgeKit
import kotlin.test.Test

/**
 * B-180: every control a screen reader should reach says what it is, in both kits.
 *
 * A name is enough for this test tree to find a control, which is why every other test found the
 * Material toolbar; it is not enough for the platform, which needs the *role* to list a button as a
 * button. Read through macOS accessibility, the oldge toolbar was nine `AXButton`s and the Material
 * one was none. Each test here runs once per kit, so a control one kit announces and the other does
 * not fails by name.
 */
@OptIn(ExperimentalTestApi::class)
class ControlRolesTest {
    @Test
    fun everyToolbarControlIsAButtonOrAToggle() {
        KITS.forEach { kit ->
            // Both widths: the narrow bar draws *Add torrent* as an icon button under the oldge kit.
            listOf(false, true).forEach { narrow ->
                runComposeUiTest {
                    // A filter, so the clear button is there; a running torrent, so *Resume* is
                    // greyed — a disabled button is still a button.
                    val bar = ToolbarState(filter = "deb").forSelection(TorrentState.Downloading)
                    setContent {
                        KachokTheme(kit = kit.kit) {
                            Toolbar(bar, if (narrow) Modifier.width(NARROW) else Modifier, narrow = narrow)
                        }
                    }

                    // The panel toggles are checkboxes, on or off (B-182); every other control a button.
                    bar.all.forEach { action ->
                        when (val on = action.active) {
                            null -> button(kit, action.label)
                            else -> checkbox(kit, action.label, ToggleableState(on))
                        }
                    }
                    button(kit, kit.clearFilter)
                }
            }
        }
    }

    @Test
    fun theBannersShowItIsAButton() {
        KITS.forEach { kit ->
            runComposeUiTest {
                setContent {
                    KachokTheme(kit = kit.kit) { DegradedBanner("One session is degraded.", "writer loop failed") }
                }
                onNodeWithText("Show it").assertRole(kit, Role.Button)
            }
        }
    }

    /** Tabs, and the current one is selected: the underline is not something anybody can read. */
    @Test
    fun theDetailsTabsAreTabsAndTheCurrentOneIsSelected() {
        KITS.forEach { kit ->
            runComposeUiTest {
                setContent { KachokTheme(kit = kit.kit) { DetailsPanel(designDetails(DetailsTab.Peers)) } }

                DetailsTab.entries.forEach { tab ->
                    val node = onNodeWithText(tab.label).assertRole(kit, Role.Tab)
                    if (tab == DetailsTab.Peers) node.assertIsSelected() else node.assertIsNotSelected()
                }
            }
        }
    }

    /** The panel's own pressable things, drawn by the screen in both kits. */
    @Test
    fun theDetailsPanelsControlsAreButtons() {
        KITS.forEach { kit ->
            runComposeUiTest {
                setContent { KachokTheme(kit = kit.kit) { DetailsPanel(designDetails(), onMoveData = {}) } }
                button(kit, "Copy Info hash")
                button(kit, "Move Save to")
            }
            runComposeUiTest {
                setContent { KachokTheme(kit = kit.kit) { DetailsPanel(designDetails(DetailsTab.Files)) } }
                button(kit, "piece order")
                button(kit, "priority of SHA512SUMS.sign")
            }
            runComposeUiTest {
                setContent { KachokTheme(kit = kit.kit) { DetailsPanel(designDetails(DetailsTab.Trackers)) } }
                button(kit, "Re-announce")
            }
        }
    }

    @Test
    fun theSettingsSwitchIsASwitchAndBrowseIsAButton() {
        KITS.forEach { kit ->
            runComposeUiTest {
                setContent { KachokTheme(kit = kit.kit) { SettingsScreen(settingsOf(PREFERENCES)) } }

                onNodeWithContentDescription(START_WHEN_ADDED)
                    .assertRole(kit, Role.Switch)
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
                button(kit, "Browse")
                InterfaceKit.entries.forEach { onNodeWithText(it.label).assertRole(kit, Role.RadioButton) }
            }
        }
    }

    @Test
    fun theEmptyStatesInvitationsAreButtons() {
        KITS.forEach { kit ->
            runComposeUiTest {
                setContent { KachokTheme(kit = kit.kit) { EmptyState() } }
                onNodeWithText("Add a torrent").assertRole(kit, Role.Button)
            }
            runComposeUiTest {
                setContent { KachokTheme(kit = kit.kit) { NoMatches("deb", hidden = 3) } }
                onNodeWithText("Clear the filter").assertRole(kit, Role.Button)
            }
        }
    }

    @Test
    fun theColumnHeadsAreButtons() {
        KITS.forEach { kit ->
            runComposeUiTest {
                setContent { KachokTheme(kit = kit.kit) { ColumnHeader(SortOrder()) } }
                onAllNodesWithContentDescription("column ", substring = true)
                    .assertCountEquals(SortColumn.entries.size)
                    .assertAll(roleIs(Role.Button))
            }
        }
    }

    @Test
    fun theAddDialogsControlsSayWhatTheyAre() {
        KITS.forEach { kit ->
            runComposeUiTest {
                setContent { KachokTheme(kit = kit.kit) { AddTorrentDialog(designTorrentToAdd) } }

                button(kit, "Cancel")
                button(kit, "Add")
                onNodeWithText("Browse…").assertRole(kit, Role.Button)
                checkbox(kit, AddTorrentState.SEQUENTIAL, ToggleableState.Off)
                designTorrentToAdd.files.forEach {
                    checkbox(kit, it.name, if (it.wanted) ToggleableState.On else ToggleableState.Off)
                }
            }
            runComposeUiTest {
                setContent { KachokTheme(kit = kit.kit) { ClipboardMagnetPrompt("magnet:?xt=urn:btih:e4f2") } }
                onNodeWithText("Add it").assertRole(kit, Role.Button)
                button(kit, "Dismiss")
            }
        }
    }

    @Test
    fun theRemoveDialogsControlsSayWhatTheyAre() {
        KITS.forEach { kit ->
            runComposeUiTest {
                setContent {
                    KachokTheme(kit = kit.kit) {
                        RemoveTorrentDialog(
                            RemoveState(name = "Sintel.tar", where = "~/Downloads", howMuch = "2.89 GiB on disk"),
                        )
                    }
                }

                button(kit, "Cancel")
                button(kit, "Remove")
                checkbox(kit, DELETE_DATA, ToggleableState.Off)
            }
        }
    }

    private fun ComposeUiTest.button(
        kit: Kit,
        name: String,
    ): SemanticsNodeInteraction = onNodeWithContentDescription(name).assertRole(kit, Role.Button)

    private fun ComposeUiTest.checkbox(
        kit: Kit,
        name: String,
        state: ToggleableState,
    ) {
        // Named by a description (the Material rows) or by the label it shows (oldge-ui's own box).
        onNode((hasContentDescription(name) or hasText(name)) and isToggleable())
            .assertRole(kit, Role.Checkbox)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state))
    }

    private fun SemanticsNodeInteraction.assertRole(
        kit: Kit,
        role: Role,
    ): SemanticsNodeInteraction = assert(roleIs(role)) { "under the ${kit.name} kit" }

    private fun roleIs(role: Role): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    /** A kit, and the name it gives the filter's clear button: oldge-ui's is set through its strings. */
    private class Kit(
        val name: String,
        val kit: KachokKit,
        val clearFilter: String,
    )

    private companion object {
        val KITS =
            listOf(
                Kit("Material", MaterialKit.Default, "Clear the filter"),
                Kit("oldge", OldgeKit.Default, "Clear the filter"),
            )
        val NARROW = 600.dp
        val PREFERENCES = Preferences(directory = "~/Downloads")
        const val START_WHEN_ADDED = "Start torrents when added"
    }
}
