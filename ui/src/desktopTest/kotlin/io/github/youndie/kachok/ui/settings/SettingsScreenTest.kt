package io.github.youndie.kachok.ui.settings

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.youndie.kachok.ui.session.Preferences
import io.github.youndie.kachok.ui.session.settingsOf
import io.github.youndie.kachok.ui.theme.KachokTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The invariant [B-56](../../../../../../../../docs/backlog/B-56-dead-toolbar-controls.md) wrote
 * for the toolbar, applied where it should have been in the first place.
 *
 * B-56 fixed one bar and left two screens with the same disease: the settings screen had **no**
 * interactive element at all, and the add dialog's *Browse…* was a bordered box. The person who
 * found that had the application open for about a minute. A guard over one bar is not a guard over
 * a class of defect, which is what it claimed to be.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {
    private val preferences =
        Preferences(directory = io.github.youndie.kachok.ui.session.DEFAULT_DIRECTORY, port = 6881)

    /** Every key either takes an edit here or says, on the row, why it does not. */
    @Test
    fun everySettingIsEitherEditableOrSaysWhyNot() {
        SettingKey.entries.forEach { key ->
            assertTrue(
                key.editable != (key.disabledBecause != null),
                "$key: editable=${key.editable} because=${key.disabledBecause}",
            )
            key.disabledBecause?.let { assertTrue(it.length > SHORT, "$key: $it") }
        }
        assertEquals(
            listOf(SettingKey.ListeningPort),
            SettingKey.entries.filter { !it.editable },
            "the port is bound once; everything else, the DHT included, can be changed here",
        )
    }

    /** A row that cannot be edited says so where a person reads the row, not in a release note. */
    @Test
    fun aSettingThatCannotBeChangedSaysSoOnItsOwnRow() =
        runComposeUiTest {
            setContent { KachokTheme { SettingsScreen(settingsOf(preferences)) } }
            // One row cannot be changed here, and it says so on itself.
            assertEquals(
                1,
                onAllNodesWithText("Not changeable here", substring = true).fetchSemanticsNodes().size,
            )
        }

    @Test
    fun theToggleReportsTheValueItWouldBecome() =
        runComposeUiTest {
            val changes = mutableListOf<SettingChange>()
            setContent { KachokTheme { SettingsScreen(settingsOf(preferences)) { changes += it } } }
            onNodeWithContentDescription("Start torrents when added").performClick()
            val toggled = changes.filterIsInstance<SettingChange.Toggled>().single()
            assertEquals(SettingKey.StartWhenAdded, toggled.key)
            assertEquals(false, toggled.on, "it is on, so a click asks for off")
        }

    /** Closing to the tray is the difference between a client that keeps working and one that stops. */
    @Test
    fun closingToTheTrayCanBeTurnedOffFromHere() =
        runComposeUiTest {
            val changes = mutableListOf<SettingChange>()
            setContent { KachokTheme { SettingsScreen(settingsOf(preferences)) { changes += it } } }
            onNodeWithContentDescription("Close to the tray").performClick()
            val toggled = changes.filterIsInstance<SettingChange.Toggled>().single()
            assertEquals(SettingKey.CloseToTray, toggled.key)
            assertEquals(false, toggled.on, "it is on by default, so a click asks for off")
        }

    /**
     * A desktop with no tray says so on the row, and the toggle is not drawn on.
     *
     * GNOME dropped the status-icon protocol. A checkbox promising that closing leaves the client
     * running, on a desktop where closing stops it, is the worst of the three states this can be in
     * ([B-88](../../../../../../../../docs/backlog/B-88-closing-to-a-tray.md)).
     */
    @Test
    fun withNoTrayTheRowSaysSoAndDoesNotClaimToBeOn() =
        runComposeUiTest {
            setContent {
                KachokTheme {
                    SettingsScreen(
                        settingsOf(
                            preferences,
                            trayProblem = "This desktop has no tray, so the close button stops the torrents.",
                        ),
                    )
                }
            }
            assertEquals(
                1,
                onAllNodesWithText("no tray", substring = true).fetchSemanticsNodes().size,
                "the row does not say the tray is missing",
            )
            assertEquals(
                "off",
                onNodeWithContentDescription("Close to the tray")
                    .fetchSemanticsNode()
                    .config
                    .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.StateDescription),
                "the toggle claimed to be on with no tray to close to",
            )
        }

    /** Starting with the computer is a decision a person takes, and taking it writes a file. */
    @Test
    fun startingWithTheComputerCanBeAskedForFromHere() =
        runComposeUiTest {
            val changes = mutableListOf<SettingChange>()
            setContent { KachokTheme { SettingsScreen(settingsOf(preferences)) { changes += it } } }
            onNodeWithContentDescription("Start with the computer").performClick()
            val toggled = changes.filterIsInstance<SettingChange.Toggled>().single()
            assertEquals(SettingKey.Autostart, toggled.key)
            assertEquals(true, toggled.on, "it is off, so a click asks for on")
        }

    /**
     * A build that cannot start with the computer says so on the row, not in the footnote.
     *
     * The footnote is about the screen; this is about one checkbox that has just refused to stay
     * pressed, and a person reading the row is the person who pressed it
     * ([B-83](../../../../../../../../docs/backlog/B-83-autostart-and-its-setting.md)).
     */
    @Test
    fun aRefusalToStartWithTheComputerIsOnTheRowItBelongsTo() =
        runComposeUiTest {
            setContent {
                KachokTheme {
                    SettingsScreen(settingsOf(preferences, autostartProblem = "kachok is not installed here."))
                }
            }
            assertEquals(
                1,
                onAllNodesWithText("kachok is not installed here.").fetchSemanticsNodes().size,
                "the refusal is not on the row",
            )
            assertEquals(
                0,
                onAllNodesWithText("Starts minimised", substring = true).fetchSemanticsNodes().size,
                "the usual note was drawn beside a refusal",
            )
        }

    /** Joining the DHT is a decision a person takes, and taking it opens the socket. */
    @Test
    fun theDhtCanBeJoinedFromHere() =
        runComposeUiTest {
            val changes = mutableListOf<SettingChange>()
            setContent { KachokTheme { SettingsScreen(settingsOf(preferences)) { changes += it } } }
            onNodeWithContentDescription("Join the DHT (BEP 5)").performClick()
            val toggled = changes.filterIsInstance<SettingChange.Toggled>().single()
            assertEquals(SettingKey.Dht, toggled.key)
            assertEquals(true, toggled.on, "it is off, so a click asks for on")
        }

    @Test
    fun browsingAsksForADirectoryRatherThanDoingNothing() =
        runComposeUiTest {
            val changes = mutableListOf<SettingChange>()
            setContent { KachokTheme { SettingsScreen(settingsOf(preferences)) { changes += it } } }
            onNodeWithContentDescription("Browse").performClick()
            assertEquals(
                SettingKey.SaveTo,
                changes.filterIsInstance<SettingChange.Browsed>().single().key,
            )
        }

    @Test
    fun aNumberCanBeTypedInto() =
        runComposeUiTest {
            val changes = mutableListOf<SettingChange>()
            setContent { KachokTheme { SettingsScreen(settingsOf(preferences)) { changes += it } } }
            onNodeWithText("50").performTextReplacement("80")
            val typed = changes.filterIsInstance<SettingChange.Typed>().single()
            assertEquals(SettingKey.MaxPeers, typed.key)
            assertEquals("80", typed.text)
        }

    /** And what is typed reaches the next torrent, which is the only place it can reach today. */
    @Test
    fun whatIsTypedReachesTheNextTorrentsOptions() {
        val changed =
            preferences
                .typed(SettingKey.MaxPeers, "80")
                .typed(SettingKey.PipelineDepth, "24")
                .typed(SettingKey.DownloadLimit, "12000")
        val options = changed.runtimeOptions()
        assertEquals(80, options.maxPeers)
        assertEquals(24, options.pipelineDepth)
        assertEquals(12_000L * 1024, options.downloadLimitBytesPerSecond, "KiB/s on screen, bytes to the engine")
        assertEquals(0L, options.uploadLimitBytesPerSecond, "and nothing typed is still no limit")
    }

    /** A half-cleared field is not a zero. */
    @Test
    fun clearingAFieldKeepsTheDefaultRatherThanMeaningZero() {
        val cleared = preferences.typed(SettingKey.MaxPeers, "")
        assertEquals(null, cleared.maxPeers)
        assertEquals(50, cleared.runtimeOptions().maxPeers, "the engine's own default, not zero")
    }

    private companion object {
        const val SHORT = 12
    }
}
