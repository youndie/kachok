package io.github.youndie.kachok.ui.theme.material

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.youndie.kachok.ui.icons.Glyph
import io.github.youndie.kachok.ui.icons.Icons
import io.github.youndie.kachok.ui.theme.ChromeButton
import io.github.youndie.kachok.ui.theme.ChromeText
import io.github.youndie.kachok.ui.theme.KachokTheme
import io.github.youndie.kachok.ui.theme.MonoSmall
import io.github.youndie.kachok.ui.theme.PathText
import io.github.youndie.kachok.ui.theme.RowStateLabel
import io.github.youndie.kachok.ui.theme.Text

/*
 * The Material kit's own drawing of the components a second kit draws differently (B-160).
 *
 * Each one is the code the screen had before it moved here, moved and not rewritten: the twelve
 * goldens are the evidence that this kit still draws what the window drew, and a rewrite would be a
 * second design to compare against them.
 */

/** The settings screen's switch: a 34 x 20 capsule and its knob, from `settings/Settings.kt`. */
@Composable
internal fun MaterialToggle(
    on: Boolean,
    label: String,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val scheme = KachokTheme.colors
    Box(
        Modifier
            .semantics {
                contentDescription = label
                // **The switch says which way it is.** It is a drawn `Box` and not a `Switch`, so
                // until this it announced nothing: a screen reader read the label and stopped, and
                // a test could press it but not read it. The column heads have said their sort
                // order this way since B-58.
                stateDescription = if (on) "on" else "off"
                // **And what it is** (B-180): without a role the platform had a named box, not a
                // switch. `toggleable` and not `clickable`, as `OldgeSwitch` has it, so the state is
                // the platform's own on/off as well as the words.
            }.toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = { onToggle() })
            .width(TOGGLE_WIDTH)
            .height(TOGGLE_HEIGHT)
            .background(
                if (on) scheme.accent else scheme.raisedHighest,
                RoundedCornerShape(TOGGLE_HEIGHT / 2),
            ).border(
                if (on) 0.dp else HAIRLINE,
                if (on) scheme.accent else scheme.outline,
                RoundedCornerShape(TOGGLE_HEIGHT / 2),
            ).padding(horizontal = 3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(KNOB)
                .background(
                    if (on) scheme.onAccent else scheme.textFaint,
                    RoundedCornerShape(KNOB / 2),
                ),
        )
    }
}

/** Both dialogs' button, from `theme/DialogButton.kt`. */
@Composable
internal fun MaterialDialogButton(
    label: String,
    primary: Boolean,
    enabled: Boolean,
    destructive: Boolean,
    onClick: () -> Unit,
) {
    val scheme = KachokTheme.colors
    val ground =
        when {
            !primary -> Color.Transparent
            !enabled -> scheme.raisedHighest
            destructive -> scheme.error
            else -> scheme.accent
        }
    Box(
        Modifier
            .height(BUTTON_HEIGHT)
            .background(ground, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = if (primary) 18.dp else 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = if (primary) ChromeButton.copy(fontWeight = FontWeight.SemiBold) else ChromeButton,
            color =
                when {
                    !enabled -> scheme.textFaint
                    destructive -> scheme.onError
                    primary -> scheme.onAccent
                    else -> KachokTheme.colors.textMuted
                },
            maxLines = 1,
        )
    }
}

/**
 * The row's bar, from `list/TorrentRow.kt`: custom for a reason the design writes down — 4 dp, no
 * M3-Expressive wave and no stop indicator, because it redraws at 1 Hz and a wave animating under a
 * number that changes once a second is decoration fighting the data.
 */
@Composable
internal fun MaterialProgressBar(
    fraction: Float,
    track: Color,
    bar: Color,
    modifier: Modifier,
) {
    Box(
        modifier
            .height(BAR_HEIGHT)
            .background(track, RoundedCornerShape(BAR_HEIGHT / 2)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(BAR_HEIGHT)
                .background(bar, RoundedCornerShape(BAR_HEIGHT / 2)),
        )
    }
}

/**
 * A 28 dp icon button, from `main/Toolbar.kt`.
 *
 * Three states and no more, because the design draws three: available, unavailable, and on. An
 * unavailable one is dimmed rather than hidden — the design's *Resume* is greyed while a torrent is
 * running, so the toolbar does not reshuffle every time a download starts.
 */
