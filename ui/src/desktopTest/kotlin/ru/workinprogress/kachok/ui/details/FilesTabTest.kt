package ru.workinprogress.kachok.ui.details

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import ru.workinprogress.kachok.ui.main.designDetails
import ru.workinprogress.kachok.ui.theme.KachokTheme
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

    /** A file this client is not fetching says `skip` rather than `0%`, which is what a stall says. */
    @Test
    fun anUnwantedFileSaysSkipRatherThanZero() =
        runComposeUiTest {
            setContent { KachokTheme { DetailsPanel(details) } }
            onNodeWithText("skip").assertIsDisplayed()
        }

    /**
     * The ticks are drawn and do nothing, and the tab says so.
     *
     * The badge is the difference between a control that is waiting and one that is broken. It goes
     * when the picker learns about unwanted files, and not before.
     */
    @Test
    fun theTabStillCarriesTheBadgeItsTicksHaveNotEarnedTheirWayOutOf() =
        runComposeUiTest {
            setContent { KachokTheme { DetailsPanel(details) } }
            assertEquals(
                1,
                onAllNodesWithText("planned").fetchSemanticsNodes().size,
                "the ticks respond to nothing and the tab no longer admits it",
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
