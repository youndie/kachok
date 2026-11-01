package io.github.youndie.kachok.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import io.github.youndie.kachok.ui.theme.material.MaterialColours
import io.github.youndie.kachok.ui.theme.material.MaterialKit
import io.github.youndie.kachok.ui.theme.oldge.OldgeKit
import io.github.youndie.oldge.theme.OldgeSkin

/**
 * Every colour a screen asks for, by what it is for rather than by Material's name for it.
 *
 * **The names are the design's roles, not a kit's.** `text`, `textMuted` and `textFaint` are the
 * three text levels the design has where Material has two (`docs/design/design-tokens.md` §1);
 * `accent` is what Material calls `primary`, and a kit that has no "primary" still has an accent.
 * The row- and card-specific values that used to live in a palette object beside Material's
 * scheme are here too, because a second kit has to answer for them just the same — a recoloured
 * scheme with the old error row tint under it is two designs in one window
 * ([B-159](../../../../../../../../docs/backlog/B-159-the-screens-draw-through-a-kit-not-through-material.md)).
 */
@Immutable
internal class KachokColors(
    /** The accent as a *fill* — a button, a bar, a selected segment, a toggle that is on. */
    val accent: Color,
    /**
     * The accent as *ink*: text, a glyph, a figure, a thin outline or a caret, drawn on the window's
     * grounds (B-164). On a dark design the two are the same colour; on a light skin the fill's lime
     * is about 1.6:1 against white, and a word written in it cannot be read. The Material kits fill
     * it with [accent]; the oldge kit with its skin's `accent-ink`, which the design system has for
     * exactly this.
     */
    val accentText: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    /** The content of a control drawn *on* [accentContainer]: the Add button, a toggle that is on. */
    val accentBright: Color,
    /** A selected row's hairline, and the Add button under the pointer. */
    val accentContainerHigh: Color,
    // The three text levels: the headline figure, a figure that is merely true, a zero or absence.
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    // Grounds, from the window down to the recessed details panel.
    val surface: Color,
    val raised: Color,
    val raisedHigh: Color,
    val raisedHighest: Color,
    val panel: Color,
    // Lines. The design has no elevation, so these are what separates everything.
    val outline: Color,
    val divider: Color,
    val rowHairline: Color,
    // Error and warning — warning exists for checking and stopping only.
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    // Rows in a state that colours them.
    val errorFigure: Color,
    val errorRowTint: Color,
    val errorTrack: Color,
    val stoppingBar: Color,
    val selectedFigure: Color,
    val selectedTrack: Color,
    // Banners, cards and the hover hint.
    val degradedBanner: Color,
    val warningCard: Color,
    val onWarningCard: Color,
    val neutralCard: Color,
    val tooltip: Color,
    val onTooltip: Color,
    /**
     * The settings screen's footnote ground and a dialog's border — two values the screens wrote as
     * literals until a seed could recolour everything around them (B-161).
     */
    val footnoteGround: Color,
    val dialogBorder: Color,
)

/**
 * The type roles a screen names. Everything else is written as a `TextStyle` beside the component
 * that uses it, because a size is the component's and not the theme's.
 */
@Immutable
internal class KachokType(
    /** Dialog titles and the empty state. */
    val headline: TextStyle,
    /** Section heads: `DOWNLOADS`, `IDENTITY`. */
    val section: TextStyle,
    /** A command's label where it is not drawn by a button of its own. */
    val action: TextStyle,
)

/**
 * One way of drawing the window: its tokens, and the few components it has to draw itself.
 *
 * The screens draw almost everything on `foundation` already — the table, the fields, the toolbar
 * — and read their colours from [colors], so a kit is mostly its tokens. What is here as a function
 * is what a screen cannot draw without a library's own component: the text (whose default style and
 * colour the kit's root sets) and the hover hint's raised surface — and, since
 * [B-160](../../../../../../../../docs/backlog/B-160-oldge-ui-is-a-kit-the-window-can-wear.md), each
 * component the second kit's library draws differently from a recoloured box: the settings toggle,
 * the dialog button and the row's progress bar. A component joins this list when a kit needs it to,
 * not before.
 *
 * **No defaults on a member.** An abstract composable cannot have them, so the screens call a
 * top-level wrapper that does, as `Text` does.
 */
