package io.github.youndie.kachok.ui.theme.oldge

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import io.github.youndie.kachok.ui.theme.Archivo
import io.github.youndie.kachok.ui.theme.KachokColors
import io.github.youndie.kachok.ui.theme.KachokKit
import io.github.youndie.kachok.ui.theme.KachokType
import io.github.youndie.kachok.ui.theme.SourceSerif
import io.github.youndie.oldge.actions.OldgeButton
import io.github.youndie.oldge.actions.OldgeButtonSize
import io.github.youndie.oldge.actions.OldgeButtonVariant
import io.github.youndie.oldge.forms.OldgeSwitch
import io.github.youndie.oldge.theme.LocalOldgeContentColor
import io.github.youndie.oldge.theme.LocalOldgeTextStyle
import io.github.youndie.oldge.theme.OldgeSkin
import io.github.youndie.oldge.theme.OldgeTheme
import io.github.youndie.oldge.tokens.OldgeColors
import io.github.youndie.oldge.type.OldgeText

/**
 * The window in oldge-ui — the portfolio's mid-2000s design system — in one of its skins (B-160,
 * B-162).
 *
 * **One instance per skin.** [of] hands back the same kit for the same skin, built once for every
 * entry of `OldgeSkin`, so a skin the library adds is a kit here with a version bump and no change
 * in kachok, and the root's recompositions never redraw the window for a skin that did not change.
 * The skin is the kit's whole colour choice: its skins are drawn — gloss, glow and shadows tuned
 * per skin — and a palette generated from a seed would be a skin nobody designed.
 *
 * **What is the library's and what is drawn here.** The settings switch is `OldgeSwitch` and the
 * dialog buttons are `OldgeButton`; the text goes through `OldgeText`, which sets the design
 * system's faces on the baseline a browser would. The library is a *mobile* kit — no table, no
 * column header, no splitter, no dense row — so the rest of the window is the screens' own drawing
 * in this kit's colours, and the row's progress bar is `OldgeProgressBar`'s look (a sunken well,
 * glossy blocks) at the row's height, because the component itself is a 20 dp well under a head
 * line of its own.
 *
 * **This file may name Material 3** — the one other place `make check` allows it. `AppFrame` draws
 * the title bar from `MaterialTheme.colorScheme` itself, so [Root] provides a Material scheme built
 * from this kit's tokens; without it the bar would stay in the Material kit's colours over an
 * oldge window.
 */
