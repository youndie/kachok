package ru.workinprogress.kachok.ui.session

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import ru.workinprogress.kachok.engine.metainfo.MetainfoParser
import ru.workinprogress.kachok.ui.Client
import ru.workinprogress.kachok.ui.theme.KachokTheme
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Clicking a row switches the panel **now**, not on the next sample.
 *
 * The window samples the engine once a second, and everything a *person* decides — which row is
 * selected, which tab is open, what is typed into the filter — is read in composition instead of
 * being folded into that sample. It was folded in once, and every click waited up to a second
 * ([B-64](../../../../../../../../docs/backlog/B-64-a-click-waited-for-the-tick.md)); reported again
 * on 2026-09-07, so this is the test that says which of the two it is.
 *
 * **The clock is stopped for the click.** With `autoAdvance` on, a delay of a second would be
 * indistinguishable from none: the test would simply wait. Frozen, the only thing that can move the
 * screen is the click itself.
 */
@OptIn(ExperimentalTestApi::class)
class SelectionTest {
    private val root: Path = Files.createTempDirectory("kachok-selection")
    private val list: Path get() = root.resolve("torrents")

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() = root.deleteRecursively()

    private fun remember(name: String) =
        MetainfoParser.parse(TestTorrents.bytes(name)).also {
            rememberTorrent(list, it, saveTo = root.toString())
        }

    /** A name on the screen twice is a row *and* the details panel's header; once is only the row. */
    private fun androidx.compose.ui.test.ComposeUiTest.times(name: String) =
        onAllNodesWithText(name).fetchSemanticsNodes().size

    @Test
    fun clickingARowSwitchesThePanelWithoutWaitingForTheNextSample() =
        runComposeUiTest {
            remember("alpha.bin")
            remember("beta.bin")
            setContent {
                KachokTheme {
                    Client(
                        initial = null,
                        directory = root,
                        settingsFile = root.resolve("settings.properties"),
                        torrents = list,
                    )
                }
            }
            waitUntil(timeoutMillis = 60_000) { times("alpha.bin") > 0 && times("beta.bin") > 0 }
            // Whichever came first is selected; the other one is the one to click.
            val selectedFirst = if (times("alpha.bin") == 2) "alpha.bin" else "beta.bin"
            val other = if (selectedFirst == "alpha.bin") "beta.bin" else "alpha.bin"
            assertEquals(2, times(selectedFirst), "nothing was selected to begin with")

            mainClock.autoAdvance = false
            onAllNodesWithText(other)[0].performClick()
            // Two frames, which is what a click costs: the press and the recomposition it causes.
            // Nowhere near the second a sample takes.
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()

            assertEquals(2, times(other), "the panel did not follow the click within two frames")
            assertEquals(1, times(selectedFirst), "the old selection is still in the panel")
        }
}