@Composable
internal fun MaterialToolButton(
    glyph: String,
    label: String,
    enabled: Boolean,
    active: Boolean,
    onClick: () -> Unit,
) {
    val scheme = KachokTheme.colors
    val background = if (active) scheme.accentContainer else Color.Transparent
    val tint =
        when {
            active -> scheme.accentBright
            enabled -> scheme.textMuted
            else -> scheme.textFaint
        }
    Box(
        Modifier
            .size(CONTROL_HEIGHT)
            .background(background, RoundedCornerShape(CONTROL_RADIUS))
            // `Role.Button` (B-180): the name made it findable by a test; the role is what makes
            // the platform's accessibility list it as a button at all. Without it the Material
            // toolbar was a row of nothing to VoiceOver while the oldge one was nine buttons.
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            // A glyph and nothing else. Without this the button has no name at all — not to a
            // screen reader, and not to a test, which is why the bar's guard could only ever
            // click the one control that happens to carry text.
            .semantics {
                contentDescription = label
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Glyph(glyph, size = ACTION_GLYPH, tint = tint)
    }
}

/**
 * The only tonal button in the whole window, from `main/Toolbar.kt`. It fits the narrow bar with its
 * label, so [compact] changes nothing here.
 *
 * `FilledTonalButton`'s own geometry is 40 dp high with a full-height corner radius; the design
 * asks for 28 dp and 4 dp, which is every dimension the component has, so this is drawn rather
 * than configured.
 */
@Composable
internal fun MaterialToolMainButton(
    glyph: String,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .height(CONTROL_HEIGHT)
            .background(KachokTheme.colors.accentContainer, RoundedCornerShape(CONTROL_RADIUS))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Glyph(glyph, size = BUTTON_GLYPH, tint = KachokTheme.colors.accentBright)
        Text(label, style = ChromeButton, color = KachokTheme.colors.accentBright, maxLines = 1)
    }
}

/**
 * The filter box, from `main/Toolbar.kt`.
 *
 * An outlined box rather than `OutlinedTextField`, which reserves room for a floating label and a
 * supporting line and cannot be 28 dp high with either — so the placeholder is drawn behind the
 * field rather than configured on it.
 *
 * The clear button appears only when there is something to clear. A filter that has hidden thirteen
 * of sixteen rows is a state a person has to be able to leave in one press, and the alternative —
 * selecting the text and deleting it — is three.
 */
@Composable
internal fun MaterialFilterField(
    text: String,
    placeholder: String,
    width: Dp,
    onFilter: (String) -> Unit,
) {
    val scheme = KachokTheme.colors
    Row(
        Modifier
            .height(CONTROL_HEIGHT)
            .width(width)
            .border(HAIRLINE, scheme.outline, RoundedCornerShape(CONTROL_RADIUS))
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Glyph(Icons.SEARCH, size = SEARCH_GLYPH, tint = scheme.textFaint)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) {
                Text(placeholder, style = ChromeText, color = scheme.textFaint, maxLines = 1)
            }
            BasicTextField(
                value = text,
                onValueChange = onFilter,
                singleLine = true,
                textStyle = ChromeText.copy(color = scheme.text),
                cursorBrush = SolidColor(scheme.accentText),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
            )
        }
        if (text.isNotEmpty()) {
            Glyph(
                Icons.CLOSE,
                size = SEARCH_GLYPH,
                tint = scheme.textFaint,
                modifier =
                    Modifier
                        .clickable(role = Role.Button) { onFilter("") }
                        .semantics { contentDescription = "Clear the filter" },
            )
        }
    }
}

/**
 * The degraded banner's button, from `main/DegradedBanner.kt`: a bordered box with the label in the
 * error colour, the band's own colours, so it reads as part of the band and not as a second alarm.
 */
@Composable
internal fun MaterialBannerButton(
    label: String,
    onClick: () -> Unit,
) {
    val scheme = KachokTheme.colors
    Row(
        Modifier
            .height(BANNER_BUTTON_HEIGHT)
            .border(HAIRLINE, scheme.errorContainer, RoundedCornerShape(CONTROL_RADIUS))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = RowStateLabel, color = scheme.error, maxLines = 1)
    }
}

/** The details panel's tab strip, from `details/DetailsPanel.kt`: labels over an accent underline. */
@Composable
internal fun MaterialTabs(
    name: String,
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(TAB_HEIGHT)
                .semantics { contentDescription = name }
                .selectableGroup()
                .padding(horizontal = 6.dp),
        ) {
            labels.forEachIndexed { index, label ->
                val on = index == selected
                Column(
                    Modifier
                        // The indicator is as wide as the label and no wider, and the only way to
                        // say that is to measure the column by what is in it: `fillMaxWidth` on the
                        // indicator otherwise takes the whole tab row and the first tab eats the rest.
                        .width(IntrinsicSize.Max)
                        // A tab, and which one is current (B-180), as `OldgeTabs` says it: the
                        // underline is the only other thing that does, and nobody can read it.
                        .selectable(selected = on, role = Role.Tab) { onSelect(index) }
                        .padding(horizontal = 10.dp),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(
                        label,
                        style = if (on) ChromeText.copy(fontWeight = FontWeight.Medium) else ChromeText,
                        color = if (on) KachokTheme.colors.accentText else KachokTheme.colors.textFaint,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 8.dp, bottom = 7.dp),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(INDICATOR)
                            .background(if (on) KachokTheme.colors.accent else Color.Transparent),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(HAIRLINE).background(KachokTheme.colors.divider))
    }
}