internal class OldgeKit private constructor(
    val skin: OldgeSkin,
) : KachokKit {
    override val colors: KachokColors = kachokColorsOf(skin)

    override fun toString(): String = "OldgeKit(${skin.name})"

    companion object {
        /** The library's own default, and the skin the window was drawn in before it was a choice. */
        val DEFAULT_SKIN: OldgeSkin = OldgeSkin.Toxic

        private val BY_SKIN: Map<OldgeSkin, OldgeKit> = OldgeSkin.entries.associateWith { OldgeKit(it) }

        /** The kit in [DEFAULT_SKIN]. */
        val Default: OldgeKit = of(DEFAULT_SKIN)

        /** The kit in [skin]: the same instance every time. */
        fun of(skin: OldgeSkin): OldgeKit = BY_SKIN.getValue(skin)

        /**
         * The skin a stored name means — `OldgeSkin`'s own name for it — or `null` for no name.
         *
         * **A name the library no longer has is its first skin**, never a refusal to start: a file
         * written against one release of oldge-ui is read by the next, and a skin it dropped or
         * renamed says nothing about which of the remaining ones somebody wanted.
         */
        fun skinOf(stored: String?): OldgeSkin? {
            val name = stored?.trim() ?: return null
            return OldgeSkin.entries.firstOrNull { it.name == name } ?: OldgeSkin.entries.first()
        }
    }

    /**
     * The design system's own type styles, by role, at its sizes and weights. Its faces are compose
     * resources and load only inside a composition, which a `val` is not — so each role names the
     * Material kit's family for the same job, and [Text] swaps it for oldge-ui's face as it draws,
     * the way it swaps every style a screen names.
     *
     * No line heights: a screen re-sizes these (`headline.copy(fontSize = 15.sp)`), and a line
     * height set here would stay behind at the old size. [Text] derives one from the size.
     */
    override val type: KachokType =
        KachokType(
            // `heading`: Fira Sans 700 at 20 — a dialog title, the empty state.
            headline = TextStyle(fontFamily = SourceSerif, fontSize = 20.sp, fontWeight = BOLD),
            // `label`'s face and weight, DejaVu Sans Condensed 700 — but at 11 and not its 13: the
            // nine column heads are this role too, and at 13 `RATIO` no longer fits its column.
            section = TextStyle(fontFamily = Archivo, fontSize = 11.sp, fontWeight = BOLD),
            // `button`: DejaVu Sans Condensed 700 at 15 — a command's label.
            action = TextStyle(fontFamily = Archivo, fontSize = 15.sp, fontWeight = BOLD),
        )

    @Composable
    override fun Root(content: @Composable () -> Unit) {
        OldgeTheme(skin = skin) {
            val ui = OldgeTheme.type.body.fontFamily
            val typography = remember(ui) { materialTypographyIn(ui) }
            // `MaterialTheme` provides a ripple as the indication of everything clickable under it,
            // which would replace oldge-ui's press flash and focus ring in the whole window. The
            // library's is taken from outside and put back inside.
            val indication = LocalIndication.current
            MaterialTheme(
                colorScheme = materialSchemeOf(colors, skin.colors),
                typography = typography,
                shapes = SHAPES,
            ) {
                CompositionLocalProvider(LocalIndication provides indication, content = content)
            }
        }
    }

    override val contentColor: Color
        @Composable @ReadOnlyComposable
        get() = LocalOldgeContentColor.current

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
        val laid = oldgeStyle(style, color, textAlign)
        if (onTextLayout == null && laid.lineHeight.isSp) {
            OldgeText(text, modifier, style = laid, maxLines = maxLines, softWrap = softWrap, overflow = overflow)
        } else {
            // `OldgeText` has no layout callback; `PathText` measures through one. Same style, same
            // faces, without the library's baseline nudge of a pixel.
            BasicText(text, modifier, laid, onTextLayout, overflow, softWrap, maxLines)
        }
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
        BasicText(text, modifier, oldgeStyle(style, color, textAlign), onTextLayout, overflow, softWrap, maxLines)
    }

    /**
     * The balloon: the design system's tooltip plate, `balloon` on `on-balloon`. `OldgeTooltip`
     * itself is the hover *wrapper* (a label and the content it hovers over), and the window's hint
     * already has one — `TooltipArea` — so only the plate is taken.
     */
    @Composable
    override fun Tooltip(
        shape: Shape,
        content: @Composable () -> Unit,
    ) {
        Box(
            Modifier
                .shadow(TOOLTIP_SHADOW, shape)
                .background(colors.tooltip, shape)
                .border(HAIRLINE, colors.onTooltip.copy(alpha = TOOLTIP_RIM_ALPHA), shape),
        ) { content() }
    }

    @Composable
    override fun Toggle(
        on: Boolean,
        label: String,
        enabled: Boolean,
        onToggle: () -> Unit,
    ) {
        // The row shows the label, so the switch carries it only as its accessible name — the
        // library's own rule for a switch in a list row.
        OldgeSwitch(
            label = label,
            checked = on,
            onCheckedChange = { onToggle() },
            enabled = enabled,
            showLabel = false,
            modifier = Modifier.semantics { stateDescription = if (on) "on" else "off" },
        )
    }

    /**
     * `OldgeButton` at its dense size. A destructive action is the chrome *Secondary* button and not
     * the lime *Primary*: the design system has no danger variant, and the accent is its "go" — the
     * last colour an irreversible *Remove* should wear. The dialog's own text says what it removes.
     */
    @Composable
    override fun DialogButton(
        label: String,
        primary: Boolean,
        enabled: Boolean,
        destructive: Boolean,
        onClick: () -> Unit,
    ) {
        OldgeButton(
            text = label,
            onClick = onClick,
            modifier = Modifier.semantics { contentDescription = label },
            variant = if (primary && !destructive) OldgeButtonVariant.Primary else OldgeButtonVariant.Secondary,
            size = OldgeButtonSize.Small,
            enabled = enabled,
        )
    }

    /**
     * `OldgeProgressBar`'s look at a row's height: a sunken well in the row's track colour with the
     * skin's edge round it, and the fill as glossy 8 dp blocks with 2 dp of the well between them —
     * in the colour the row's state chose, so an error row's bar is still red and a stopping one
     * still dim. Not animated: the figure beside it changes once a second, and the library's own bar
     * eases into each new value.
     */
    @Composable
    override fun ProgressBar(
        fraction: Float,
        track: Color,
        bar: Color,
        modifier: Modifier,
    ) {
        val shape = RoundedCornerShape(WELL_RADIUS)
        Box(
            modifier
                .height(WELL)
                .background(track, shape)
                .border(HAIRLINE, colors.outline.copy(alpha = WELL_RIM_ALPHA), shape)
                .padding(WELL_PADDING)
                .clip(RoundedCornerShape(FILL_RADIUS))
                .drawBehind {
                    val width = (size.width * fraction.coerceIn(0f, 1f)).toInt().toFloat()
                    if (width <= 0f) return@drawBehind
                    drawRoundRect(
                        Brush.verticalGradient(
                            0f to lerp(bar, Color.White, GLOSS_TOP),
                            HALF to bar,
                            1f to lerp(bar, Color.Black, GLOSS_BOTTOM),
                        ),
                        size = Size(width, size.height),
                        cornerRadius = CornerRadius(FILL_RADIUS.toPx()),
                    )
                    val block = BLOCK.toPx()
                    val gap = BLOCK_GAP.toPx()
                    var x = block
                    while (x < width) {
                        drawRect(track, Offset(x, 0f), Size(gap, size.height))
                        x += block + gap
                    }
                },
        )
    }
}

