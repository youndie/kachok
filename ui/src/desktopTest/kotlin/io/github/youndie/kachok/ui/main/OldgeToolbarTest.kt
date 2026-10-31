package io.github.youndie.kachok.ui.main

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.youndie.kachok.ui.list.TorrentState
import io.github.youndie.kachok.ui.theme.KachokTheme
import io.github.youndie.kachok.ui.theme.oldge.OldgeKit
import io.github.youndie.kachok.ui.theme.oldge.TOOL_ICONS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B-176: under the oldge kit the toolbar is oldge-ui's buttons and search bar, and it is still the
 * same toolbar — every control named, every press reaching its command, the whole bar inside the
 * narrowest window.
 */
@OptIn(ExperimentalTestApi::class)
class OldgeToolbarTest {
    /** A glyph with no entry is drawn as *More*, which says nothing about what the button does. */
    @Test
    fun everyToolbarGlyphHasAnOldgeIcon() {
        val glyphs =
            (listOf(null) + TorrentState.entries)
                .flatMap { ToolbarState().forSelection(it).all }
                .map { it.label to it.glyph }
                .toSet()
        val missing = glyphs.filter { (_, glyph) -> glyph !in TOOL_ICONS }.map { it.first }
        assertEquals(emptyList(), missing, "toolbar controls with no oldge-ui icon")
    }

    @Test
    fun everyEnabledControlIsNamedAndPressesItsCommand(): Unit =
        runComposeUiTest {
            val bar = ToolbarState().forSelection(TorrentState.Downloading)
            val pressed = mutableListOf<ToolbarCommand?>()
            setContent {
                KachokTheme(kit = OldgeKit.Default) {
                    Toolbar(bar, onAction = { pressed += it.command })
                }
            }

            val enabled = bar.all.filter { it.enabled }
            enabled.forEach { onNodeWithContentDescription(it.label).performClick() }

            assertEquals(enabled.map { it.command }, pressed)
            onNodeWithContentDescription("Resume").assertIsNotEnabled()
        }

    @Test
    fun theFilterTakesTextAndItsClearButtonEmptiesIt(): Unit =
        runComposeUiTest {
            val typed = mutableListOf<String>()
            setContent {
                KachokTheme(kit = OldgeKit.Default) {
                    Toolbar(ToolbarState(filter = "deb"), onFilter = { typed += it })
                }
            }

            onNodeWithContentDescription("Filter").performTextInput("x")
            // The library names its clear button itself, in Russian, and takes no label for it.
            onNodeWithContentDescription("Очистить").performClick()

            assertEquals(listOf("xdeb", ""), typed)
        }

    /**
     * At the narrow artboard's 600 dp every control keeps its 36 dp and ends inside the bar. A bar
     * that does not fit does not overflow visibly: the last controls are measured into what is left,
     * which is nothing, so the width is what is asserted, and not only the edge.
     */
    @Test
    fun theNarrowBarFitsEveryControl(): Unit =
        runComposeUiTest {
            val bar = ToolbarState(filter = "deb").forSelection(TorrentState.Downloading)
            setContent {
                KachokTheme(kit = OldgeKit.Default) {
                    Toolbar(bar, Modifier.width(NARROW), narrow = true)
                }
            }

            bar.all.forEach { action ->
                val bounds = onNodeWithContentDescription(action.label).getBoundsInRoot()
                assertTrue(
                    bounds.right - bounds.left >= 36.dp,
                    "${action.label} squeezed to ${bounds.right - bounds.left}",
                )
                assertTrue(bounds.right <= NARROW, "${action.label} ends at ${bounds.right}, past $NARROW")
            }
        }

    private companion object {
        val NARROW = 600.dp
    }
}
