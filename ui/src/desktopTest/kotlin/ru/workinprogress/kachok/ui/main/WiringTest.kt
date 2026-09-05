package ru.workinprogress.kachok.ui.main

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import ru.workinprogress.kachok.ui.add.designTorrentToAdd
import ru.workinprogress.kachok.ui.details.DetailsTab
import ru.workinprogress.kachok.ui.session.Preferences
import ru.workinprogress.kachok.ui.session.settingsOf
import ru.workinprogress.kachok.ui.settings.SettingChange
import ru.workinprogress.kachok.ui.settings.SettingKey
import ru.workinprogress.kachok.ui.theme.KachokTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The press arrives, all the way out of the window.
 *
 * Three times in one day a control was drawn, given a callback, and the callback dropped one layer
 * up — the toolbar's `when` on labels with an `else`, then `SettingsScreen`'s `onChange` accepted by
 * `MainWindow` and never passed on. Each time the *screen's* own test passed, because each screen
 * did report its press; nothing asked whether anybody upstream was listening.
 *
 * An unused parameter is not a warning, so `-Werror` cannot see it either. This can: every callback
 * `MainWindow` takes is exercised through `MainWindow`, not through the screen underneath it.
 */
@OptIn(ExperimentalTestApi::class)
class WiringTest {
    private val window =
        MainWindowState(
            torrents = designTorrents,
            status = designStatus,
            details = designDetails(),
        )

    @Test
    fun theSettingsScreensChangesLeaveTheWindow(): Unit =
        runComposeUiTest {
            val changes = mutableListOf<SettingChange>()
            setContent {
                KachokTheme {
                    MainWindow(
                        MainWindowState(
                            torrents = window.torrents,
                            status = window.status,
                            settings = settingsOf(Preferences(directory = "/tmp/x")),
                        ),
                        onSetting = { changes += it },
                    )
                }
            }
            onNodeWithContentDescription("Start torrents when added").performClick()
            onNodeWithContentDescription("Browse").performClick()
            assertEquals(1, changes.filterIsInstance<SettingChange.Toggled>().size)
            assertEquals(1, changes.filterIsInstance<SettingChange.Browsed>().size)
        }

    @Test
    fun theAddDialogsBrowseLeavesTheWindow(): Unit =
        runComposeUiTest {
            var browsed = 0
            setContent {
                KachokTheme {
                    MainWindow(
                        MainWindowState(
                            torrents = window.torrents,
                            status = window.status,
                            adding = designTorrentToAdd,
                        ),
                        onBrowse = { browsed++ },
                    )
                }
            }
            onNodeWithText("Browse…").performClick()
            assertEquals(1, browsed)
        }

    @Test
    fun aRowsSelectionLeavesTheWindow(): Unit =
        runComposeUiTest {
            val selected = mutableListOf<Int>()
            setContent { KachokTheme { MainWindow(window, onSelect = { selected += it }) } }
            onNodeWithText("Sintel-2010-1080p-DCP.tar").performClick()
            assertTrue(selected.isNotEmpty(), "a click on a row reached nobody")
        }

    /**
     * Every column head, not a chosen one.
     *
     * A test that clicks RATIO proves RATIO is wired. The nine are nine separate `Head` calls with
     * nine separate `column` arguments, and a copy-paste that repeats one of them is the defect
     * this catches.
     */
    @Test
    fun everyColumnHeadLeavesTheWindow(): Unit =
        runComposeUiTest {
            val sorted = mutableListOf<SortColumn>()
            setContent { KachokTheme { MainWindow(window, onSort = { sorted += it }) } }
            listOf(
                "NAME",
                "SIZE",
                "PROGRESS",
                "DOWN KIB/S",
                "UP KIB/S",
                "PEERS · OUT",
                "RATIO",
                "ETA",
                "STATE",
            ).forEach { head -> onNodeWithContentDescription("column $head").performClick() }
            assertEquals(SortColumn.entries.size, sorted.size)
            assertEquals(SortColumn.entries.toSet(), sorted.toSet(), "a head reported another head's column")
        }

    /** All four tabs, for the same reason. */
    @Test
    fun everyDetailsTabLeavesTheWindow(): Unit =
        runComposeUiTest {
            val tabs = mutableListOf<DetailsTab>()
            setContent { KachokTheme { MainWindow(window, onTab = { tabs += it }) } }
            DetailsTab.entries.forEach { onNodeWithText(it.label).performClick() }
            assertEquals(DetailsTab.entries.toList(), tabs)
        }

    /** And every editable setting, addressed by the label its own row carries. */
    @Test
    fun everyEditableSettingLeavesTheWindow(): Unit =
        runComposeUiTest {
            val changes = mutableListOf<SettingChange>()
            setContent {
                KachokTheme {
                    MainWindow(
                        MainWindowState(
                            torrents = window.torrents,
                            status = window.status,
                            settings = settingsOf(Preferences(directory = "/tmp/x")),
                        ),
                        onSetting = { changes += it },
                    )
                }
            }
            val screen = settingsOf(Preferences(directory = "/tmp/x"))
            val editable = screen.all.filter { it.key.editable }
            editable.forEach { setting ->
                when {
                    setting.folder -> onNodeWithContentDescription("Browse").performClick()
                    setting.toggle != null -> onNodeWithContentDescription(setting.label).performClick()
                    else -> onNodeWithContentDescription(setting.label).performTextReplacement("7")
                }
            }
            assertEquals(
                editable.map { it.key }.toSet(),
                changes.map { it.key() }.toSet(),
                "a row was drawn editable and reported nothing",
            )
        }

    @Test
    fun everyEnabledToolbarControlLeavesTheWindow(): Unit =
        runComposeUiTest {
            val fired = mutableListOf<String>()
            setContent { KachokTheme { MainWindow(window, onAction = { fired += it.label }) } }
            onNodeWithText("Add torrent").performClick()
            assertEquals(listOf("Add torrent"), fired)
        }

    private fun SettingChange.key(): SettingKey =
        when (this) {
            is SettingChange.Browsed -> key
            is SettingChange.Toggled -> key
            is SettingChange.Typed -> key
        }
}