/**
 * A style a screen names, in this kit's faces.
 *
 * The screens' styles name the Material kit's families; each is replaced by the design system's
 * face for the same job, and the sizes stay the screen's. **Which face draws Cyrillic** — the
 * question oldge-ui's own research put to this item — is answered by the replacement:
 *
 * - Archivo (labels, names, the chrome) becomes DejaVu Sans Condensed, oldge-ui's `ui` face, which
 *   covers Russian; Archivo has no Cyrillic at all, so under the Material kit a Russian torrent
 *   name is drawn by whatever fallback the platform has.
 * - Source Serif (titles) becomes Fira Sans Bold, the `title` face, which covers Cyrillic.
 * - JetBrains Mono (every figure, hash and verbatim error) stays. The design system's mono is Share
 *   Tech Mono, whose LCD look is the obvious choice for a figure — and which has no Cyrillic, and a
 *   verbatim error from a Russian-locale system is Russian. oldge-ui joins it with PT Mono inside
 *   its own components only; its public `OldgeText` does not.
 *
 * A line height is set where the screen set none, in sp, because `OldgeText` places text on the
 * CSS baseline of its line and a line it cannot measure is one it cannot place.
 */
@Composable
@ReadOnlyComposable
private fun oldgeStyle(
    style: TextStyle,
    color: Color,
    textAlign: TextAlign?,
): TextStyle {
    val type = OldgeTheme.type
    val base = if (style === TextStyle.Default) LocalOldgeTextStyle.current else style
    val family =
        when (base.fontFamily) {
            null, Archivo -> type.body.fontFamily
            SourceSerif -> type.heading.fontFamily
            else -> base.fontFamily
        }
    val size = if (base.fontSize.isSpecified) base.fontSize else type.body.fontSize
    val line =
        when {
            base.lineHeight.isSp -> base.lineHeight
            size.isSp -> (size.value * LINE_RATIO).sp
            else -> TextUnit.Unspecified
        }
    return base.copy(
        fontFamily = family,
        fontSize = size,
        lineHeight = line,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        color =
            when {
                color.isSpecified -> color
                base.color.isSpecified -> base.color
                else -> LocalOldgeContentColor.current
            },
        textAlign = textAlign ?: base.textAlign,
    )
}

/**
 * Every role of the window from the skin's tokens.
 *
 * Where the design system has a token for the job it is used as it is; where it has none — the
 * middle text level, the row tints, the cards — the value is mixed from tokens it does have, so a
 * skin with other tokens gives other values without a second table (B-162). The warning family
 * stays amber and the error family red because their sources are the skin's own `warning` and
 * `danger`.
 */
