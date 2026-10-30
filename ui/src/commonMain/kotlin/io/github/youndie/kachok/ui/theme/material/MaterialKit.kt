package io.github.youndie.kachok.ui.theme.material

import androidx.compose.foundation.shape.RoundedCornerShape
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
 * The design as it was drawn: a dark Material 3 scheme, read off the design's swatch row, with the
 * roles Material does not ship beside it.
 *
 * **One of the two places in the module that name `androidx.compose.material3`** — `make check`
 * greps for it everywhere else (B-159); the other is the oldge kit, for the scheme `AppFrame` reads
 * (B-160). A screen that reached for `MaterialTheme` directly would draw the
 * same thing under this kit and the wrong thing under any other, and nothing would say so until
 * somebody switched.
 */
internal object MaterialKit : KachokKit {
    override val colors: KachokColors =
        KachokDarkColors.let { scheme ->
            KachokColors(
                accent = scheme.primary,
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
            )
        }

    override val type: KachokType =
        KachokTypography.let {
            KachokType(headline = it.headlineSmall, section = it.labelSmall, action = it.labelLarge)
        }

    @Composable
    override fun Root(content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = KachokDarkColors,
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
