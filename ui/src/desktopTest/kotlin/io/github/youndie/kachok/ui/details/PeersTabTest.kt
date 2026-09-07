package io.github.youndie.kachok.ui.details

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.youndie.kachok.ui.main.designDetails
import io.github.youndie.kachok.ui.theme.KachokTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The words on the *Peers* tab, which the golden cannot check.
 *
 * A picture in which a flag was drawn in the wrong colour matches itself; a picture that lost its
 * legend matches itself too. These are the claims the tab makes to a person who has never seen
 * `U`, `C` or `I` before.
 */
@OptIn(ExperimentalTestApi::class)
class PeersTabTest {
    private val details = designDetails(DetailsTab.Peers)

    @Test
    fun theTabDrawsARowPerPeerWithItsAddressAndClient() =
        runComposeUiTest {
            setContent { KachokTheme { DetailsPanel(details) } }
            onNodeWithText("88.99.242.17:6881").assertIsDisplayed()
            onNodeWithText("qBittorrent 5.1").assertIsDisplayed()
            onNodeWithText("1 842").assertIsDisplayed()
        }

    /**
     * The key is on the screen, not in anybody's head.
     *
     * `U`, `C` and `I` are one character each and no reader can guess them; the design puts the
     * legend at the foot of the tab for exactly that reason.
     */
    @Test
    fun theFlagsAreExplainedOnTheTabItself() =
        runComposeUiTest {
            setContent { KachokTheme { DetailsPanel(details) } }
            onNodeWithText("unchoked").assertIsDisplayed()
            onNodeWithText("choked").assertIsDisplayed()
            onNodeWithText("interested").assertIsDisplayed()
        }

    @Test
    fun theColumnHeadsAreTheDesignsFour() =
        runComposeUiTest {
            setContent { KachokTheme { DetailsPanel(details) } }
            listOf("ADDRESS", "CLIENT", "FLAG", "KIB/S").forEach {
                onNodeWithText(it).assertIsDisplayed()
            }
        }

    /** A torrent with no peers says so, rather than showing an empty table that looks unfinished. */
    @Test
    fun aTorrentWithNoPeersSaysSo() =
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
                            tab = DetailsTab.Peers,
                            peers = emptyList(),
                        ),
                    )
                }
            }
            onNodeWithText("No peers connected.").assertIsDisplayed()
        }

    /** And no tab says it is waiting for the engine any more, because none of them is. */
    @Test
    fun noTabSaysItIsPlanned() =
        runComposeUiTest {
            setContent { KachokTheme { DetailsPanel(details) } }
            assertEquals(
                0,
                onAllNodesWithText("planned").fetchSemanticsNodes().size,
                "a tab still wears the badge it earned by being empty",
            )
        }
}
