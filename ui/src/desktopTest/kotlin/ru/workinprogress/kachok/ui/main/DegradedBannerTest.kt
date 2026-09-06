package ru.workinprogress.kachok.ui.main

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import ru.workinprogress.kachok.ui.theme.KachokTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one line on this screen whose whole value is that it is somebody else's words.
 *
 * The engine's rule — that "no peers, no reason" is a state nobody can act on — is inherited here:
 * the class name and the message are what a person searches for. A banner that summarised them
 * would be a banner that threw away the only thing on it worth having, and a golden could not tell:
 * a picture in which the exception was truncated to nothing matches itself.
 */
@OptIn(ExperimentalTestApi::class)
class DegradedBannerTest {
    private val summary = "payload.bin is degraded."
    private val detail = "commands: the picker is already in use"

    @Test
    fun theExceptionIsOnTheBannerInItsOwnWords() =
        runComposeUiTest {
            setContent { KachokTheme { DegradedBanner(summary, detail) } }
            onNodeWithText(summary).assertIsDisplayed()
            onNodeWithText(detail).assertIsDisplayed()
        }

    /**
     * *Show it* is a control, and it used to be a bordered box.
     *
     * `MainWindow` took the callback and passed nothing, so the only way to read an exception the
     * banner had ellipsized was to guess which row it came from
     * ([B-76](../../../../../../../../docs/backlog/B-76-the-last-dead-controls.md)).
     */
    @Test
    fun showItReportsThePress() =
        runComposeUiTest {
            var shown = 0
            setContent { KachokTheme { DegradedBanner(summary, detail, onShow = { shown++ }) } }
            onNodeWithText("Show it").performClick()
            assertEquals(1, shown)
        }

    /**
     * A long exception is still on the screen, however little of it fits.
     *
     * The banner is one line high and the text is ellipsized, so what this asserts is that the node
     * carries the whole string — the panel is where the rest of it is read, and *Show it* is how a
     * person gets there.
     */
    @Test
    fun aLongExceptionIsCarriedWholeEvenThoughOneLineCannotShowIt() =
        runComposeUiTest {
            val long =
                "java.net.SocketException: Network is unreachable (connect failed) at " +
                    "sun.nio.ch.Net.connect0(Native Method) at sun.nio.ch.Net.connect(Net.java:589)"
            setContent { KachokTheme { DegradedBanner(summary, long) } }
            onNodeWithText(long).assertIsDisplayed()
        }
}
