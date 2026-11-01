package io.github.youndie.kachok.ui.theme.oldge

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import io.github.youndie.kachok.ui.icons.Icons
import io.github.youndie.kachok.ui.theme.Archivo
import io.github.youndie.kachok.ui.theme.KachokColors
import io.github.youndie.kachok.ui.theme.KachokKit
import io.github.youndie.kachok.ui.theme.KachokType
import io.github.youndie.kachok.ui.theme.SourceSerif
import io.github.youndie.kachok.ui.theme.material.MaterialSegmented
import io.github.youndie.oldge.actions.OldgeButton
import io.github.youndie.oldge.actions.OldgeButtonSize
import io.github.youndie.oldge.actions.OldgeButtonVariant
import io.github.youndie.oldge.actions.OldgeIconButton
import io.github.youndie.oldge.actions.OldgeSegment
import io.github.youndie.oldge.actions.OldgeSegmented
import io.github.youndie.oldge.containers.OldgeDialog
import io.github.youndie.oldge.feedback.OldgeProgressWell
import io.github.youndie.oldge.forms.OldgeSearchBar
import io.github.youndie.oldge.forms.OldgeSwitch
import io.github.youndie.oldge.forms.OldgeTextField
import io.github.youndie.oldge.icons.OldgeIcons
import io.github.youndie.oldge.navigation.OldgeTab
import io.github.youndie.oldge.navigation.OldgeTabs
import io.github.youndie.oldge.theme.LocalOldgeContentColor
import io.github.youndie.oldge.theme.LocalOldgeTextStyle
import io.github.youndie.oldge.theme.OldgeDensity
import io.github.youndie.oldge.theme.OldgeSkin
import io.github.youndie.oldge.theme.OldgeStrings
import io.github.youndie.oldge.theme.OldgeTheme
import io.github.youndie.oldge.tokens.OldgeColors
import io.github.youndie.oldge.tokens.OldgeRadii
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
 * in this kit's colours, and the row's progress bar is `OldgeProgressWell`, the bar's well without
 * its head line.
 *
 * **A pointer window, in English.** [Root] gives the library `OldgeDensity.Pointer`, so its controls
 * are drawn at a mouse's sizes and not a finger's 44 dp, and [STRINGS], so the names it gives its
 * own orbs are English (B-182).
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
         * The skin a stored value means, or `null` for no value. The value is the skin's `id`, the
         * library's key for persisting it (B-182). A build before B-182 stored the enum's `name`,
         * which is read as well, so a settings file from it keeps its choice.
         *
         * **A value no skin has is the library's first skin**, never a refusal to start: a file
         * written by a newer release of oldge-ui says nothing about which of the skins this one has
         * somebody wanted.
         */
        fun skinOf(stored: String?): OldgeSkin? {
            val value = stored?.trim() ?: return null
            return OldgeSkin.byId(value)
                ?: OldgeSkin.entries.firstOrNull { it.name == value }
                ?: OldgeSkin.entries.first()
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
        OldgeTheme(skin = skin, strings = STRINGS, density = OldgeDensity.Pointer) {
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
        if (laid.lineHeight.isSp) {
            OldgeText(
                text,
                modifier,
                style = laid,
                maxLines = maxLines,
                softWrap = softWrap,
                overflow = overflow,
                onTextLayout = onTextLayout,
            )
        } else {
            // `OldgeText` places text on the baseline of a line height in sp, and a style without
            // one is one it cannot place. Same style, same faces, without the library's nudge.
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
     * `OldgeButton` at its dense size. A destructive action is *Danger* with the `trash` icon, the
     * design system's confirm of what cannot be undone, and never the lime *Primary*, which is its
     * "go" (B-182). The dialog's own text says what it removes.
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
            variant =
                when {
                    destructive -> OldgeButtonVariant.Danger
                    primary -> OldgeButtonVariant.Primary
                    else -> OldgeButtonVariant.Secondary
                },
            size = OldgeButtonSize.Small,
            enabled = enabled,
            icon = if (destructive) OldgeIcons.Trash else null,
        )
    }

    /**
     * `OldgeProgressWell`, the library's bar without its head line, at the row's 10 dp: the sunken
     * well and the glossy blocks, the fill in the colour the row's state chose, so an error row's bar
     * is still red and a stopping one still dim (B-182). [track] is the Material kit's: the well is
     * the skin's own. Named by the row, as the library asks of a bar in a row.
     */
    @Composable
    override fun ProgressBar(
        fraction: Float,
        name: String,
        track: Color,
        bar: Color,
        modifier: Modifier,
    ) {
        OldgeProgressWell(fraction, name, modifier.height(WELL), fill = bar)
    }

    /**
     * `OldgeIconButton` at the pointer density's small size, a 28 dp square: the design's own control.
     * A toggle is the library's `selected` button, latched in the accent when it is on, and not the
     * screen's one *Primary* (B-182).
     */
    @Composable
    override fun ToolButton(
        glyph: String,
        label: String,
        enabled: Boolean,
        active: Boolean?,
        onClick: () -> Unit,
    ) {
        OldgeIconButton(
            icon = toolIconOf(glyph),
            contentDescription = label,
            onClick = onClick,
            variant = OldgeButtonVariant.Secondary,
            size = OldgeButtonSize.Small,
            enabled = enabled,
            selected = active,
        )
    }

    /**
     * `OldgeButton`, *Primary* — the screen's one main action, the library's own rule for it. In a
     * narrow bar the glyph alone: at 600 dp the eight buttons and the label do not fit.
     */
    @Composable
    override fun ToolMainButton(
        glyph: String,
        label: String,
        compact: Boolean,
        onClick: () -> Unit,
    ) {
        if (compact) {
            OldgeIconButton(
                icon = toolIconOf(glyph),
                contentDescription = label,
                onClick = onClick,
                variant = OldgeButtonVariant.Primary,
                size = OldgeButtonSize.Small,
            )
        } else {
            OldgeButton(
                text = label,
                onClick = onClick,
                modifier = Modifier.semantics { contentDescription = label },
                variant = OldgeButtonVariant.Primary,
                size = OldgeButtonSize.Small,
                icon = toolIconOf(glyph),
            )
        }
    }

    /**
     * `OldgeSearchBar`, 32 dp at the pointer density: the sunken capsule, the magnifier and the round
     * clear button, named *Clear the filter* by [STRINGS]. Narrow, 150 dp and not the Material field's 110: the
     * capsule's 16 sp text, its magnifier and its clear orb left one letter of a filter visible at
     * 110, and the bar has the room.
     */
    @Composable
    override fun FilterField(
        text: String,
        placeholder: String,
        narrow: Boolean,
        onFilter: (String) -> Unit,
    ) {
        OldgeSearchBar(
            modifier = Modifier.width(if (narrow) NARROW_FILTER else FILTER),
            value = text,
            onValueChange = onFilter,
            placeholder = placeholder,
        )
    }

    /**
     * `OldgeButton`, *Secondary* — the library's banners carry chrome buttons, because a band's
     * colours are not the skin's (its README). The pointer density's small 28 dp, inside the 32 dp
     * band (B-182).
     */
    @Composable
    override fun BannerButton(
        label: String,
        onClick: () -> Unit,
    ) {
        OldgeButton(
            text = label,
            onClick = onClick,
            variant = OldgeButtonVariant.Secondary,
            size = OldgeButtonSize.Small,
        )
    }

    /**
     * `OldgeTabs`, the design system's property-sheet tabs, with no panel of its own: the details
     * panel draws the page under them. The current tab stands 44 dp and runs into the line under the
     * strip, which is why the strip's height is the kit's.
     */
    @Composable
    override fun Tabs(
        name: String,
        labels: List<String>,
        selected: Int,
        onSelect: (Int) -> Unit,
    ) {
        val items = remember(labels) { labels.mapIndexed { index, label -> OldgeTab(index.toString(), label) } }
        OldgeTabs(
            items = items,
            value = selected.toString(),
            onChange = { onSelect(it.toInt()) },
            label = name,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    /**
     * `OldgeTextField`: the sunken well, in the design system's 16 sp `body`, with no label above it,
     * because the row already shows the same words on its left. The label still names the field.
     *
     * **A changed value is ringed in `accent-ink`**, the Material field's "you changed this", drawn
     * over the well's own edge, because the component's border knows only `edge` and `danger`.
     *
     * The well is 32 dp at the pointer density, where the Material field is 30. The width grows for
     * [words] in the larger face, which at the Material field's width left `no limit` cut to `no li`.
     * An absent value is not dimmed — the text's colour is the field's — and is aligned to the
     * start, as every field of the design system is.
     */
    @Composable
    override fun SettingField(
        label: String,
        value: String,
        width: Dp,
        words: String?,
        changed: Boolean,
        absent: Boolean,
        onValueChange: (String) -> Unit,
    ) {
        val wide = words?.let { textWidth(it, OldgeTheme.type.body) + FIELD_INSET * 2 + CARET } ?: 0.dp
        OldgeTextField(
            label = label,
            value = value,
            onValueChange = onValueChange,
            modifier =
                Modifier
                    .width(maxOf(width, wide))
                    .changedRing(changed, colors.accentText),
            showLabel = false,
        )
    }

    /**
     * `OldgeTextField` with the folder icon, and *Browse…* as `OldgeButton`, *Secondary*, at the
     * well's 32 dp. The directory is picked and never typed, so the field is read-only: its path can
     * be selected and copied, and nothing typed into it is dropped on the floor. Its label is the
     * row's, as [SettingField]'s is.
     */
    @Composable
    override fun FolderField(
        label: String,
        path: String,
        changed: Boolean,
        onBrowse: () -> Unit,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(GAP), verticalAlignment = Alignment.CenterVertically) {
            OldgeTextField(
                label = label,
                value = path,
                onValueChange = {},
                modifier =
                    Modifier
                        .width(PATH)
                        .changedRing(changed, colors.accentText),
                icon = OldgeIcons.Folder,
                readOnly = true,
                showLabel = false,
            )
            OldgeButton(
                text = "Browse…",
                onClick = onBrowse,
                modifier = Modifier.semantics { contentDescription = "Browse" },
                variant = OldgeButtonVariant.Secondary,
                size = OldgeButtonSize.Medium,
            )
        }
    }

    /**
     * `OldgeSegmented`, set to 32 dp from outside, level with the settings fields: its own segment is
     * 40 inside a 2 dp rim at either density. As wide as its widest label twice over: its segments
     * share the width they are given, and a settings row gives all of it. A choice that cannot be
     * changed is drawn disabled (B-182).
     *
     * The component takes two to four options and throws on any other count; the screen's drawing
     * in this kit's colours takes the rest, which today is nothing (the kits are two, and the scheme
     * styles are the Material kit's).
     */
    @Composable
    override fun Segmented(
        name: String,
        labels: List<String>,
        selected: Int,
        enabled: Boolean,
        onSelect: (Int) -> Unit,
    ) {
        if (labels.size !in SEGMENTS) {
            MaterialSegmented(name, labels, selected, enabled, onSelect)
            return
        }
        val options = remember(labels) { labels.mapIndexed { index, label -> OldgeSegment(index, label) } }
        OldgeSegmented(
            options = options,
            selected = selected,
            onSelect = { if (it != selected) onSelect(it) },
            label = name,
            modifier = Modifier.height(SEGMENTED).width(IntrinsicSize.Max),
            enabled = enabled,
        )
    }

    /** `OldgeButton`, *Primary*, medium: the empty window's one main action, with *Plus*. */
    @Composable
    override fun InvitationButton(
        glyph: String,
        label: String,
        onClick: () -> Unit,
    ) {
        OldgeButton(
            text = label,
            onClick = onClick,
            variant = OldgeButtonVariant.Primary,
            size = OldgeButtonSize.Medium,
            icon = toolIconOf(glyph),
        )
    }

    /**
     * `OldgeDialog`, not modal: the window — the skin's bezel, the title in its bar — in place, over
     * the screen's own scrim. [width] is its `maxWidth`, where the library's own is the design
     * system's 358 dp phone width; until B-182 it was forced from outside, ahead of that cap. No close
     * orb: the dialog ends with *Cancel*, the design's. The body is padded 16 dp by the window, so
     * the screen's side margin is 4 — 20 in all, the design's.
     */
    @Composable
    override fun DialogFrame(
        title: String,
        width: Dp,
        modifier: Modifier,
        content: @Composable (edge: Dp) -> Unit,
    ) {
        OldgeDialog(title = title, modifier = modifier, maxWidth = width) {
            Column(Modifier.fillMaxWidth()) { content(DIALOG_EDGE) }
        }
    }

    /**
     * `OldgeTextField` under its own label, the folder icon in it, and *Browse…* as `OldgeButton`,
     * *Secondary*, at the well's 32 dp beside it. The note is the field's help line, drawn under the
     * row in the help line's style rather than passed as `help`: inside the field it would make the
     * field taller than the well, and the button would no longer stand level with it. The field is
     * read-only, as [FolderField]'s is.
     */
    @Composable
    override fun SaveToField(
        label: String,
        path: String,
        note: String,
        onBrowse: () -> Unit,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HELP_GAP)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(GAP)) {
                OldgeTextField(
                    label = label,
                    value = path,
                    onValueChange = {},
                    modifier = Modifier.weight(1f),
                    icon = OldgeIcons.Folder,
                    readOnly = true,
                )
                OldgeButton(
                    text = "Browse…",
                    onClick = onBrowse,
                    variant = OldgeButtonVariant.Secondary,
                    size = OldgeButtonSize.Medium,
                )
            }
            OldgeText(note, style = OldgeTheme.type.caption.copy(color = OldgeTheme.colors.inkMuted))
        }
    }
}

