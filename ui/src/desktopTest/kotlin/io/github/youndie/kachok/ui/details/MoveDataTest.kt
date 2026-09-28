package io.github.youndie.kachok.ui.details

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.youndie.kachok.ui.main.designDetails
import io.github.youndie.kachok.ui.main.designSession
import io.github.youndie.kachok.ui.session.Rates
import io.github.youndie.kachok.ui.session.detailsOf
import io.github.youndie.kachok.ui.theme.KachokTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * *Save to*'s folder button, which is where a torrent's files are moved from
 * ([B-134](../../../../../../../../docs/backlog/B-134-move-a-torrent-s-data.md)).
 *
 * The button is drawn only when the panel is given a handler. That is what keeps the goldens the
 * pictures they were — the sheet that renders them passes none — and it is asserted here, because
 * a button that appeared in a golden nobody re-recorded would fail on the one machine that checks
 * them and nowhere else.
 */
@OptIn(ExperimentalTestApi::class)
class MoveDataTest {
    @Test
    fun withNoHandlerThereIsNoButton() =
        runComposeUiTest {
            setContent { KachokTheme { DetailsPanel(designDetails()) } }
            onAllNodesWithContentDescription("Move Save to").assertCountEquals(0)
        }

    @Test
    fun theFolderButtonBesideSaveToAsksTheCaller() =
        runComposeUiTest {
            var asked = 0
            setContent { KachokTheme { DetailsPanel(designDetails(), onMoveData = { asked++ }) } }
            onNodeWithContentDescription("Move Save to").performClick()
            assertEquals(1, asked)
        }

    /** A move that did not happen says why, in the torrent's own complaints, until the next one. */
    @Test
    fun aMoveThatFailedIsAComplaint() =
        runComposeUiTest {
            val details =
                detailsOf(
                    state = designSession,
                    rates = Rates(down = 0, up = 0),
                    pieceLength = 1,
                    directory = "~/Downloads/iso",
                    moveProblem = "/mnt/b/iso.bin already exists; nothing was moved",
                )
            setContent { KachokTheme { DetailsPanel(details) } }
            onNodeWithText("MOVE").assertExists()
            onNodeWithText("/mnt/b/iso.bin already exists; nothing was moved").assertExists()
        }
}
