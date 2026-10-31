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
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.pow
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

    /** Every pair the settings offer, the design's own included, by the names the settings show. */
    private val offered: List<Pair<String, MaterialColours>> =
        SEED_SWATCHES.flatMap { swatch ->
            SchemeStyle.entries.map { "${swatch.label} ${it.label}" to MaterialColours(swatch.seed, it) }
        }

    /**
     * The error row is told by its ground as well as its text (B-167): the tint is red where the
     * surface is not, by at least Oklab's just-noticeable difference.
     *
     * Measured in Oklab's a–b plane, lightness left out: the derivation keeps every tone, so the
     * tint is one lightness step above the surface under every seed — the step a raised row also
     * has. What a warm surface takes away is the redness, and the full ΔE would let the lightness
     * step pass for it (orange under *Vibrant* was 0.021 in ΔE and 0.009 in the a–b plane).
     */
    @Test
    fun theErrorRowTintStaysApartFromTheSurface() {
        val short =
            offered.mapNotNull { (name, colours) ->
                val colors = MaterialKit.of(colours).colors
                val gap = abDistance(colors.errorRowTint, colors.surface)
                "$name ${"%.4f".format(gap)}".takeIf { gap < MIN_TINT_GAP }
            }
        assertTrue(short.isEmpty(), "the error row tint is within $MIN_TINT_GAP of the surface under: $short")
    }

    /**
     * *Seeding* is not a third amber beside *Checking* and *Stopping* (B-167): the accent, and the
     * accent as text, are at least one hue name — amber's own width — from the warning's hue.
     */
    @Test
    fun theAccentStaysApartFromTheWarning() {
        val near =
            offered.flatMap { (name, colours) ->
                val colors = MaterialKit.of(colours).colors
                val warning = colors.warning.toHct().hue
                listOf("accent" to colors.accent, "accentText" to colors.accentText).mapNotNull { (role, colour) ->
                    val apart = hueDistance(colour.toHct().hue, warning)
                    "$name $role ${"%.1f".format(apart)}".takeIf { apart < MIN_STATE_HUE_GAP }
                }
            }
        assertTrue(near.isEmpty(), "within $MIN_STATE_HUE_GAP degrees of the warning's hue: $near")
    }

    /**
     * A seeding row is not drawn in the error's colour (B-170): the accent, and the accent as text,
     * are at least one hue name from `error`'s hue — the same width the warning is held to, since
     * the accent, the error and the amber are light colours at one tone and hue is what parts them.
     */
    @Test
    fun theAccentIsNotTheErrorsColour() {
        val near =
            offered.flatMap { (name, colours) ->
                val colors = MaterialKit.of(colours).colors
                val error = colors.error.toHct().hue
                listOf("accent" to colors.accent, "accentText" to colors.accentText).mapNotNull { (role, colour) ->
                    val apart = hueDistance(colour.toHct().hue, error)
                    "$name $role ${"%.1f".format(apart)}".takeIf { apart < MIN_STATE_HUE_GAP }
                }
            }
        assertTrue(near.isEmpty(), "within $MIN_STATE_HUE_GAP degrees of the error's hue: $near")
    }

    /**
     * Oklab's a–b distance, written out from Björn Ottosson's matrices rather than taken from
     * Compose's colour space, which is what the derivation uses: a ruler of its own.
     */
    private fun abDistance(
        x: Color,
        y: Color,
    ): Double {
        val a = oklab(x)
        val b = oklab(y)
        return hypot(a[1] - b[1], a[2] - b[2])
    }

    private fun oklab(colour: Color): DoubleArray {
        val r = linear(colour.red)
        val g = linear(colour.green)
        val b = linear(colour.blue)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return doubleArrayOf(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
    }

    private fun linear(channel: Float): Double =
        if (channel <=
            SRGB_KNEE
        ) {
            channel / SRGB_SLOPE
        } else {
            ((channel + SRGB_OFFSET) / (1 + SRGB_OFFSET)).pow(SRGB_GAMMA)
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

        /** Oklab's just-noticeable difference, the one CSS Color 4 maps gamuts by. */
        const val MIN_TINT_GAP = 0.02

        /** One hue name's width: amber, [AMBER], is 32 degrees wide. */
        const val MIN_STATE_HUE_GAP = 30.0
        const val SRGB_KNEE = 0.04045f
        const val SRGB_SLOPE = 12.92
        const val SRGB_OFFSET = 0.055
        const val SRGB_GAMMA = 2.4
        const val HALF_TURN = 180.0
        const val FULL_TURN = 360.0
    }
}
