package ru.workinprogress.kachok.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import ru.workinprogress.kachok.ui.session.Preferences
import ru.workinprogress.kachok.ui.session.settingsOf
import ru.workinprogress.kachok.ui.theme.KachokTheme
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
        Preferences(directory = ru.workinprogress.kachok.ui.session.DEFAULT_DIRECTORY, port = 6881)

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
            listOf(SettingKey.ListeningPort, SettingKey.Dht),
            SettingKey.entries.filter { !it.editable },
            "these two are built once, when the process starts",
        )
    }

    /** A row that cannot be edited says so where a person reads the row, not in a release note. */
    @Test
    fun aSettingThatCannotBeChangedSaysSoOnItsOwnRow() =
        runComposeUiTest {
            setContent { KachokTheme { SettingsScreen(settingsOf(preferences)) } }
            // Two rows cannot be changed here, and both say so on themselves.
            assertEquals(
                2,
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