internal fun kachokColorsOf(skin: OldgeSkin): KachokColors =
    skin.colors.let { o ->
        KachokColors(
            accent = o.accent,
            onAccent = o.onAccent,
            accentContainer = o.select,
            onAccentContainer = o.onSelect,
            accentBright = o.accentHi,
            accentContainerHigh = o.accentLo.copy(alpha = SELECTED_HAIRLINE).compositeOver(o.select),
            text = o.ink,
            textMuted = lerp(o.ink, o.inkMuted, HALF),
            textFaint = o.inkMuted,
            surface = o.surface,
            raised = o.surface2,
            raisedHigh = lerp(o.surface2, o.line, STEP),
            raisedHighest = lerp(o.surface2, o.line, 2 * STEP),
            panel = o.well,
            outline = o.edge,
            divider = o.line,
            rowHairline = lerp(o.surface, o.line, HALF),
            error = o.danger,
            onError = o.ground,
            errorContainer = o.danger.copy(alpha = CONTAINER).compositeOver(o.surface),
            warning = o.warning,
            onWarning = o.ground,
            warningContainer = o.warning.copy(alpha = CONTAINER).compositeOver(o.surface),
            onWarningContainer = o.warning,
            errorFigure = lerp(o.danger, o.ink, HALF),
            errorRowTint = o.danger.copy(alpha = TINT).compositeOver(o.surface),
            errorTrack = o.danger.copy(alpha = CONTAINER).compositeOver(o.well),
            stoppingBar = lerp(o.edge, o.surface, HALF),
            selectedFigure = lerp(o.onSelect, o.accent, STEP),
            selectedTrack = lerp(o.select, o.well, HALF),
            degradedBanner = o.danger.copy(alpha = TINT).compositeOver(o.surface),
            warningCard = o.warning.copy(alpha = TINT).compositeOver(o.surface),
            onWarningCard = lerp(o.warning, o.ink, HALF),
            neutralCard = o.surface2,
            tooltip = o.balloon,
            onTooltip = o.onBalloon,
            footnoteGround = if (skin == OldgeSkin.Toxic) TOXIC_FOOTNOTE_GROUND else footnoteGroundOf(o),
            dialogBorder = if (skin == OldgeSkin.Toxic) TOXIC_DIALOG_BORDER else dialogBorderOf(o),
        )
    }

/**
 * The settings footnote's ground: the skin's `surface`, a step towards its `ground`.
 *
 * **Toxic keeps the literal it had** ([TOXIC_FOOTNOTE_GROUND]). Before B-161 made it a role it was a
 * colour written in the screen — the Material design's teal-grey `#111716` — and no Toxic token and
 * no mix of two is that grey: Toxic's grounds and lines are green, the literal has almost no
 * chroma. What *can* be carried to the other skins is its place between their tokens, which is what this is: the
 * literal sits at [FOOTNOTE_TOWARDS_GROUND] of the way from Toxic's `surface` to its `ground` in
 * lightness (Oklab L, the space `lerp` mixes in), and every other skin's footnote sits at the same
 * place between its own two. `OldgeSkinsTest` holds the fraction to that claim.
 */
internal fun footnoteGroundOf(o: OldgeColors): Color = lerp(o.surface, o.ground, FOOTNOTE_TOWARDS_GROUND)

/**
 * A dialog's border: the skin's `line`, a step towards its `edge` — by the same reasoning as
 * [footnoteGroundOf], from the Material design's `#303836` ([TOXIC_DIALOG_BORDER]), which Toxic
 * keeps and which sits at [DIALOG_BORDER_TOWARDS_EDGE] of the way from Toxic's `line` to its `edge`.
 */
internal fun dialogBorderOf(o: OldgeColors): Color = lerp(o.line, o.edge, DIALOG_BORDER_TOWARDS_EDGE)

/** The Material design's footnote ground, which the Toxic skin has drawn since B-160. */
internal val TOXIC_FOOTNOTE_GROUND: Color = Color(0xFF111716)

/** The Material design's dialog border, likewise. */
internal val TOXIC_DIALOG_BORDER: Color = Color(0xFF303836)

/** Where [TOXIC_FOOTNOTE_GROUND]'s lightness sits from Toxic's `surface` to its `ground`: 0.070. */
internal const val FOOTNOTE_TOWARDS_GROUND: Float = 0.07f