/** [text]'s width in [style], for a field that has to be wide enough for its words. */
@Composable
private fun textWidth(
    text: String,
    style: TextStyle,
): Dp {
    val measurer = rememberTextMeasurer()
    return with(LocalDensity.current) {
        measurer
            .measure(text, style)
            .size.width
            .toDp()
    }
}

/**
 * The names oldge-ui gives its own orbs and actions, in the window's English (B-182). The library's
 * defaults are the design system's Russian.
 */
private val STRINGS =
    OldgeStrings(
        close = "Close",
        // The window has one search bar, the filter, and the Material kit's clear button says this.
        clear = "Clear the filter",
        showPassword = "Show password",
        hidePassword = "Hide password",
        progress = "Progress",
        stepDone = "done",
        page = { number, count -> "Page $number of $count" },
        day = { date -> date.toString() },
    )

/** The changed field's ring, over the well's edge and in its corner. */
private fun Modifier.changedRing(
    changed: Boolean,
    ink: Color,
): Modifier = if (changed) border(HAIRLINE, ink, RoundedCornerShape(OldgeRadii.xs)) else this

/**
 * The library's icon for a toolbar glyph (B-176). The toolbar's glyphs are Material Symbols, and
 * oldge-ui draws its own set; each control takes the nearest in meaning. A glyph with no entry gets
 * *More*, and `OldgeToolbarTest` fails on every toolbar glyph that has none.
 */
