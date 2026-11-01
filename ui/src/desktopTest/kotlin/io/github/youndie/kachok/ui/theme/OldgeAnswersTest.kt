package io.github.youndie.kachok.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.youndie.kachok.ui.add.AddTorrentDialog
import io.github.youndie.kachok.ui.add.designTorrentToAdd
import io.github.youndie.kachok.ui.remove.DELETE_DATA
import io.github.youndie.kachok.ui.remove.RemoveState
import io.github.youndie.kachok.ui.remove.RemoveTorrentDialog
import io.github.youndie.kachok.ui.theme.oldge.OldgeKit
import io.github.youndie.oldge.feedback.OldgeProgressWell
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B-183: what oldge-ui answered youndie/oldge-ui#45–#50 with, used by the oldge kit in place of what
 * it drew from outside. Each test fails when the kit stops passing the library its answer.
 */
@OptIn(ExperimentalTestApi::class)
class OldgeAnswersTest {
    /** The segmented choice is the pointer height by itself, level with a settings field (#45). */
    @Test
    fun theSegmentedChoiceStandsLevelWithTheFields(): Unit =
        runComposeUiTest {
            setContent {
                KachokTheme(kit = OldgeKit.Default) {
                    Box(Modifier.testTag(SEGMENTED)) {
                        KachokTheme.kit.Segmented("Mode", listOf("One", "Two"), 0, true) {}
                    }
                }
            }

            onNodeWithTag(SEGMENTED).assertHeightIsEqualTo(32.dp)
        }

    /** A value changed from its default is ringed in the accent's ink by the field itself (#46). */
    @Test
    fun aChangedFieldIsRingedInTheAccent() {
        val changed = accentPixels(changed = true)
        val unchanged = accentPixels(changed = false)

        assertTrue(changed > RING_PIXELS, "a changed field draws its ring: $changed")
        assertEquals(0, unchanged, "an unchanged field has none")
    }

    /** The remove box's directory is its own description line: pressed, it toggles the box (#47). */
    @Test
    fun theDirectoryIsTheBoxsOwnLine(): Unit =
        runComposeUiTest {
            val toggled = mutableListOf<Boolean>()
            setContent {
                KachokTheme(kit = OldgeKit.Default) {
                    RemoveTorrentDialog(
                        RemoveState(name = "Sintel.tar", where = "~/Downloads", howMuch = "2.89 GiB on disk"),
                        onToggleData = { toggled += it },
                    )
                }
            }

            onNode(hasText(DELETE_DATA) and isToggleable()).assert(hasText("~/Downloads"))
            onNodeWithText("~/Downloads").performClick()

            assertEquals(listOf(true), toggled)
        }

    /** *Add paused* is shown and not offered: the library's disabled option, its reason under it (#47). */
    @Test
    fun addPausedIsShownAndNotOffered(): Unit =
        runComposeUiTest {
            setContent { KachokTheme(kit = OldgeKit.Default) { AddTorrentDialog(designTorrentToAdd) } }

            onNode(hasText("Start immediately") and isSelectable()).assertIsSelected().assertIsEnabled()
            onNode(hasText("Add paused") and isSelectable())
                .assertIsNotEnabled()
                .assert(hasText("Planned", substring = true))
        }

    /**
     * A row its state tints gives the well its track, so the bar belongs to the row; a plain row's
     * track is the `divider`, and its well stays the skin's (#49).
     */
    @Test
    fun aTintedRowsWellIsTheRowsColour(): Unit =
        runComposeUiTest {
            setContent {
                KachokTheme(kit = OldgeKit.Default) {
                    Box(Modifier.testTag(BAR)) {
                        KachokTheme.kit.ProgressBar(0f, "Sintel.tar", TRACK, Color.Red, Modifier.width(120.dp))
                    }
                }
            }
            val node = onNodeWithTag(BAR)
            val image = node.captureToImage()
            val share = node.pixelsNear(TRACK).toFloat() / (image.width * image.height)
            assertTrue(share > TINTED_SHARE, "the tinted row's well is its track: $share")
        }

    @Test
    fun aPlainRowsWellIsTheSkins(): Unit =
        runComposeUiTest {
            setContent {
                KachokTheme(kit = OldgeKit.Default) {
                    Column {
                        Box(Modifier.testTag(BAR)) {
                            val track = KachokTheme.colors.divider
                            KachokTheme.kit.ProgressBar(0.4f, "Sintel.tar", track, Color.Red, Modifier.width(120.dp))
                        }
                        Box(Modifier.testTag(SKIN)) {
                            OldgeProgressWell(
                                0.4f,
                                "Sintel.tar",
                                Modifier.width(120.dp).height(10.dp),
                                fill = Color.Red,
                            )
                        }
                    }
                }
            }
            val kit = onNodeWithTag(BAR).captureToImage().toPixelMap()
            val skin = onNodeWithTag(SKIN).captureToImage().toPixelMap()

            assertEquals(skin.width to skin.height, kit.width to kit.height)
            for (x in 0 until kit.width) {
                for (y in 0 until kit.height) {
                    assertEquals(skin[x, y], kit[x, y], "the plain row's bar is the library's at ($x, $y)")
                }
            }
        }

    private fun accentPixels(changed: Boolean): Int {
        var count = 0
        runComposeUiTest {
            var accent = Color.Unspecified
            setContent {
                KachokTheme(kit = OldgeKit.Default) {
                    accent = KachokTheme.colors.accentText
                    Box(Modifier.testTag(FIELD)) {
                        KachokTheme.kit.SettingField("Port", "6881", 80.dp, null, changed, false) {}
                    }
                }
            }
            count = onNodeWithTag(FIELD).pixelsNear(accent)
        }
        return count
    }

    private fun SemanticsNodeInteraction.pixelsNear(colour: Color): Int {
        val pixels = captureToImage().toPixelMap()
        var count = 0
        for (x in 0 until pixels.width) {
            for (y in 0 until pixels.height) {
                val c = pixels[x, y]
                if (abs(c.red - colour.red) < NEAR && abs(c.green - colour.green) < NEAR &&
                    abs(c.blue - colour.blue) < NEAR
                ) {
                    count++
                }
            }
        }
        return count
    }

    private companion object {
        const val SEGMENTED = "segmented"
        const val FIELD = "field"
        const val BAR = "bar"
        const val SKIN = "skin"

        /** A colour no skin draws a well in. */
        val TRACK = Color(0xFFFF00FF)

        /** How close a channel is to count as the colour: antialiasing moves an edge pixel a little. */
        const val NEAR = 0.04f

        /** A hairline round an 80 × 32 field is a couple of hundred pixels; a stray few are not one. */
        const val RING_PIXELS = 100

        /** The well inside its border and bevel is most of the bar. */
        const val TINTED_SHARE = 0.3f
    }
}
