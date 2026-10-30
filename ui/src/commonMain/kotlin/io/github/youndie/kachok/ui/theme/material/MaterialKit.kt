package io.github.youndie.kachok.ui.theme.material

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.youndie.kachok.ui.theme.KachokColors
import io.github.youndie.kachok.ui.theme.KachokKit
import io.github.youndie.kachok.ui.theme.KachokType

/**
 * The design as it was drawn — a dark Material 3 scheme, read off the design's swatch row, with the
 * roles Material does not ship beside it — or that design in a seed the person picked (B-161).
 *
 * **[Default] is the hand-written scheme, not a generated one.** `KachokDarkColors` and
 * `KachokPalette` were read off the design and are the reference; the design's teal under
 * [SchemeStyle.TonalSpot] draws them exactly, so the first frame after the seed setting arrived is
 * the frame before it. Every other [MaterialColours] is MaterialKolor's scheme for that seed, with
 * this design's roles placed in it the way they sit in the teal (`SeededDesign.kt`).
 *
 * **One of the two places in the module that name `androidx.compose.material3`** — `make check`
 * greps for it everywhere else (B-159); the other is the oldge kit, for the scheme `AppFrame` reads
 * (B-160). A screen that reached for `MaterialTheme` directly would draw the
 * same thing under this kit and the wrong thing under any other, and nothing would say so until
 * somebody switched. `com.materialkolor` is named in this directory only, for the same reason.
 *
 * **Equal by its colours.** The kit is a static composition local, and a new kit that is not equal
 * to the old one redraws the whole window — which is the point when the person picks a seed and a
 * waste on every other recomposition of the root. [of] hands back the same instance for the same
 * colours, and two instances for one choice compare equal anyway.
 */
internal class MaterialKit private constructor(
    val colours: MaterialColours,
    /** What `MaterialTheme` gets at the root, for whatever reads it directly — the title bar. */
    val scheme: ColorScheme,
    override val colors: KachokColors,
) : KachokKit {
    override fun equals(other: Any?): Boolean = other is MaterialKit && other.colours == colours

    override fun hashCode(): Int = colours.hashCode()

    override fun toString(): String = "MaterialKit(${colours.storedSeed}, ${colours.style.stored})"

    companion object {
        /** The design's own colours: the hand-written scheme. */
        val Default: MaterialKit = MaterialKit(MaterialColours.DEFAULT, KachokDarkColors, designColors())

        private var last: MaterialKit = Default

        /** The kit for [colours]: [Default] for the design's, a generated scheme for anything else. */
        fun of(colours: MaterialColours): MaterialKit {
            if (colours.isTheDesign) return Default
            last.takeIf { it.colours == colours }?.let { return it }
            val seeded = seededDesign(colours)
            return MaterialKit(colours, seeded.scheme, seeded.colors).also { last = it }
        }
    }

    override val type: KachokType = MATERIAL_TYPE

    @Composable
    override fun Root(content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = scheme,
            typography = KachokTypography,
            shapes = KachokShapes,
            content = content,
        )
    }

    override val contentColor: Color
        @Composable @ReadOnlyComposable
        get() = LocalContentColor.current

    @Composable
    override fun Text(
        text: String,
        modifier: Modifier,
        color: Color,
        style: TextStyle,
        textAlign: TextAlign?,
        overflow: TextOverflow,
        softWrap: Boolean,
        maxLines: Int,
        onTextLayout: ((TextLayoutResult) -> Unit)?,
    ) {
        androidx.compose.material3.Text(
            text = text,
            modifier = modifier,
            color = color,
            textAlign = textAlign,
            overflow = overflow,
            softWrap = softWrap,
            maxLines = maxLines,
            onTextLayout = onTextLayout,
            style = style.orRoot(),
        )
    }

    @Composable
    override fun Text(
        text: AnnotatedString,
        modifier: Modifier,
        color: Color,
        style: TextStyle,
        textAlign: TextAlign?,
        overflow: TextOverflow,
        softWrap: Boolean,
        maxLines: Int,
        onTextLayout: (TextLayoutResult) -> Unit,
    ) {
        androidx.compose.material3.Text(
            text = text,
            modifier = modifier,
            color = color,
            textAlign = textAlign,
            overflow = overflow,
            softWrap = softWrap,
            maxLines = maxLines,
            onTextLayout = onTextLayout,
            style = style.orRoot(),
        )
    }

    @Composable
    override fun Tooltip(
        shape: Shape,
        content: @Composable () -> Unit,
    ) {
        Surface(shape = shape, color = colors.tooltip, shadowElevation = 4.dp, content = content)
    }

    @Composable
    override fun Toggle(
        on: Boolean,
        label: String,
        enabled: Boolean,
        onToggle: () -> Unit,
    ) {
        MaterialToggle(on, label, enabled, onToggle)
    }

    @Composable
    override fun DialogButton(
        label: String,
        primary: Boolean,
        enabled: Boolean,
        destructive: Boolean,
        onClick: () -> Unit,
    ) {
        MaterialDialogButton(label, primary, enabled, destructive, onClick)
    }

    @Composable
    override fun ProgressBar(
        fraction: Float,
        track: Color,
        bar: Color,
        modifier: Modifier,
    ) {
        MaterialProgressBar(fraction, track, bar, modifier)
    }
}

