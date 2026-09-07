package io.github.youndie.kachok.ui.theme

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The theme's colours, read out of the recorded golden's **pixels**.
 *
 * Asserting that `KachokDarkColors.primary == Color(0xFF4FD9C2)` would compare the source with
 * itself: the constant would be checked against a copy of the constant, and every way the value
 * could fail to reach the screen — a role wired to the wrong slot, a theme not applied, a swatch
 * drawn from something else — would pass. So this opens the picture and counts what is actually
 * in it.
 *
 * The golden is `viddikRecord`'s output and is checked in. If it is missing, that is the failure
 * this test reports rather than a test that quietly passes.
 */
class ThemeColorsTest {
    private val golden = File("src/desktopTest/snapshots/theme_roles-and-type.png")

    /** The design's own swatch row, `docs/design/design-tokens.md` §1. */
    private val roles =
        listOf(
            "primary" to 0x4FD9C2,
            "primaryContainer" to 0x005046,
            "secondary" to 0xB0CCC6,
            "tertiary" to 0xAEC6E8,
            "warning" to 0xF3C46B,
            "error" to 0xFFB4AB,
            "surface" to 0x0F1513,
            "onSurface" to 0xDDE4E1,
        )

    @Test
    fun everyRoleTheDesignNamesIsOnTheSheetInItsOwnColour() {
        assertTrue(golden.isFile, "no golden at ${golden.absolutePath}; run :ui:viddikRecord")
        val image = ImageIO.read(golden)
        val counts = HashMap<Int, Int>()
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val rgb = image.getRGB(x, y) and 0xFFFFFF
                counts[rgb] = (counts[rgb] ?: 0) + 1
            }
        }

        // A swatch is 88 x 44 with a one-pixel border, so several thousand pixels of each. The
        // bound is far below that and far above the odd anti-aliased pixel that lands on a value
        // by accident.
        val missing = roles.filter { (_, rgb) -> (counts[rgb] ?: 0) < MINIMUM_SWATCH_PIXELS }
        assertTrue(
            missing.isEmpty(),
            "roles missing from the sheet, or drawn in another colour: " +
                missing.joinToString { (name, rgb) -> "$name #%06X (${counts[rgb] ?: 0} px)".format(rgb) },
        )
    }

    @Test
    fun theSheetIsTheSizeItsFixtureAsksFor() {
        // A golden of the wrong size is a fixture that changed without anyone looking at it, and
        // every pixel assertion above would still pass on the part that survived.
        val image = ImageIO.read(golden)
        assertEquals(440, image.width)
        assertEquals(560, image.height)
    }

    private companion object {
        const val MINIMUM_SWATCH_PIXELS = 1_500
    }
}
