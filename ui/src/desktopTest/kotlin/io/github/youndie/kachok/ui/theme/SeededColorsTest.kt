package io.github.youndie.kachok.ui.theme

import androidx.compose.ui.graphics.Color
import com.materialkolor.ktx.toHct
import io.github.youndie.kachok.ui.theme.material.KachokDarkColors
import io.github.youndie.kachok.ui.theme.material.KachokPalette
import io.github.youndie.kachok.ui.theme.material.KachokWarning
import io.github.youndie.kachok.ui.theme.material.MaterialColours
import io.github.youndie.kachok.ui.theme.material.MaterialKit
import io.github.youndie.kachok.ui.theme.material.SEED_SWATCHES
import io.github.youndie.kachok.ui.theme.material.SchemeStyle
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Material kit's colours from a seed (B-161): the design's own seed draws the hand-written
 * scheme, field for field, and any other seed moves the accent while the design's structure — its
 * tones, and amber for *checking* and *stopping* — stays.
 */
class SeededColorsTest {
    /**
     * Every role of [KachokColors], by name, with the hand-written value the design's seed must give.
     *
     * Spelled out here rather than taken from the kit's own mapping, so a default that drifted —
     * generated after all, or one role wired to a neighbour — fails by name.
     */
    private val handWritten: List<Pair<String, (KachokColors) -> Color>> =
        listOf(
            "accent" to { it.accent },
            "accentText" to { it.accentText },
            "onAccent" to { it.onAccent },
            "accentContainer" to { it.accentContainer },
            "onAccentContainer" to { it.onAccentContainer },
            "accentBright" to { it.accentBright },
            "accentContainerHigh" to { it.accentContainerHigh },
            "text" to { it.text },
            "textMuted" to { it.textMuted },
            "textFaint" to { it.textFaint },
            "surface" to { it.surface },
            "raised" to { it.raised },
            "raisedHigh" to { it.raisedHigh },
            "raisedHighest" to { it.raisedHighest },
            "panel" to { it.panel },
            "outline" to { it.outline },
            "divider" to { it.divider },
            "rowHairline" to { it.rowHairline },
            "error" to { it.error },
            "onError" to { it.onError },
            "errorContainer" to { it.errorContainer },
            "warning" to { it.warning },
            "onWarning" to { it.onWarning },
            "warningContainer" to { it.warningContainer },
            "onWarningContainer" to { it.onWarningContainer },
            "errorFigure" to { it.errorFigure },
            "errorRowTint" to { it.errorRowTint },
            "errorTrack" to { it.errorTrack },
            "stoppingBar" to { it.stoppingBar },
            "selectedFigure" to { it.selectedFigure },
            "selectedTrack" to { it.selectedTrack },
            "degradedBanner" to { it.degradedBanner },
            "warningCard" to { it.warningCard },
            "onWarningCard" to { it.onWarningCard },
            "neutralCard" to { it.neutralCard },
            "tooltip" to { it.tooltip },
            "onTooltip" to { it.onTooltip },
            "footnoteGround" to { it.footnoteGround },
            "dialogBorder" to { it.dialogBorder },
        )

    private val expected: Map<String, Color> =
        KachokDarkColors.let { s ->
            mapOf(
                "accent" to s.primary,
                "accentText" to s.primary,
                "onAccent" to s.onPrimary,
                "accentContainer" to s.primaryContainer,
                "onAccentContainer" to s.onPrimaryContainer,
                "accentBright" to KachokPalette.primaryBright,
                "accentContainerHigh" to KachokPalette.primaryContainerHigh,
                "text" to s.onSurface,
                "textMuted" to KachokPalette.onSurfaceMuted,
                "textFaint" to s.onSurfaceVariant,
                "surface" to s.surface,
                "raised" to s.surfaceVariant,
                "raisedHigh" to s.surfaceContainerHigh,
                "raisedHighest" to s.surfaceContainerHighest,
                "panel" to KachokPalette.panel,
                "outline" to s.outline,
                "divider" to s.outlineVariant,
                "rowHairline" to KachokPalette.rowHairline,
                "error" to s.error,
                "onError" to s.onError,
                "errorContainer" to s.errorContainer,
                "warning" to KachokWarning.warning,
                "onWarning" to KachokWarning.onWarning,
                "warningContainer" to KachokWarning.warningContainer,
                "onWarningContainer" to KachokWarning.onWarningContainer,
                "errorFigure" to KachokPalette.errorFigure,
                "errorRowTint" to KachokPalette.errorRowTint,
                "errorTrack" to KachokPalette.errorTrack,
                "stoppingBar" to KachokPalette.stoppingBar,
                "selectedFigure" to KachokPalette.selectedFigure,
                "selectedTrack" to KachokPalette.selectedTrack,
                "degradedBanner" to KachokPalette.degradedBanner,
                "warningCard" to KachokPalette.warningCard,
                "onWarningCard" to KachokPalette.onWarningCard,
                "neutralCard" to KachokPalette.neutralCard,
                "tooltip" to s.inverseSurface,
                "onTooltip" to s.inverseOnSurface,
                "footnoteGround" to KachokPalette.footnoteGround,
                "dialogBorder" to KachokPalette.dialogBorder,
            )
        }