/** The hand-written roles, as B-159 mapped them: the scheme's names, the palette and the warning. */
internal fun designColors(): KachokColors =
    KachokDarkColors.let { scheme ->
        KachokColors(
            accent = scheme.primary,
            // The design's teal reads as text on its dark grounds: one colour, two roles (B-164).
            accentText = scheme.primary,
            onAccent = scheme.onPrimary,
            accentContainer = scheme.primaryContainer,
            onAccentContainer = scheme.onPrimaryContainer,
            accentBright = KachokPalette.primaryBright,
            accentContainerHigh = KachokPalette.primaryContainerHigh,
            text = scheme.onSurface,
            textMuted = KachokPalette.onSurfaceMuted,
            textFaint = scheme.onSurfaceVariant,
            surface = scheme.surface,
            raised = scheme.surfaceVariant,
            raisedHigh = scheme.surfaceContainerHigh,
            raisedHighest = scheme.surfaceContainerHighest,
            panel = KachokPalette.panel,
            outline = scheme.outline,
            divider = scheme.outlineVariant,
            rowHairline = KachokPalette.rowHairline,
            error = scheme.error,
            onError = scheme.onError,
            errorContainer = scheme.errorContainer,
            warning = KachokWarning.warning,
            onWarning = KachokWarning.onWarning,
            warningContainer = KachokWarning.warningContainer,
            onWarningContainer = KachokWarning.onWarningContainer,
            errorFigure = KachokPalette.errorFigure,
            errorRowTint = KachokPalette.errorRowTint,
            errorTrack = KachokPalette.errorTrack,
            stoppingBar = KachokPalette.stoppingBar,
            selectedFigure = KachokPalette.selectedFigure,
            selectedTrack = KachokPalette.selectedTrack,
            degradedBanner = KachokPalette.degradedBanner,
            warningCard = KachokPalette.warningCard,
            onWarningCard = KachokPalette.onWarningCard,
            neutralCard = KachokPalette.neutralCard,
            tooltip = scheme.inverseSurface,
            onTooltip = scheme.inverseOnSurface,
            footnoteGround = KachokPalette.footnoteGround,
            dialogBorder = KachokPalette.dialogBorder,
        )
    }

/** One type for every seed: the colours move, the faces do not. */
private val MATERIAL_TYPE: KachokType =
    KachokTypography.let {
        KachokType(headline = it.headlineSmall, section = it.labelSmall, action = it.labelLarge)
    }

/** [TextStyle.Default] is the shared `Text`'s way of saying "nobody chose one": Material's root style. */
@Composable
private fun TextStyle.orRoot(): TextStyle = if (this === TextStyle.Default) LocalTextStyle.current else this

/**
 * The whole calibration, in one place, because that is what makes a component nobody listed still
 * come out right (`docs/design/design-tokens.md` §3).
 *
 * Three decisions:
 *
 * 1. **Shapes at 4 dp** — everything, including the dialog, which the design puts at 6 dp against
 *    M3's 28.
 * 2. **No elevation.** Not themed away, simply never used: the design replaces it with 1 dp
 *    `outlineVariant` hairlines, and the one exception is `DropdownMenu` at level 2.
 * 3. **Every vertical dimension cut to a desktop number** — those live with the components that
 *    have them, because a height is not a theme value.
 */
internal val KachokShapes: Shapes =
    Shapes(
        extraSmall = RoundedCornerShape(3.dp),
        small = RoundedCornerShape(4.dp),
        medium = RoundedCornerShape(4.dp),
        large = RoundedCornerShape(6.dp),
        extraLarge = RoundedCornerShape(6.dp),
    )