internal fun toolIconOf(glyph: String): ImageVector = TOOL_ICONS[glyph] ?: OldgeIcons.More

internal val TOOL_ICONS: Map<String, ImageVector> by lazy {
    mapOf(
        Icons.ADD to OldgeIcons.Plus,
        Icons.LINK to OldgeIcons.Attach,
        Icons.PAUSE to OldgeIcons.Pause,
        Icons.PLAY_ARROW to OldgeIcons.Play,
        Icons.DELETE to OldgeIcons.Trash,
        Icons.RESTART_ALT to OldgeIcons.Refresh,
        // Re-announce: the torrent tells its trackers again — sent out, as *Share* sends.
        Icons.CAMPAIGN to OldgeIcons.Share,
        // The details panel: what there is to know about the selected torrent.
        Icons.RIGHT_PANEL_OPEN to OldgeIcons.Info,
        // Settings: `tune` is sliders, and so is `levels`.
        Icons.TUNE to OldgeIcons.Levels,
    )
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
            // `accent-ink` in every skin, the owner's decision (B-164, 2026-10-30) — not only where
            // `accent` fails a contrast. Toxic's and Media's inks are a step brighter than their
            // fills, Crystal's a dark green where its fill is a lime that white swallows.
            accentText = o.accentInk,
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
            // From the skin's own vocabulary (B-168, the owner's decision): the settings footnote on the
            // raised `surface2` the library puts secondary panels on, a dialog's border on `edge`, the
            // line the library draws around a framed thing. Until B-168 these were the Material
            // design's greys carried over, which every skin drew almost as its surface and its line.
            footnoteGround = o.surface2,
            dialogBorder = o.edge,
        )
    }

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

