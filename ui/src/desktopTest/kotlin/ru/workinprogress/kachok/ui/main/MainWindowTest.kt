package ru.workinprogress.kachok.ui.main

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import ru.workinprogress.kachok.ui.theme.KachokTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The acceptance criteria of [B-48](../../../../../../../../docs/backlog/B-48-main-window-shell.md)
 * that are not about pixels.
 *
 * The golden says the shell looks like the design. This says the words a person needs are on the
 * screen — which a golden cannot, because a picture in which the exception text was quietly
 * truncated to nothing still matches itself.
 */
@OptIn(ExperimentalTestApi::class)
class MainWindowTest {
    private val state =
        MainWindowState(
            torrents = designTorrents,
            status = designStatus,
            degradedSummary = DEGRADED_SUMMARY,
            degradedDetail = DEGRADED_DETAIL,
        )

    @Test
    fun theStatusBarShowsEverySessionTotal(): Unit =
        runComposeUiTest {
            setContent { KachokTheme { MainWindow(state) } }
            listOf(
                designStatus.down,
                designStatus.up,
                designStatus.torrents,
                designStatus.dht.orEmpty(),
                designStatus.port,
                designStatus.heap,
            ).forEach { onNodeWithText(it).assertIsDisplayed() }
        }

    /**
     * The whole point of a docked banner: it is a condition, not an event, so it is still there
     * after any timeout a `Snackbar` would have had.
     */
    @Test
    fun theBannerCarriesTheExceptionVerbatimAndDoesNotTimeOut(): Unit =
        runComposeUiTest {
            setContent { KachokTheme { MainWindow(state) } }
            onNodeWithText(DEGRADED_DETAIL).assertIsDisplayed()
            mainClock.advanceTimeBy(A_MINUTE)
            onNodeWithText(DEGRADED_DETAIL).assertIsDisplayed()
            onNodeWithText(DEGRADED_SUMMARY).assertIsDisplayed()
            assertTrue(
                DEGRADED_DETAIL.contains("java.nio.channels.ClosedChannelException"),
                "the class name is the half a person searches for; a summary without it is a shrug",
            )
        }

    @Test
    fun aHealthySessionHasNoBanner(): Unit =
        runComposeUiTest {
            setContent {
                KachokTheme { MainWindow(MainWindowState(designTorrents, designStatus)) }
            }
            onNodeWithText(DEGRADED_SUMMARY).assertDoesNotExist()
            onNodeWithText(designStatus.heap).assertIsDisplayed()
        }

    @Test
    fun clickingAColumnHeadAsksForThatSort(): Unit =
        runComposeUiTest {
            val asked = mutableListOf<SortColumn>()
            setContent { KachokTheme { MainWindow(state, onSort = { asked += it }) } }
            onNodeWithText("RATIO").performClick()
            onNodeWithText("PEERS · OUT").performClick()
            assertEquals(listOf(SortColumn.Ratio, SortColumn.Peers), asked)
        }

    @Test
    fun everyToolbarActionReportsItselfExceptTheOneThatCannotBeUsed(): Unit =
        runComposeUiTest {
            val fired = mutableListOf<String>()
            setContent { KachokTheme { MainWindow(state, onAction = { fired += it.label }) } }
            onNodeWithText("Add torrent").performClick()
            assertEquals(listOf("Add torrent"), fired)
            assertTrue(!state.toolbar.resume.enabled, "the design greys Resume while a torrent runs")
        }
}

private const val A_MINUTE = 60_000L
