package io.github.youndie.kachok.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.youndie.kachok.ui.NARROW_ARTBOARD_WIDTH
import io.github.youndie.kachok.ui.main.HINT_DELAY_MILLIS
import io.github.youndie.kachok.ui.session.Preferences
import io.github.youndie.kachok.ui.session.settingsOf
import io.github.youndie.kachok.ui.theme.KachokKit
import io.github.youndie.kachok.ui.theme.KachokTheme
import io.github.youndie.kachok.ui.theme.material.MaterialKit
import io.github.youndie.kachok.ui.theme.oldge.OldgeKit
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A long default directory gives way to the label beside it, and not the other way round (B-179).
 *
 * Seen live in both kits: a home directory under `/private/tmp/…` drew *Save to* as «Sa / ve / to»,
 * because the control row is measured first and its `default …` was the whole path. The window is
 * the smallest a person can make, where the row has least to share.
 */
@OptIn(ExperimentalTestApi::class)
class LongDefaultTest {
    @Test
    fun theLabelKeepsItsWidthBesideALongDefaultInMaterial() {
        theLabelKeepsItsWidth(MaterialKit.Default)
    }

    @Test
    fun theLabelKeepsItsWidthBesideALongDefaultInOldge() {
        theLabelKeepsItsWidth(OldgeKit.Default)
    }

    /** The cut is not a loss: the whole default is a hover away. */
    @Test
    fun theWholeDefaultIsInItsHint(): Unit =
        runComposeUiTest {
            setContent {
                KachokTheme {
                    Box(Modifier.width(NARROW_ARTBOARD_WIDTH.dp)) {
                        SettingsScreen(withLongDefault(settingsOf(Preferences(directory = LONG_DEFAULT))))
                    }
                }
            }
            assertEquals(0, onAllNodesWithText(LONG_DEFAULT).fetchSemanticsNodes().size, "drawn whole before a hover")

            onNodeWithText("default …", substring = true).performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(HINT_DELAY_MILLIS + 200L)

            onNodeWithText(LONG_DEFAULT).assertExists("the hover did not show the whole default")
        }

    @Test
    fun theLongDefaultIsExactlyAsLongAsTheItemSays() {
        assertEquals(120, LONG_DEFAULT.length)
    }

    private fun theLabelKeepsItsWidth(kit: KachokKit) =
        runComposeUiTest {
            setContent {
                KachokTheme(kit = kit) {
                    Box(Modifier.width(NARROW_ARTBOARD_WIDTH.dp)) {
                        SettingsScreen(withLongDefault(settingsOf(Preferences(directory = LONG_DEFAULT))))
                    }
                }
            }
            val texts = textNodes(onRoot(useUnmergedTree = true).fetchSemanticsNode())
            // The row's label, and not the oldge field's own, which carries the same words and has no
            // bounds on screen: the leftmost of those drawn. None at all is the defect at its worst —
            // a default wide enough to push the label out of the row.
            val label =
                texts
                    .filter { it.text == "Save to" && !it.node.boundsInRoot.isEmpty }
                    .minByOrNull { it.node.boundsInRoot.left }
                    ?: fail("*Save to* has no width left on screen in $kit")
            val layout =
                mutableListOf<TextLayoutResult>()
                    .also {
                        label.node.config[SemanticsActions.GetTextLayoutResult]
                            .action!!
                            .invoke(it)
                    }.single()
            val needs = ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth).toInt()
            assertEquals(1, layout.lineCount, "*Save to* broke into ${layout.lineCount} lines in $kit")
            assertTrue(
                label.node.size.width >= needs,
                "*Save to* was given ${label.node.size.width} px and needs $needs px in $kit",
            )

            val default = texts.single { it.text.startsWith("default ") && it.text.contains("kachok") }
            val lastSegment = LONG_DEFAULT.substringAfterLast('/')
            assertTrue(default.text.endsWith("/$lastSegment"), "the default lost its last segment: ${default.text}")
            assertTrue(default.text.startsWith("default …"), "a 120-character default was not cut: ${default.text}")
        }

    private class Drawn(
        val node: SemanticsNode,
        val text: String,
    )

    private fun textNodes(root: SemanticsNode): List<Drawn> =
        generateSequence(listOf(root)) { nodes -> nodes.flatMap { it.children }.ifEmpty { null } }
            .flatten()
            .mapNotNull { node ->
                node.config
                    .getOrNull(SemanticsProperties.Text)
                    ?.joinToString("")
                    ?.let { Drawn(node, it) }
            }.toList()
}