private const val TOOLTIP_RIM_ALPHA = 0.35f

private val HAIRLINE = 1.dp

/** The row's well: 10 dp, the compact well's own, where the bar's is 20 under its head line. */
private val WELL = 10.dp

private val TOOLTIP_SHADOW = 4.dp

/** The Material field's width, wide; see [OldgeKit.FilterField] for the narrow one. */
private val FILTER = 220.dp

private val NARROW_FILTER = 150.dp

/** `OldgeTextField`'s well, from its edge to its text: the 1 dp border and `space-3`. */
private val FIELD_INSET = 13.dp

/** The caret beside the last character, so a full field does not scroll by a pixel. */
private val CARET = 2.dp

/**
 * A settings directory: 180, where the Material field is 210, because *Browse…* at the well's
 * 44 dp is the wider button by about as much, and at 210 the row's label wrapped. The well's 16 sp
 * `body` and its 20 dp icon show about fourteen characters of a path, cut at the end: the component
 * has no middle ellipsis, which the Material field's `PathText` has.
 */
private val PATH = 180.dp

/** Between a field and its button: `space-2`. */
private val GAP = 8.dp

/** `.og-field { gap: 6px }`, between a field and its help line. */
private val HELP_GAP = 6.dp

/** The settings choice's height: the pointer density's text field and medium button. */
private val SEGMENTED = 32.dp

/** What `OldgeSegmented` takes. */
private val SEGMENTS = 2..4

/** A dialog body's side margin inside `OldgeDialog`'s own 16 dp: 20 in all, the design's. */
private val DIALOG_EDGE = 4.dp
