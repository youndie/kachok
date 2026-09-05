package ru.workinprogress.kachok.ui.main

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import ru.workinprogress.kachok.ui.add.designTorrentToAdd
import ru.workinprogress.kachok.ui.details.DetailsTab
import ru.workinprogress.kachok.ui.list.TorrentState
import ru.workinprogress.kachok.ui.remove.DELETE_DATA
import ru.workinprogress.kachok.ui.remove.RemoveState
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

    /**
     * The copy button, which was a glyph.
     *
     * And what it copies is the whole hash, not the ten characters the panel has room for — the
     * defect this catches is the easy one to write.
     */
    @Test
    fun theCopyButtonLeavesTheWindowWithTheWholeHash(): Unit =
        runComposeUiTest {
            val copied = mutableListOf<String>()
            setContent { KachokTheme { MainWindow(window, onCopy = { copied += it }) } }
            onNodeWithContentDescription("Copy Info hash").performClick()
            val hash = copied.single()
            assertEquals(HASH_HEX, hash.length, "the shortened one was copied")
            assertTrue(hash.all { it.isDigit() || it in 'a'..'f' }, hash)
        }

    /** *Show it* on the degraded banner, which took a callback nobody passed. */
    @Test
    fun showItLeavesTheWindow(): Unit =
        runComposeUiTest {
            var shown = 0
            setContent {
                KachokTheme {
                    MainWindow(
                        MainWindowState(
                            torrents = window.torrents,
                            status = window.status,
                            degradedSummary = "Sintel is degraded.",
                            degradedDetail = "java.net.SocketException: Network is unreachable",
                        ),
                        onShowDegraded = { shown++ },
                    )
                }
            }
            onNodeWithText("Show it").performClick()
            assertEquals(1, shown)
        }

    /** Typing in the filter, and clearing it. */
    @Test
    fun theFilterFieldLeavesTheWindow(): Unit =
        runComposeUiTest {
            val typed = mutableListOf<String>()
            setContent {
                KachokTheme {
                    MainWindow(
                        MainWindowState(
                            torrents = window.torrents,
                            status = window.status,
                            toolbar = ToolbarState(filter = "deb"),
                        ),
                        onFilter = { typed += it },
                    )
                }
            }
            onNodeWithContentDescription("Filter").performTextReplacement("sintel")
            onNodeWithContentDescription("Clear the filter").performClick()
            // Not an exact list: the field is given a value it never gets back — the test does not
            // feed the change into the state — so it re-syncs to "deb" and reports that too. What
            // matters is that both presses arrived.
            assertEquals("sintel", typed.first())
            assertEquals("", typed.last(), "the clear button reported nothing")
        }

    /** The remove dialog's three answers, through the window rather than through the dialog. */
    @Test
    fun theRemoveDialogsAnswersLeaveTheWindow(): Unit =
        runComposeUiTest {
            var cancelled = 0
            var removed = 0
            val toggles = mutableListOf<Boolean>()
            setContent {
                KachokTheme {
                    MainWindow(
                        MainWindowState(
                            torrents = window.torrents,
                            status = window.status,
                            removing =
                                RemoveState(
                                    name = "payload.bin",
                                    where = "/tmp/x",
                                    howMuch = "96.0 MiB on disk",
                                ),
                        ),
                        onCancelRemove = { cancelled++ },
                        onToggleRemoveData = { toggles += it },
                        onConfirmRemove = { removed++ },
                    )
                }
            }
            onNodeWithContentDescription(DELETE_DATA).performClick()
            onNodeWithContentDescription("Cancel").performClick()
            onNodeWithContentDescription("Remove").performClick()
            assertEquals(listOf(true), toggles)
            assertEquals(1, cancelled)
            assertEquals(1, removed)
        }

    /**
     * Every button on the bar that can be pressed, in every selection that enables it.
     *
     * Pressing one and calling the bar wired is what let four dead buttons ship. *Pause* and
     * *Resume* are enabled in different selections, so a single window can never exercise both —
     * hence the loop over selections, and the assertion that between them every command arrives.
     */
    @Test
    fun everyEnabledToolbarControlLeavesTheWindow() {
        val arrived = mutableSetOf<ToolbarCommand>()
        listOf(null, TorrentState.Downloading, TorrentState.Paused).forEach { selection ->
            val bar = ToolbarState().forSelection(selection)
            runComposeUiTest {
                setContent {
                    KachokTheme {
                        MainWindow(
                            MainWindowState(torrents = window.torrents, status = window.status, toolbar = bar),
                            onAction = { action -> action.command?.let { arrived += it } },
                        )
                    }
                }
                bar.all.filter { it.enabled }.forEach { action ->
                    onNodeWithContentDescription(action.label).performClick()
                }
            }
        }
        assertEquals(
            ToolbarCommand.entries.toSet(),
            arrived,
            "a control was drawn enabled and its press reached nobody",
        )
    }

    private companion object {
        /** Twenty bytes of SHA-1, in hex. */
        const val HASH_HEX = 40
    }

    private fun SettingChange.key(): SettingKey =
        when (this) {
            is SettingChange.Browsed -> key
            is SettingChange.Toggled -> key
            is SettingChange.Typed -> key
        }
}