@Stable
internal interface KachokKit {
    val colors: KachokColors
    val type: KachokType

    /** What the kit's library needs at the root, around the whole window. */
    @Composable
    fun Root(content: @Composable () -> Unit)

    /** The colour text and glyphs take when nobody names one. */
    val contentColor: Color
        @Composable @ReadOnlyComposable
        get

    @Composable
    fun Text(
        text: String,
        modifier: androidx.compose.ui.Modifier,
        color: Color,
        style: TextStyle,
        textAlign: TextAlign?,
        overflow: TextOverflow,
        softWrap: Boolean,
        maxLines: Int,
        onTextLayout: ((TextLayoutResult) -> Unit)?,
    )

    @Composable
    fun Text(
        text: AnnotatedString,
        modifier: androidx.compose.ui.Modifier,
        color: Color,
        style: TextStyle,
        textAlign: TextAlign?,
        overflow: TextOverflow,
        softWrap: Boolean,
        maxLines: Int,
        onTextLayout: (TextLayoutResult) -> Unit,
    )

    /** The raised, shadowed surface a hover hint sits on. */
    @Composable
    fun Tooltip(
        shape: Shape,
        content: @Composable () -> Unit,
    )

    /**
     * An on/off switch with no label of its own: the row it sits in shows [label], and the switch
     * carries it as its accessible name together with which way it is.
     */
    @Composable
    fun Toggle(
        on: Boolean,
        label: String,
        enabled: Boolean,
        onToggle: () -> Unit,
    )

    /** The button a dialog ends with; [destructive] is an appearance, never a colour argument. */
    @Composable
    fun DialogButton(
        label: String,
        primary: Boolean,
        enabled: Boolean,
        destructive: Boolean,
        onClick: () -> Unit,
    )

    /**
     * A row's progress bar, [fraction] of the way along, in the two colours the row's state chose
     * (`RowColors.kt` decides those, so a kit cannot get a state's colour wrong — only its shape).
     * [name] is the row's, for a kit whose bar is a node a screen reader names (B-182).
     */
    @Composable
    fun ProgressBar(
        fraction: Float,
        name: String,
        track: Color,
        bar: Color,
        modifier: androidx.compose.ui.Modifier,
    )

    /**
     * A toolbar control that is only a [glyph], named by [label] — to a screen reader and to a
     * test — and marked disabled when it is not [enabled]. [active] is a toggle's state (B-176), and
     * `null` for a control that is not a toggle (B-182).
     */
    @Composable
    fun ToolButton(
        glyph: String,
        label: String,
        enabled: Boolean,
        active: Boolean?,
        onClick: () -> Unit,
    )

    /**
     * The toolbar's one labelled command. [compact] is a bar too narrow for the label: a kit whose
     * button is wider than the design's may draw the glyph alone, still named by [label].
     */
    @Composable
    fun ToolMainButton(
        glyph: String,
        label: String,
        compact: Boolean,
        onClick: () -> Unit,
    )

    /**
     * The toolbar's filter box: [text] with [placeholder] behind it when empty, a way to clear it
     * once it is not, and the field named [placeholder]. Its width is the kit's, because its text
     * and its buttons are: [narrow] is a window under 800 dp, where the field gives up most of it.
     */
    @Composable
    fun FilterField(
        text: String,
        placeholder: String,
        narrow: Boolean,
        onFilter: (String) -> Unit,
    )

    /**
     * The command at the end of a coloured band — the degraded banner's *Show it* — labelled
     * [label]. The band is the screen's; the button is the kit's (B-177).
     */
    @Composable
    fun BannerButton(
        label: String,
        onClick: () -> Unit,
    )

    /**
     * A strip of tabs named [name], [labels] in order and [selected] the current one, with the line
     * under it. Its height is the kit's: the design's strip is 32 dp, a property sheet's tab stands
     * taller.
     */
    @Composable
    fun Tabs(
        name: String,
        labels: List<String>,
        selected: Int,
        onSelect: (Int) -> Unit,
    )

