package io.github.youndie.kachok.ui.theme.material

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.ktx.harmonize
import com.materialkolor.ktx.toColor
import com.materialkolor.ktx.toHct
import io.github.youndie.kachok.ui.theme.KachokColors

/**
 * The design's roles for a seed it was not drawn in (B-161): MaterialKolor's scheme for the seed,
 * and every one of this design's roles placed in it the way the hand-written one sits in the teal.
 *
 * **Why every role is derived, and not only the ones Material has no name for.** The hand-written
 * scheme borrows Material's names at tones Material does not give them: its `surfaceVariant` is the
 * raised ground at tone 10 where Material's is tone 30, its `onSurfaceVariant` the faint text at
 * tone 60 where Material's is 80, its `outline` a hairline at 28 where Material's is 60. Copying a
 * generated scheme's roles across by name would draw the column header three times brighter, the
 * hairlines as borders and the faint text level as the middle one — a second design, and two of
 * the three text levels collapsed into one. So the generated scheme provides the *anchors*, and the
 * design provides the rest.
 *
 * **The relation, per role.** Each role belongs to a family with one anchor: a role the generated
 * scheme has at the same tone the hand-written scheme has it. In HCT (the hue, chroma and tone
 * material-color-utilities generates in), the derived colour has
 *
 * - the hand-written role's **tone**, exactly — tone is L*, so every contrast the design drew
 *   between two roles survives any seed;
 * - the anchor's **hue**, plus the hand-written role's offset from the hand-written anchor (the
 *   error row's tint sits six degrees warmer than the error itself, and still does);
 * - the hand-written role's **chroma**, scaled by how much more or less colourful the generated
 *   anchor is than the hand-written one — so *Neutral* quiets every member of a family together
 *   and *Vibrant* lifts them together.
 *
 * The families, each as *hand-written anchor → generated anchor: members*:
 *
 * - **accent** — `primary` `#4FD9C2` (T79) → `primary` (T80): the accent and its container roles,
 *   the bright content of a control on the container, the selected row's figure, track and line.
 * - **neutral** — `onSurface` `#DDE4E1` (T90) → `onSurface` (T90): the surface, the raised grounds,
 *   the panel, the hairline between rows, the neutral card, the settings footnote's ground, the
 *   first text level.
 * - **neutral variant** — the middle text level `#BEC9C6` (T80) → `onSurfaceVariant` (T80, the
 *   tone Material gives that role): the middle and faint text levels, the outline, the divider,
 *   the dialogs' border, the stopping row's bar.
 * - **error** — `error` `#FFB4AB` (T80) → `error` (T80): the error roles, the error row's figure,
 *   tint and track, the degraded banner. Material's error palette does not follow the seed, so
 *   this family moves least.
 * - **warning** — `warning` `#F3C46B` → the same amber harmonised towards the generated `primary`:
 *   the warning roles and the tracker's warning card.
 *
 * The anchors are the high-tone roles on purpose: at tone 6 the gamut leaves a dark surface very
 * little chroma to have, and a ratio taken there would say less about the scheme than about sRGB.
 * The hover hint is the one pair copied by name — `inverseSurface` and `inverseOnSurface` — because
 * the hand-written scheme never set them either: it takes Material's baseline, and the generated
 * scheme's are the same roles at the same tones in the seed's hue.
 *
 * **`warning` stays amber.** `harmonize` turns a colour's hue towards the seed's by half the
 * difference and never more than 15 degrees, so *checking* and *stopping* are 66–98 degrees of
 * hue whatever the seed — amber — and lean the way the rest of the window does.
 *
 * Contrast is MaterialKolor's default, and spec 2021 is named rather than taken from the library's
 * default so a release that moved the default cannot move a golden. Contrast −1.0 is never asked
 * for: the port gives a white dark scheme there.
 */
internal class SeededDesign(
    val colors: KachokColors,
    /** What `MaterialTheme` gets: the generated scheme with the design's roles over its names. */
    val scheme: ColorScheme,
)