    /** Every pair the settings offer that is not the design: five seeds by four styles, and the teal by three. */
    private val generated: List<MaterialColours> =
        SEED_SWATCHES
            .flatMap { swatch -> SchemeStyle.entries.map { MaterialColours(swatch.seed, it) } }
            .filterNot { it.isTheDesign }

    @Test
    fun theListNamesEveryRole() {
        // A role added to `KachokColors` and not here would be a role this test cannot see.
        val fields =
            KachokColors::class.java.declaredFields.count {
                !java.lang.reflect.Modifier.isStatic(
                    it.modifiers,
                )
            }
        assertEquals(fields, handWritten.size, "KachokColors has roles this test does not list")
        assertEquals(handWritten.map { it.first }.toSet(), expected.keys)
    }

    @Test
    fun theDesignsSeedIsTheHandWrittenSchemeFieldForField() {
        val kit = MaterialKit.of(MaterialColours(MaterialColours.DESIGN_SEED, SchemeStyle.TonalSpot))
        assertSame(MaterialKit.Default, kit)
        assertSame(KachokDarkColors, kit.scheme, "MaterialTheme gets the hand-written scheme")
        val wrong =
            handWritten.filter { (name, role) -> role(kit.colors) != expected.getValue(name) }.map { it.first }
        assertTrue(wrong.isEmpty(), "the design's seed does not draw the hand-written value of: $wrong")
    }

    /**
     * Another seed moves the accent towards its own hue; the style decides how far (Expressive moves
     * the accent off the seed's hue on purpose, so it is held only to "not the design's").
     */
    @Test
    fun anotherSeedMovesTheAccent() {
        generated.forEach { colours ->
            val accent = MaterialKit.of(colours).colors.accent
            assertNotEquals(KachokDarkColors.primary, accent, "$colours kept the design's accent")
            if (colours.style == SchemeStyle.TonalSpot || colours.style == SchemeStyle.Vibrant) {
                val off = hueDistance(accent.toHct().hue, colours.seed.toHct().hue)
                assertTrue(off < ACCENT_HUE_SLACK, "$colours: the accent is $off degrees off the seed's hue")
            }
        }
    }

    /**
     * *Checking* and *stopping* stay amber whatever the seed: `harmonize` turns the design's amber
     * (HCT hue 82) at most 15 degrees towards the seed, so its hue stays within 66–98 — amber, not
     * orange, not yellow — and its tone and a colourfulness that reads as amber stay too.
     */
    @Test
    fun warningStaysAmberWhateverTheSeed() {
        val design = KachokWarning.warning.toHct()
        generated.forEach { colours ->
            val warning =
                MaterialKit
                    .of(colours)
                    .colors.warning
                    .toHct()
            val turned = hueDistance(warning.hue, design.hue)
            assertTrue(turned <= MAX_HARMONY, "$colours: warning turned $turned degrees off the design's amber")
            assertTrue(warning.hue in AMBER, "$colours: warning hue ${warning.hue} is not amber")
            assertTrue(warning.chroma > MIN_AMBER_CHROMA, "$colours: warning chroma ${warning.chroma}")
            assertTrue(abs(warning.tone - design.tone) < TONE_SLACK, "$colours: warning tone ${warning.tone}")
        }
    }

    /**
     * The derivation's promise: every role keeps the hand-written role's tone, so every contrast the
     * design drew — three text levels, a selected row, an error row, the hairlines — holds for any
     * seed. The hover hint is the one pair taken by name from the generated scheme, and is held to
     * the same tones Material gives those roles.
     */
    @Test
    fun everyRoleKeepsTheDesignsTone() {
        generated.forEach { colours ->
            val colors = MaterialKit.of(colours).colors
            val off =
                handWritten
                    .filter { it.first != "tooltip" && it.first != "onTooltip" }
                    .mapNotNull { (name, role) ->
                        val tone = role(colors).toHct().tone
                        val design = expected.getValue(name).toHct().tone
                        "$name ${"%.1f".format(tone)} vs ${"%.1f".format(design)}".takeIf {
                            abs(tone - design) > TONE_SLACK
                        }
                    }
            assertTrue(off.isEmpty(), "$colours moved the tone of: $off")
            assertTrue(abs(colors.tooltip.toHct().tone - INVERSE_SURFACE_TONE) < TONE_SLACK, "$colours tooltip")
        }
    }

    /** The error family does not follow the seed: an error row is red under a violet window too. */
    @Test
    fun errorStaysRed() {
        generated.forEach { colours ->
            val colors = MaterialKit.of(colours).colors
            listOf(colors.error, colors.errorFigure, colors.errorTrack).forEach {
                val hue = it.toHct().hue
                assertTrue(hue in RED, "$colours: an error colour at hue $hue")
            }
        }
    }

    private fun hueDistance(
        a: Double,
        b: Double,
    ): Double = abs(a - b).let { if (it > HALF_TURN) FULL_TURN - it else it }

    private companion object {
        val AMBER = 66.0..98.0

        /** `harmonize`'s 15 degrees, and the one more an 8-bit colour rounds a hue by at this chroma. */
        const val MAX_HARMONY = 16.5
        val RED = 10.0..45.0
        const val MIN_AMBER_CHROMA = 25.0
        const val TONE_SLACK = 1.0
        const val ACCENT_HUE_SLACK = 25.0
        const val INVERSE_SURFACE_TONE = 90.0
        const val HALF_TURN = 180.0
        const val FULL_TURN = 360.0
    }
}
