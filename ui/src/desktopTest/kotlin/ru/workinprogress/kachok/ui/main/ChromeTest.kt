package ru.workinprogress.kachok.ui.main

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import ru.workinprogress.kachok.ui.theme.KachokTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two bars a person reads without looking at them, and the screen a new install opens on.
 *
 * All three are drawn from strings somebody else computed, so a golden proves they were drawn and
 * nothing proves they were drawn *from the state*. What is asserted here is that each number
 * reaches the screen — a status bar showing the previous tick's port is a picture that matches
 * itself.
 */
@OptIn(ExperimentalTestApi::class)
class ChromeTest {
    private val everyHead =
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
        ).map { "column $it" }

    private val status =
        SessionStatus(
            down = "24 988 KiB/s",
            up = "812 KiB/s",
            torrents = "16 torrents, 7 seeding, 2 paused",
            dht = "DHT 214 nodes",
            port = "port 6881 listening",
            heap = "heap 41 / 128 MiB",
        )

    @Test
    fun theStatusBarShowsEveryFigureItWasGiven() =
        runComposeUiTest {
            setContent { KachokTheme { StatusBar(status) } }
            listOf(
                "24 988 KiB/s",
                "812 KiB/s",
                "16 torrents, 7 seeding, 2 paused",
                "DHT 214 nodes",
                "port 6881 listening",
                "heap 41 / 128 MiB",
            ).forEach { onNodeWithText(it, substring = true).assertIsDisplayed() }
        }

    /**
     * A client that never opened a DHT socket says *off*, not *0 nodes*.
     *
     * "No nodes answered" and "never asked" are different facts about the swarm, and the bar draws
     * them as different words — dimmed, because off is not a complaint.
     */
    @Test
    fun theDhtSaysOffRatherThanZeroWhenItWasNeverAskedFor() =
        runComposeUiTest {
            setContent { KachokTheme { StatusBar(SessionStatus("0 KiB/s", "0 KiB/s", "0 torrents", null, "p", "h")) } }
            onNodeWithText("DHT off").assertIsDisplayed()
            assertEquals(
                0,
                onAllNodesWithText("nodes", substring = true).fetchSemanticsNodes().size,
                "a client that never opened the socket counted nodes",
            )
        }

    @Test
    fun theDhtCountsNodesOnceItHasAsked() =
        runComposeUiTest {
            setContent {
                KachokTheme {
                    StatusBar(
                        SessionStatus("0 KiB/s", "0 KiB/s", "0 torrents", "DHT 0 nodes", "p", "h"),
                    )
                }
            }
            onNodeWithText("DHT 0 nodes").assertIsDisplayed()
        }

    /** The empty state says what to do, three ways, and the button is one of them. */
    @Test
    fun theEmptyStateOffersThreeWaysInAndTheButtonWorks() =
        runComposeUiTest {
            var added = 0
            setContent { KachokTheme { EmptyState(onAdd = { added++ }) } }
            onNodeWithText("Nothing downloading").assertIsDisplayed()
            onNodeWithText("Drop a", substring = true).assertIsDisplayed()
            onNodeWithText("Add a torrent").assertIsDisplayed()
            onNodeWithText("Add a torrent").performClick()
            assertEquals(1, added)
        }

    /**
     * And the two keystrokes it prints, which were a promise for a release.
     *
     * They work now ([B-72](../../../../../../../../docs/backlog/B-72-the-keyboard-map.md)). This
     * is here so that removing the hint and the handler cannot drift apart: text naming a shortcut
     * is the same kind of claim as a button drawn enabled.
     */
    @Test
    fun theEmptyStatePrintsTheTwoKeysThatWork() =
        runComposeUiTest {
            setContent { KachokTheme { EmptyState() } }
            onNodeWithText("⌘O", substring = true).assertIsDisplayed()
            onNodeWithText("⌘V", substring = true).assertIsDisplayed()
        }

    /** Every column head is on the screen and every one of them is a control. */
    @Test
    fun theHeaderDrawsNineHeadsAndEachOneSorts() =
        runComposeUiTest {
            val clicked = mutableListOf<SortColumn>()
            setContent { KachokTheme { ColumnHeader(SortOrder(), onSort = { clicked += it }) } }
            everyHead.forEach { onNodeWithContentDescription(it).performClick() }
            assertEquals(SortColumn.entries.toSet(), clicked.toSet())
            assertEquals(everyHead.size, clicked.size, "a head reported twice or not at all")
        }

    /**
     * The arrow is on the sorted column and nowhere else, and it points the way the order runs.
     *
     * Two arrows would be two claims about one list; the wrong arrow is worse than none, because it
     * says the list is in an order it is not. The direction lives in the node's *state* rather than
     * its name, so a lookup for "column RATIO" finds it whichever way the list runs.
     */
    @Test
    fun exactlyOneHeadCarriesTheOrderAndItIsTheSortedOne() =
        runComposeUiTest {
            setContent { KachokTheme { ColumnHeader(SortOrder(SortColumn.Ratio, ascending = false)) } }
            assertEquals("descending", stateOf("column RATIO"))
            assertEquals(null, stateOf("column NAME"), "an unsorted column claimed an order")
            assertEquals(
                listOf("column RATIO"),
                everyHead.filter { stateOf(it) != null },
                "more than one column claimed to be the sorted one",
            )
        }

    @Test
    fun theOrderFollowsTheSortRatherThanStayingPut() =
        runComposeUiTest {
            setContent { KachokTheme { ColumnHeader(SortOrder(SortColumn.Name, ascending = true)) } }
            assertEquals("ascending", stateOf("column NAME"))
        }

    /** What a head says about the order it is in, or null when it is not the sorted one. */
    private fun ComposeUiTest.stateOf(description: String): String? =
        onNodeWithContentDescription(description)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.StateDescription)
}
