package io.github.youndie.kachok.ui

import io.github.youndie.kachok.ui.theme.INTERFACE_SCALE
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The floor and the artboard are the same number, and stay it.
 *
 * **Two numbers that mean one thing drift apart**, and this pair would drift silently: the window's
 * minimum is enforced by AWT where no test looks, and the narrow golden is a photograph that would
 * go on being recorded at its old size. Tying them here is what makes the fixture a claim about the
 * smallest window a person can actually make
 * ([B-131](../../../../../../../docs/backlog/B-131-the-window-has-no-minimum-size.md)).
 */
class MinimumWindowTest {
    @Test
    fun theFloorIsTheNarrowArtboardAtTheInterfaceScale() {
        assertEquals(
            (NARROW_ARTBOARD_WIDTH * INTERFACE_SCALE).roundToInt(),
            MINIMUM_WINDOW_WIDTH,
            "the smallest window and the narrow artboard have come apart: the design draws the " +
                "narrow layout at $NARROW_ARTBOARD_WIDTH units and the interface is scaled $INTERFACE_SCALE",
        )
        assertEquals(
            (NARROW_ARTBOARD_HEIGHT * INTERFACE_SCALE).roundToInt(),
            MINIMUM_WINDOW_HEIGHT,
        )
    }

    /**
     * And the golden of the narrow window is recorded at exactly that floor.
     *
     * The fixture's own numbers are in its annotation, where a test cannot read them; what it can
     * read is the picture, and a picture of the wrong size is a fixture somebody changed without
     * looking at what it was for.
     */
    @Test
    fun theNarrowGoldenIsRecordedAtTheSmallestWindowAllowed() {
        val golden = java.io.File("src/desktopTest/snapshots/main_narrow.png")
        val image = javax.imageio.ImageIO.read(golden)

        assertEquals(MINIMUM_WINDOW_WIDTH, image.width, "the narrow golden is not the smallest window")
        assertEquals(MINIMUM_WINDOW_HEIGHT, image.height)
    }
}