internal fun seededDesign(colours: MaterialColours): SeededDesign {
    val generated =
        dynamicColorScheme(
            seedColor = colours.seed,
            isDark = true,
            style = colours.style.palette,
            specVersion = ColorSpec.SpecVersion.SPEC_2021,
        )
    val hand = KachokDarkColors
    val accent = Family(hand.primary, generated.primary)
    val neutral = Family(hand.onSurface, generated.onSurface)
    val variant = Family(KachokPalette.onSurfaceMuted, generated.onSurfaceVariant)
    val error = Family(hand.error, generated.error)
    val warning = Family(KachokWarning.warning, KachokWarning.warning.harmonize(generated.primary))
    val colors =
        KachokColors(
            accent = accent(hand.primary),
            onAccent = accent(hand.onPrimary),
            accentContainer = accent(hand.primaryContainer),
            onAccentContainer = accent(hand.onPrimaryContainer),
            accentBright = accent(KachokPalette.primaryBright),
            accentContainerHigh = accent(KachokPalette.primaryContainerHigh),
            text = neutral(hand.onSurface),
            textMuted = variant(KachokPalette.onSurfaceMuted),
            textFaint = variant(hand.onSurfaceVariant),
            surface = neutral(hand.surface),
            raised = neutral(hand.surfaceVariant),
            raisedHigh = neutral(hand.surfaceContainerHigh),
            raisedHighest = neutral(hand.surfaceContainerHighest),
            panel = neutral(KachokPalette.panel),
            outline = variant(hand.outline),
            divider = variant(hand.outlineVariant),
            rowHairline = neutral(KachokPalette.rowHairline),
            error = error(hand.error),
            onError = error(hand.onError),
            errorContainer = error(hand.errorContainer),
            warning = warning(KachokWarning.warning),
            onWarning = warning(KachokWarning.onWarning),
            warningContainer = warning(KachokWarning.warningContainer),
            onWarningContainer = warning(KachokWarning.onWarningContainer),
            errorFigure = error(KachokPalette.errorFigure),
            errorRowTint = error(KachokPalette.errorRowTint),
            errorTrack = error(KachokPalette.errorTrack),
            stoppingBar = variant(KachokPalette.stoppingBar),
            selectedFigure = accent(KachokPalette.selectedFigure),
            selectedTrack = accent(KachokPalette.selectedTrack),
            degradedBanner = error(KachokPalette.degradedBanner),
            warningCard = warning(KachokPalette.warningCard),
            onWarningCard = warning(KachokPalette.onWarningCard),
            neutralCard = neutral(KachokPalette.neutralCard),
            tooltip = generated.inverseSurface,
            onTooltip = generated.inverseOnSurface,
            footnoteGround = neutral(KachokPalette.footnoteGround),
            dialogBorder = variant(KachokPalette.dialogBorder),
        )
    // The same roles `KachokDarkColors` names, so whatever reads `MaterialTheme` directly — the
    // title bar, a menu — sees the design's grounds and lines and not Material's.
    val scheme =
        generated.copy(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            primaryContainer = colors.accentContainer,
            onPrimaryContainer = colors.onAccentContainer,
            error = colors.error,
            onError = colors.onError,
            errorContainer = colors.errorContainer,
            onErrorContainer = error(hand.onErrorContainer),
            background = colors.surface,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.raised,
            onSurfaceVariant = colors.textFaint,
            surfaceContainer = colors.raised,
            surfaceContainerHigh = colors.raisedHigh,
            surfaceContainerHighest = colors.raisedHighest,
            outline = colors.outline,
            outlineVariant = colors.divider,
        )
    return SeededDesign(colors, scheme)
}

/** MaterialKolor's name for a style; ours is what the settings file stores. */
private val SchemeStyle.palette: PaletteStyle
    get() =
        when (this) {
            SchemeStyle.TonalSpot -> PaletteStyle.TonalSpot
            SchemeStyle.Vibrant -> PaletteStyle.Vibrant
            SchemeStyle.Expressive -> PaletteStyle.Expressive
            SchemeStyle.Neutral -> PaletteStyle.Neutral
        }

/**
 * A role's place relative to its family's anchor, carried from the hand-written scheme to a
 * generated one: the role's own tone, the anchor's hue plus the role's offset from it, the role's
 * chroma times the generated anchor's over the hand-written anchor's.
 */
private class Family(
    handAnchor: Color,
    generatedAnchor: Color,
) {
    private val hand = handAnchor.toHct()
    private val generated = generatedAnchor.toHct()
    private val chromaScale = generated.chroma / hand.chroma

    operator fun invoke(designed: Color): Color {
        val role = designed.toHct()
        val hue = (generated.hue + (role.hue - hand.hue)).mod(FULL_TURN)
        return Hct.from(hue, role.chroma * chromaScale, role.tone).toColor()
    }
}

private const val FULL_TURN = 360.0
