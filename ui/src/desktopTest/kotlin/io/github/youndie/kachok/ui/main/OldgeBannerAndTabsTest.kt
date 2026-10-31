package io.github.youndie.kachok.ui.main

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.youndie.kachok.ui.details.DetailsPanel
import io.github.youndie.kachok.ui.details.DetailsTab
import io.github.youndie.kachok.ui.theme.KachokTheme
import io.github.youndie.kachok.ui.theme.oldge.OldgeKit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B-177: under the oldge kit the banner's *Show it* is oldge-ui's button and the details tabs are
 * its property-sheet tabs, and each still does what the Material one does.
 */
@OptIn(ExperimentalTestApi::class)
class OldgeBannerAndTabsTest {
    @Test
    fun showItReportsThePress(): Unit =
        runComposeUiTest {
            var shown = 0
            setContent {
                KachokTheme(kit = OldgeKit.Default) {
                    DegradedBanner("One session is degraded.", "writer loop failed", onShow = { shown++ })
                }
            }

            onNodeWithText("Show it").performClick()

            assertEquals(1, shown)
        }

    /** Every other tab, pressed, asks for itself: the kit hands back an index, and the panel a tab. */
    @Test
    fun everyTabAsksForItself(): Unit =
        runComposeUiTest {
            val asked = mutableListOf<DetailsTab>()
            setContent {
                KachokTheme(kit = OldgeKit.Default) {
                    DetailsPanel(designDetails(DetailsTab.Overview), onTab = { asked += it })
                }
            }

            onNodeWithContentDescription("Details").assertExists("the tab strip has no name")
            val others = DetailsTab.entries.filter { it != DetailsTab.Overview }.reversed()
            others.forEach { onNodeWithText(it.label).performClick() }

            assertEquals(others, asked)
        }
}
