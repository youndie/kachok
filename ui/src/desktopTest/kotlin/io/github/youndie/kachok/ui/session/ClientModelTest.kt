package io.github.youndie.kachok.ui.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.youndie.kachok.ui.Client
import io.github.youndie.kachok.ui.details.DetailsTab
import io.github.youndie.kachok.ui.main.SortColumn
import io.github.youndie.kachok.ui.main.SortOrder
import io.github.youndie.kachok.ui.theme.KachokTheme
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The window is thrown away and rebuilt, and the client is still there.
 *
 * **This is the claim [B-79](../../../../../../../../docs/backlog/B-79-the-windows-state-outlives-its-composition.md)
 * exists for**, and until now nothing in this module could make it: every value the window held was
 * a `remember` in its own composition and the engine was a `LaunchedEffect` in it, so "the
 * composition goes away" and "the client goes away" were the same sentence. On this desktop nothing
 * throws a window away — `visible = false` keeps the composition — which is exactly why the claim
 * needs a test rather than a platform.
 *
 * No swarm: the trackers are `.invalid`, so nothing leaves the machine. What is being asserted is
 * which object is still there afterwards, not what it downloaded.
 */
@OptIn(ExperimentalTestApi::class)
class ClientModelTest {
    private val root: Path = Files.createTempDirectory("kachok-holder")

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() {
        model.close()
        root.deleteRecursively()
    }

    private val model = ClientModel(Preferences(directory = root.toString()))

    @Test
    fun whatThePersonDecidedOutlivesTheWindow() =
        runComposeUiTest {
            var windowThere by mutableStateOf(true)
            setContent {
                KachokTheme {
                    if (windowThere) {
                        Client(
                            initial = null,
                            directory = root,
                            settingsFile = root.resolve("settings.properties"),
                            torrents = root.resolve("torrents"),
                            model = model,
                        )
                    }
                }
            }
            waitForIdle()

            // The five the item names, as a person would have left them.
            model.selected.value = "1a2b3c"
            model.panelOpen.value = false
            model.filter.value = "debian"
            model.sort.value = SortOrder(column = SortColumn.Size, ascending = false)
            model.tab.value = DetailsTab.Files
            waitForIdle()

            // The window goes, and comes back — which on Android is a rotation and here is the only
            // honest way to ask the question on a platform that never does it by itself.
            windowThere = false
            waitForIdle()
            windowThere = true
            waitForIdle()

            assertEquals("1a2b3c", model.selected.value, "the selected torrent was forgotten")
            assertEquals(false, model.panelOpen.value, "the panel reopened itself")
            assertEquals("debian", model.filter.value, "the filter was cleared")
            assertEquals(SortColumn.Size, model.sort.value.column, "the sort went back to its default")
            assertEquals(DetailsTab.Files, model.tab.value, "the open tab was forgotten")
        }

    /** And the engine with it: the same `TorrentSet`, not a second one built by the new window. */
    @Test
    fun theEngineOutlivesTheWindowAndIsNotRebuilt() =
        runComposeUiTest {
            var windowThere by mutableStateOf(true)
            setContent {
                KachokTheme {
                    if (windowThere) {
                        Client(
                            initial = null,
                            directory = root,
                            settingsFile = root.resolve("settings.properties"),
                            torrents = root.resolve("torrents"),
                            model = model,
                        )
                    }
                }
            }
            waitForIdle()
            waitUntil(timeoutMillis = TIMEOUT) { model.set != null }
            val before = assertNotNull(model.set, "the holder never started an engine")

            windowThere = false
            waitForIdle()
            assertSame(before, model.set, "the engine was closed when the window went away")

            windowThere = true
            waitForIdle()
            assertSame(before, model.set, "the new window built a second engine")
            assertTrue(model.set?.torrents?.isEmpty() == true, "the set came back with torrents nobody added")
        }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