/**
 * A settings row's number, typed into, from `settings/Settings.kt`.
 *
 * `BasicTextField` and not `OutlinedTextField` for the reason the whole screen is drawn by hand:
 * the M3 field brings a label slot, a supporting line and 56 dp of height to a row that is 30.
 */
@Composable
internal fun MaterialSettingField(
    label: String,
    value: String,
    width: Dp,
    changed: Boolean,
    absent: Boolean,
    onValueChange: (String) -> Unit,
) {
    val scheme = KachokTheme.colors
    Box(
        Modifier
            .height(SETTING_FIELD_HEIGHT)
            .width(width)
            // Outlined in primary when it is no longer the default: the one place this screen
            // says "you changed this", and it says it where the change is.
            .border(
                HAIRLINE,
                if (changed) scheme.accentText else scheme.outline,
                RoundedCornerShape(4.dp),
            ).padding(horizontal = SETTING_FIELD_PADDING),
        contentAlignment = Alignment.CenterEnd,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle =
                SETTING_FIELD_TEXT.copy(
                    // A value that is absent is dimmed; a zero would not be, and that is the
                    // difference the words are there to carry.
                    color = if (absent) scheme.textFaint else scheme.text,
                    textAlign = TextAlign.End,
                ),
            cursorBrush = SolidColor(scheme.accentText),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = label },
        )
    }
}

/** A settings row's directory and its *Browse…*, from `settings/Settings.kt`. */
@Composable
internal fun MaterialFolderField(
    path: String,
    changed: Boolean,
    onBrowse: () -> Unit,
) {
    val scheme = KachokTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .height(SETTING_FIELD_HEIGHT)
                .width(PATH_WIDTH)
                .border(
                    HAIRLINE,
                    if (changed) scheme.accentText else scheme.outline,
                    RoundedCornerShape(4.dp),
                ).padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Glyph(Icons.FOLDER, size = FOLDER_GLYPH, tint = scheme.textFaint)
            PathText(path, MonoSmall, scheme.text, Modifier.weight(1f), textAlign = TextAlign.Start)
        }
        Box(
            Modifier
                .height(SETTING_FIELD_HEIGHT)
                .border(HAIRLINE, scheme.outline, RoundedCornerShape(4.dp))
                .semantics { contentDescription = "Browse" }
                .clickable(role = Role.Button, onClick = onBrowse)
                .padding(horizontal = 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Browse…", style = ChromeText.copy(fontSize = 12.sp), color = scheme.text, maxLines = 1)
        }
    }
}

/**
 * A few named values side by side, the chosen one filled with the accent, from
 * `settings/Settings.kt`. The group carries the row's label; each segment is a radio button with
 * its own.
 */