/** Where [TOXIC_DIALOG_BORDER]'s lightness sits from Toxic's `line` to its `edge`: 0.021. */
internal const val DIALOG_BORDER_TOWARDS_EDGE: Float = 0.02f

/**
 * The Material scheme `AppFrame` and anything left over in Material read, from the same tokens.
 *
 * The title bar is the skin's bezel — the design system's frame round the device — and its title
 * `on-bezel`, so the window's own frame is the one part drawn in the frame's colours.
 */
private fun materialSchemeOf(
    c: KachokColors,
    o: OldgeColors,
) = darkColorScheme(
    primary = c.accent,
    onPrimary = c.onAccent,
    primaryContainer = c.accentContainer,
    onPrimaryContainer = c.onAccentContainer,
    secondary = c.textMuted,
    onSecondary = c.surface,
    tertiary = c.warning,
    onTertiary = c.onWarning,
    error = c.error,
    onError = c.onError,
    errorContainer = c.errorContainer,
    onErrorContainer = c.errorFigure,
    background = c.surface,
    onBackground = c.text,
    surface = c.surface,
    onSurface = c.text,
    surfaceVariant = o.bezel,
    onSurfaceVariant = o.onBezel,
    surfaceContainer = c.raised,
    surfaceContainerHigh = c.raisedHigh,
    surfaceContainerHighest = c.raisedHighest,
    outline = c.outline,
    outlineVariant = c.divider,
    inverseSurface = c.tooltip,
    inverseOnSurface = c.onTooltip,
)

/** Every Material type style in the design system's `ui` face, for the title bar's `labelLarge`. */
private fun materialTypographyIn(ui: FontFamily?): Typography =
    Typography().run {
        copy(
            displayLarge = displayLarge.copy(fontFamily = ui),
            displayMedium = displayMedium.copy(fontFamily = ui),
            displaySmall = displaySmall.copy(fontFamily = ui),
            headlineLarge = headlineLarge.copy(fontFamily = ui),
            headlineMedium = headlineMedium.copy(fontFamily = ui),
            headlineSmall = headlineSmall.copy(fontFamily = ui),
            titleLarge = titleLarge.copy(fontFamily = ui),
            titleMedium = titleMedium.copy(fontFamily = ui),
            titleSmall = titleSmall.copy(fontFamily = ui),
            bodyLarge = bodyLarge.copy(fontFamily = ui),
            bodyMedium = bodyMedium.copy(fontFamily = ui),
            bodySmall = bodySmall.copy(fontFamily = ui),
            labelLarge = labelLarge.copy(fontFamily = ui),
            labelMedium = labelMedium.copy(fontFamily = ui),
            labelSmall = labelSmall.copy(fontFamily = ui),
        )
    }

/** oldge-ui's `radius-sm`, everywhere: the design system's controls are 4 px boxes. */
private val SHAPES: Shapes =
    Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(4.dp),
        medium = RoundedCornerShape(4.dp),
        large = RoundedCornerShape(8.dp),
        extraLarge = RoundedCornerShape(8.dp),
    )

private val BOLD = FontWeight.Bold

/** The design system's own ratio of line to size for its `ui` text, 20 / 15 rounded down. */
private const val LINE_RATIO = 1.25f

private const val HALF = 0.5f

/** One step from one ground towards the next, for the two raised levels the skin has no token for. */
private const val STEP = 0.35f

/** A tint that colours a whole row or banner and must leave its text readable. */
private const val TINT = 0.12f

/** A container that is a colour's own ground, a step stronger than a tint. */
private const val CONTAINER = 0.28f

/** How much of `accent-lo` a selected row's hairline carries over the selection. */
private const val SELECTED_HAIRLINE = 0.55f

private const val GLOSS_TOP = 0.35f

private const val GLOSS_BOTTOM = 0.25f

private const val WELL_RIM_ALPHA = 0.6f

private const val TOOLTIP_RIM_ALPHA = 0.35f

private val HAIRLINE = 1.dp

/** The row's well: 10 dp, where the library's own is 20 under a head line the row does not have. */
private val WELL = 10.dp

private val WELL_PADDING = 2.dp

private val WELL_RADIUS = 3.dp

private val FILL_RADIUS = 1.dp

private val BLOCK = 8.dp

private val BLOCK_GAP = 2.dp

private val TOOLTIP_SHADOW = 4.dp
