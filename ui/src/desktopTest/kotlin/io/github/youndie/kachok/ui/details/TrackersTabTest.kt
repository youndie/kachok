package io.github.youndie.kachok.ui.details

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.youndie.kachok.ui.main.designDetails
import io.github.youndie.kachok.ui.theme.KachokTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The *Trackers* tab, and the sentence a golden cannot check.
 *
 * A tracker's refusal is somebody else's words — `announce failed: 502 Bad Gateway` — and the whole
 * point of the card is that they arrive intact. A picture in which that line was truncated to
 * nothing matches itself.
 */
@OptIn(ExperimentalTestApi::class)
class TrackersTabTest {
    private val details = designDetails(DetailsTab.Trackers)

    @Test
    fun everyAnnounceUrlIsOnTheScreen() =
        runComposeUiTest {
            setContent { KachokTheme { DetailsPanel(details) } }
            onNodeWithText("http://bttracker.debian.org:6969/announce").assertIsDisplayed()
            onNodeWithText("udp://tracker.opentrackr.org:1337/announce").assertIsDisplayed()
            onNodeWithText("udp://open.demonii.com:1337/announce").assertIsDisplayed()
        }

    /** In the tracker's own words, whole. */
    @Test
    fun aRefusalIsQuotedRatherThanSummarised() =
        runComposeUiTest {
            setContent { KachokTheme { DetailsPanel(details) } }
            onNodeWithText("announce failed: 502 Bad Gateway").assertIsDisplayed()
        }

    /**
     * *Not tried* is a status of its own, and it is what most trackers are.
     *
     * BEP 12 has a client use the first that answers. A tab that showed only the one in use would
     * make a three-tracker torrent look like a one-tracker torrent.
     */
    @Test
    fun aTrackerNobodyReachedSaysNotTriedRatherThanBeingHidden() =
        runComposeUiTest {
            setContent { KachokTheme { DetailsPanel(details) } }
            onNodeWithText("not tried").assertIsDisplayed()
        }

    @Test
    fun theDhtHasItsOwnCardWhenItIsOn() =
        runComposeUiTest {
            setContent { KachokTheme { DetailsPanel(details) } }
            onNodeWithText("DHT (BEP 5)").assertIsDisplayed()
            onNodeWithText("214 nodes · announced 6 m ago · next in 9 m").assertIsDisplayed()
        }

    @Test
    fun reAnnounceLeavesThePanel() =
        runComposeUiTest {
            var asked = 0
            setContent { KachokTheme { DetailsPanel(details, onAnnounce = { asked++ }) } }
            onNodeWithContentDescription("Re-announce").performClick()
            assertEquals(1, asked)
        }

    /** A torrent with no announce list says so rather than drawing an empty tab. */
    @Test
    fun aTrackerlessTorrentSaysSo() =
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
                            tab = DetailsTab.Trackers,
                            trackers = emptyList(),
                            trackersSummary = "0 trackers",
                        ),
                    )
                }
            }
            onNodeWithText("This torrent names no trackers.").assertIsDisplayed()
        }
}