@Composable
internal fun MaterialSegmented(
    name: String,
    labels: List<String>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    val scheme = KachokTheme.colors
    Row(
        Modifier
            .height(SETTING_FIELD_HEIGHT)
            .border(HAIRLINE, scheme.outline, RoundedCornerShape(4.dp))
            .semantics { contentDescription = name }
            .selectableGroup(),
    ) {
        labels.forEachIndexed { index, label ->
            val chosen = index == selected
            Box(
                Modifier
                    .fillMaxHeight()
                    .background(if (chosen) scheme.accent else Color.Transparent, RoundedCornerShape(4.dp))
                    .selectable(
                        selected = chosen,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { if (!chosen) onSelect(index) },
                    ).padding(horizontal = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = ChromeText.copy(fontSize = 12.sp),
                    color = if (chosen) scheme.onAccent else scheme.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The empty state's *Add a torrent*, from `main/EmptyState.kt`: the accent's one filled button. */
@Composable
internal fun MaterialInvitationButton(
    glyph: String,
    label: String,
    onClick: () -> Unit,
) {
    val scheme = KachokTheme.colors
    Row(
        Modifier
            .height(INVITATION_HEIGHT)
            .background(scheme.accent, RoundedCornerShape(4.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Glyph(glyph, size = INVITATION_GLYPH, tint = scheme.onAccent)
        Text(
            label,
            style = ChromeButton.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
            color = scheme.onAccent,
        )
    }
}

/**
 * Both dialogs' frame, from `add/AddTorrent.kt` and `remove/RemoveTorrent.kt`: a raised plate with a
 * border, the title in the headline face, and the body at the design's 20 dp from its sides.
 */
@Composable
internal fun MaterialDialogFrame(
    title: String,
    width: Dp,
    modifier: Modifier,
    content: @Composable (edge: Dp) -> Unit,
) {
    val scheme = KachokTheme.colors
    Column(
        modifier
            .width(width)
            .background(scheme.raisedHigh, RoundedCornerShape(6.dp))
            .border(HAIRLINE, scheme.dialogBorder, RoundedCornerShape(6.dp)),
    ) {
        Text(
            title,
            style = KachokTheme.type.headline,
            color = scheme.text,
            modifier = Modifier.padding(start = DIALOG_EDGE, end = DIALOG_EDGE, top = 18.dp, bottom = 14.dp),
        )
        content(DIALOG_EDGE)
    }
}

/** The add dialog's *Save to*, its directory and *Browse…*, from `add/AddTorrent.kt`. */
@Composable
internal fun MaterialSaveToField(
    label: String,
    path: String,
    note: String,
    onBrowse: () -> Unit,
) {
    val scheme = KachokTheme.colors
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, style = DIALOG_SECTION_LABEL, color = KachokTheme.colors.textMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier
                    .weight(1f)
                    .height(DIALOG_FIELD_HEIGHT)
                    .border(HAIRLINE, scheme.outline, RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Glyph(Icons.FOLDER, size = DIALOG_FIELD_GLYPH, tint = scheme.textFaint)
                PathText(
                    path,
                    MonoSmall.copy(fontSize = 12.sp),
                    scheme.text,
                    Modifier.weight(1f),
                    textAlign = TextAlign.Start,
                )
            }
            Box(
                Modifier
                    .height(DIALOG_FIELD_HEIGHT)
                    .border(HAIRLINE, scheme.outline, RoundedCornerShape(4.dp))
                    // The one decision this dialog exists to take. It was a bordered box.
                    .clickable(role = Role.Button, onClick = onBrowse)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Browse…", style = ChromeButton, color = scheme.text, maxLines = 1)
            }
        }
        Text(note, style = DIALOG_NOTE, color = scheme.textFaint)
    }
}

/** A settings field's height. */
private val SETTING_FIELD_HEIGHT = 30.dp

/**
 * A number field's own text: the figures' mono, a point larger than the status bar's. The settings
 * screen sizes the field for its words in the same style, as `FIELD_TEXT`.
 */
private val SETTING_FIELD_TEXT = MonoSmall.copy(fontSize = 12.sp)

/** The number field's inner padding on each side; the screen's `FIELD_PADDING`, for the same reason. */
private val SETTING_FIELD_PADDING = 9.dp

private val PATH_WIDTH = 210.dp

private val FOLDER_GLYPH = 16.sp

private val INVITATION_HEIGHT = 34.dp

private val INVITATION_GLYPH = 18.sp

/** A dialog body's side margin, and the title's. */
private val DIALOG_EDGE = 20.dp

private val DIALOG_FIELD_HEIGHT = 36.dp

private val DIALOG_FIELD_GLYPH = 17.sp

private val DIALOG_SECTION_LABEL = ChromeText.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium)

private val DIALOG_NOTE = ChromeText.copy(fontSize = 10.5.sp)

/** The design's `height: 22px` plus the border CSS measures outside it. */
private val BANNER_BUTTON_HEIGHT = 24.dp

private val TAB_HEIGHT = 32.dp

private val INDICATOR = 2.dp

/**
 * 220, not the design's `width: 200px`.
 *
 * CSS measures that width inside the border and the padding; the box on screen is 200 + 18 + 2,
 * and 200 here would be a field twenty pixels narrower than the one in the picture. Every other
 * dimension in the design happens to have no padding or border to add.
 */
internal val FILTER_WIDTH = 220.dp

/** Enough for the glyph, a word and the clear button, which is what the field is for. */
internal val NARROW_FILTER_WIDTH = 110.dp

/** A toolbar control, and the size the design draws every icon button at (`Chrome.controlHeight`). */
private val CONTROL_HEIGHT = 28.dp

private val CONTROL_RADIUS = 4.dp

private val ACTION_GLYPH = 18.sp

private val BUTTON_GLYPH = 17.sp

private val SEARCH_GLYPH = 16.sp

private val HAIRLINE = 1.dp

private val TOGGLE_WIDTH = 34.dp

private val TOGGLE_HEIGHT = 20.dp

private val KNOB = 14.dp

/**
 * 32 dp, which is what the add dialog drew before this function was shared.
 *
 * Written down because moving the button here silently made it 30 and shifted every row in
 * `add_dialog.png` by a pixel — a regression the golden caught only because the golden was
 * re-recorded and read, not because anything failed.
 */
private val BUTTON_HEIGHT = 32.dp

private val BAR_HEIGHT = 4.dp
