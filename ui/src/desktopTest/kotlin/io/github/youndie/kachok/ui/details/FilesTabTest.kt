package io.github.youndie.kachok.ui.details

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.youndie.kachok.ui.main.designDetails
import io.github.youndie.kachok.ui.theme.KachokTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The *Files* tab, and the one claim it must not make.
 *
 * The percentage beside a file is of *that file*. Counting the pieces that touch it makes a
 * 700-byte file complete the moment its neighbour's piece lands, and every row would then read
 * 100% while the torrent was a third done — a lie a golden cannot catch, because a picture full of
 * hundreds matches itself.
 */
@OptIn(ExperimentalTestApi::class)
class FilesTabTest {
    private val details = designDetails(DetailsTab.Files)

    @Test
    fun theTabDrawsARowPerFileWithItsSizeAndShare() =
        runComposeUiTest {
            setContent { KachokTheme { DetailsPanel(details) } }
            onNodeWithText("SHA512SUMS.sign").assertIsDisplayed()
            onNodeWithText("833 B").assertIsDisplayed()
            onNodeWithText("79%").assertIsDisplayed()
        }

    /** The summary counts the files and the ones this client wants. */
    @Test
    fun theSummaryLineCountsTheFilesAndTheWantedOnes() =
        runComposeUiTest {
            setContent { KachokTheme { DetailsPanel(details) } }
            onNodeWithText("9 files · 3.70 GiB · 8 wanted").assertIsDisplayed()
        }

    /**
     * The gesture reaches the handler, which is the half no unit test of `openFile` can see.
     *
     * `openFile` decides what a double-click means and is asserted branch by branch in
     * `OpenFileTest`; what this asserts is that a row is a control at all. A `combinedClickable`
     * that was attached to the wrong element, or a callback the panel forgot to pass down, is a
     * tab where nothing happens and every test still passes
     * ([B-85](../../../../../../../../docs/backlog/B-85-open-a-file-from-the-files-tab.md)).
     */
    @Test
    fun doubleClickingAFileAsksTheCallerToOpenIt() =
        runComposeUiTest {
            val asked = mutableListOf<String>()
            setContent {
                KachokTheme {
                    DetailsPanel(details, onOpenFile = {
                        asked += it.name
                        null
                    })
                }
            }
            onNodeWithContentDescription("file SHA512SUMS.sign").performTouchInput { doubleClick() }
            assertEquals(listOf("SHA512SUMS.sign"), asked)
        }

    /**
     * A single click is not the gesture. Reading the list must not launch a video player.
     */
    @Test
    fun aSingleClickOpensNothing() =
        runComposeUiTest {
            var asked = 0
            setContent {
                KachokTheme {
                    DetailsPanel(details, onOpenFile = {
                        asked++
                        null
                    })
                }
            }
            onNodeWithContentDescription("file SHA512SUMS.sign").performClick()
            assertEquals(0, asked)
        }

    /** What the handler answers is drawn where the person is looking, not swallowed. */
    @Test
    fun theRefusalIsShownUnderTheList() =
        runComposeUiTest {
            setContent {
                KachokTheme {
                    DetailsPanel(
                        details,
                        onOpenFile = { "${it.name} is 79% — opening it would hand a truncated file over." },
                    )
                }
            }
            assertEquals(0, onAllNodesWithText("truncated", substring = true).fetchSemanticsNodes().size)
            onNodeWithContentDescription("file SHA512SUMS.sign").performTouchInput { doubleClick() }
            onNodeWithText("truncated", substring = true).assertIsDisplayed()
        }

    /**
     * The order is a control here, and the file ticks are not — which is the whole difference.
     *
     * Somebody asks for sequential order because they have started watching, and they start
     * watching after the download has started; before this the only place to say so was the add
     * dialog, so the answer was to remove the torrent and add it again
     * ([B-89](../../../../../../../../docs/backlog/B-89-sequential-on-a-running-torrent.md)).
     */
    @Test
    fun theOrderCanBeChangedFromTheTab() =
        runComposeUiTest {
            val asked = mutableListOf<Boolean>()
            setContent { KachokTheme { DetailsPanel(details, onSequential = { asked += it }) } }
            onNodeWithContentDescription("piece order").performClick()
            assertEquals(listOf(true), asked, "the tab did not ask for the order it does not have")
        }

    /** And it reports what the session is doing, not what was last pressed. */
    @Test
    fun theOrderShowsWhatTheSessionSaysRatherThanTheLastClick() =
        runComposeUiTest {
            setContent { KachokTheme { DetailsPanel(details) } }
            assertEquals("rarest first", stateOfOrder())
        }

    @Test
    fun aTorrentAlreadyInOrderSaysSoAndAsksToBeTurnedOff() =
        runComposeUiTest {
            val asked = mutableListOf<Boolean>()
            setContent {
                KachokTheme {
                    DetailsPanel(designDetails(DetailsTab.Files, sequential = true), onSequential = { asked += it })
                }
            }
            assertEquals("in order", stateOfOrder())
            onNodeWithContentDescription("piece order").performClick()
            assertEquals(listOf(false), asked, "a torrent already in order could not be switched back")
        }

    private fun ComposeUiTest.stateOfOrder(): String? =
        onNodeWithContentDescription("piece order")
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.StateDescription)

    /** A file this client is not fetching says `skip` rather than `0%`, which is what a stall says. */
    @Test
    fun anUnwantedFileSaysSkipRatherThanZero() =
        runComposeUiTest {
            setContent { KachokTheme { DetailsPanel(details) } }
            onNodeWithText("skip").assertIsDisplayed()
        }

    /**
     * The badge is gone, because the ticks are live.
     *
     * They are live in the *add dialog*; the ones here are indicators of what that dialog decided.
     * Changing the selection on a running torrent needs the picker to give back pieces it has
     * started, which the item leaves uncovered — and an indicator is not a control, so it wears no
     * badge either way.
     */
    @Test
    fun theTabNoLongerSaysItIsWaitingForTheEngine() =
        runComposeUiTest {
            setContent { KachokTheme { DetailsPanel(details) } }
            assertEquals(
                0,
                onAllNodesWithText("planned").fetchSemanticsNodes().size,
                "the ticks work now and the tab still says they do not",
            )
        }

    /** A magnet has no file list yet, and says which of the two empties that is. */
    @Test
    fun aTorrentWithNoMetainfoYetSaysSo() =
        runComposeUiTest {
            setContent {
                KachokTheme {
                    DetailsPanel(
                        DetailsState(
                            name = details.name,
                            state = details.state,
                            stateLabel = details.stateLabel,
                            summary = details.summary,
                            sections = emptyList(),
                            complaints = emptyList(),
                            sessionError = null,
                            tab = DetailsTab.Files,
                            files = emptyList(),
                            filesSummary = "No files",
                        ),
                    )
                }
            }
            onNodeWithText("No files yet — the metainfo has not arrived.").assertIsDisplayed()
        }
}