    /**
     * A settings row's number field, named [label] — the row shows the label, the field carries it
     * as its accessible name. [width] is the screen's, sized for [words] in the design's face; a
     * kit whose text is larger may widen it for them. [changed] is a value that is no longer the
     * default, [absent] one that is not a number at all and says [words] (B-178).
     */
    @Composable
    fun SettingField(
        label: String,
        value: String,
        width: Dp,
        words: String?,
        changed: Boolean,
        absent: Boolean,
        onValueChange: (String) -> Unit,
    )

    /**
     * A settings row's directory: [path], picked and never typed, and the *Browse…* button that
     * picks it. [label] is the row's, for a kit whose field needs a name (B-178).
     */
    @Composable
    fun FolderField(
        label: String,
        path: String,
        changed: Boolean,
        onBrowse: () -> Unit,
    )

    /**
     * A few named values side by side, [selected] the chosen one or none, the group named [name]
     * and each segment a radio button with its label. [onSelect] hears only a segment that was not
     * chosen already (B-178).
     */
    @Composable
    fun Segmented(
        name: String,
        labels: List<String>,
        selected: Int,
        enabled: Boolean,
        onSelect: (Int) -> Unit,
    )

    /** The empty window's one way in, a [glyph] and [label]: the empty state's *Add a torrent*. */
    @Composable
    fun InvitationButton(
        glyph: String,
        label: String,
        onClick: () -> Unit,
    )

    /**
     * A dialog's frame, [width] wide, with [title] at its top. [content] is the body, laid out with
     * the [content]'s `edge` as its side margin: the frame's own padding is the kit's, and the two
     * together are the body's margin (B-178).
     */
    @Composable
    fun DialogFrame(
        title: String,
        width: Dp,
        modifier: androidx.compose.ui.Modifier,
        content: @Composable (edge: Dp) -> Unit,
    )

    /**
     * The add dialog's destination, under [label]: [path], picked by the *Browse…* button beside it
     * and never typed, with [note] under it (B-178).
     */
    @Composable
    fun SaveToField(
        label: String,
        path: String,
        note: String,
        onBrowse: () -> Unit,
    )
}

internal val LocalKit = staticCompositionLocalOf<KachokKit> { MaterialKit.Default }

/**
 * The kits a person can choose between on the settings screen, by the name the settings file
 * stores.
 *
 * [stored] is what `settings.properties` holds and must never change once released: a file written
 * by one build is read by the next. A name nobody knows — a newer build's third kit read by an
 * older one — is [Material], never a refusal to start.
 */
internal enum class InterfaceKit(
    val stored: String,
    val label: String,
) {
    Material("material", "Material"),
    Oldge("oldge-ui", "oldge-ui"),
    ;

    /**
     * The kit itself, in its own colour choice: [colours] are the Material kit's seed and style
     * (B-161), [skin] the oldge kit's skin (B-162). Each kit reads its own and ignores the other's,
     * which is what lets a person come back to a kit and find the colours they left it in.
     */
    fun kit(
        colours: MaterialColours = MaterialColours.DEFAULT,
        skin: OldgeSkin = OldgeKit.DEFAULT_SKIN,
    ): KachokKit =
        when (this) {
            Material -> MaterialKit.of(colours)
            Oldge -> OldgeKit.of(skin)
        }

    companion object {
        val DEFAULT: InterfaceKit = Material

        /** The kit a stored name means, and [DEFAULT] for a name this build does not know. */
        fun of(stored: String?): InterfaceKit = entries.firstOrNull { it.stored == stored } ?: DEFAULT
    }
}

/** The current kit's tokens, read the way `KachokTheme.colors` used to be. */
internal object KachokTheme {
    val kit: KachokKit
        @Composable @ReadOnlyComposable
        get() = LocalKit.current

    val colors: KachokColors
        @Composable @ReadOnlyComposable
        get() = LocalKit.current.colors

    val type: KachokType
        @Composable @ReadOnlyComposable
        get() = LocalKit.current.type
}
