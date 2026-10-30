package io.github.youndie.kachok.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.youndie.kachok.ui.icons.Glyph
import io.github.youndie.kachok.ui.icons.Icons
import io.github.youndie.kachok.ui.theme.ChromeText
import io.github.youndie.kachok.ui.theme.KachokTheme
import io.github.youndie.kachok.ui.theme.MonoSmall
import io.github.youndie.kachok.ui.theme.PathText
import io.github.youndie.kachok.ui.theme.Text

/**
 * Which setting a row is, and whether it can be changed here at all.
 *
 * **A key with a reason cannot be edited, and the screen says so** — the same invariant the toolbar
 * got in [B-56](../../../../../../../../docs/backlog/B-56-dead-toolbar-controls.md) and the same
 * defect it was written for: a row that looks like a field and is a picture. Two of these cannot
 * take effect because what they configure is built once, when the process starts.
 */
internal enum class SettingKey(
    val disabledBecause: String? = null,
    /**
     * Why a change here does not reach a torrent that is already running.
     *
     * A third state, and it needed to exist: [disabledBecause] would have made the row read-only,
     * and this one *can* be changed — it is the next torrent that gets the new number, not this
     * one. Saying nothing at all is what left the screen's footnote claiming every setting applies
     * immediately while two of them did not.
     */
    val nextTorrentBecause: String? = null,
) {
    SaveTo,
    StartWhenAdded,
    ListeningPort("bound when the process starts"),
    MaxPeers,
    PipelineDepth(
        nextTorrentBecause =
            "the buffer pool is sized from it when a torrent opens, and a session cannot grow the " +
                "pool it was handed",
    ),
    AllTrackers,
    UploadLimit,
    DownloadLimit,
    MaxActiveDownloads,
    MaxConnections,
    MaxDials,
    Dht,
    Autostart,
    CloseToTray,

    /** Which kit draws the window (B-160). */
    Interface,

    /**
     * The kit's colours, one row whose contents are the kit's: under the Material kit a seed and a
     * scheme style (B-161), under oldge-ui its skins (B-162).
     */
    Colours,
    ;

    val editable: Boolean get() = disabledBecause == null
}

/** What a setting is: a name, why it matters, its measured default, and what it is now. */
internal class Setting(
    val key: SettingKey,
    val label: String,
    /** The design's own rule: the default is printed, not hidden in a blank field. */
    val default: String,
    val value: String,
    val note: String? = null,
    /** `KiB/s`, and nothing else in this screen. */
    val unit: String? = null,
    val toggle: Boolean? = null,
    /** True when the value is not the default any more, which the design outlines in primary. */
    val changed: Boolean = false,
    /** A value that is absent rather than zero: the field says the words. */
    val absent: Boolean = false,
    /**
     * The words the field says when the value is absent, if it can be.
     *
     * The field is sized for them even while a number is in it (B-169): sized for its widest number
     * alone, `no limit` came out as `no lim`, and a field that widened only once it was cleared would
     * move the row under the person's hands.
     */
    val absentAs: String? = null,
    /** A directory rather than a number: a wider field with a folder in it, and a Browse button. */
    val folder: Boolean = false,
    /**
     * Whether this row can be changed *on this machine*, as opposed to ever.
     *
     * [SettingKey.disabledBecause] is the permanent answer — the listening port is bound when the
     * process starts, on every machine. This is the one that depends on where the application is
     * running: a desktop with no tray has nothing to close to, and a row that could be switched on
     * there would be a promise the close button does not keep.
     */
    val enabled: Boolean = true,
    /**
     * One of a few named values rather than a number or a switch: a segmented control, with
     * [value] the chosen one's [Choice.value]. The *Interface* row's kits are the first (B-160).
     */
    val choices: List<Choice>? = null,
    /**
     * Colours to pick from, drawn as swatches, with [swatch] the chosen one's [Swatch.value] — or
     * none of them, for a colour somebody wrote into the file by hand. A row may carry [choices]
     * too: the *Colours* row's are the scheme styles, under the swatches (B-161).
     */
    val swatches: List<Swatch>? = null,
    val swatch: String? = null,
)

/** One colour of a swatch row: what is stored, what is drawn, and what a screen reader says. */
internal class Swatch(
    val value: String,
    val color: Color,
    val label: String,
    /**
     * A second colour, drawn as a dot on [color]: an oldge skin's accent on its ground (B-162). A
     * skin is not one colour, and two of the three share their lime — the ground is what tells them
     * apart, and the dot is what says the square is a skin rather than a seed.
     */
    val mark: Color? = null,
)

/** One segment of a choice: what is stored, and what the segment says. */
internal class Choice(
    val value: String,
    val label: String,
)

internal class SettingsSection(
    val title: String,
    val settings: List<Setting>,
)

internal class SettingsState(
    val sections: List<SettingsSection>,
    val footnote: String,
    val footnotePlanned: Boolean = true,
) {
    val all: List<Setting> get() = sections.flatMap { it.settings }
}

/** What the screen reports when something on it is changed. */
internal sealed interface SettingChange {
    class Toggled(
        val key: SettingKey,
        val on: Boolean,
    ) : SettingChange

    class Typed(
        val key: SettingKey,
        val text: String,
    ) : SettingChange

    class Browsed(
        val key: SettingKey,
    ) : SettingChange

    /** A segment of a [Setting.choices] row was picked; [value] is its [Choice.value]. */
    class Chose(
        val key: SettingKey,
        val value: String,
    ) : SettingChange

    /** A swatch of a [Setting.swatches] row was picked; [value] is its [Swatch.value]. */
    class Swatched(
        val key: SettingKey,
        val value: String,
    ) : SettingChange
}

/**
 * One screen, in the window, with the measured default printed beside every field.
 *
 * **That is the design's rule and this project's.** Every number here is a `SessionConfig` field
 * and every default was measured rather than guessed, so a blank field would throw the measurement
 * away — somebody changing the pipeline depth should be able to see what it was.
 *
 * **The DHT toggle is the only setting with a paragraph.** A switch that announces this machine's
 * address to strangers earns an explanation next to it, not in a help page.
 */
@Composable
internal fun SettingsScreen(
    state: SettingsState,
    modifier: Modifier = Modifier,
    onChange: (SettingChange) -> Unit = {},
) {
    val scheme = KachokTheme.colors
    Column(modifier.fillMaxSize().background(scheme.surface)) {
        // **Scrolls, and the footnote below it does not.** At the artboard's 620×760 the rows
        // filled the screen exactly, so the next setting added pushed the last one off it — not
        // out of reach by scrolling, which is recoverable, but clipped, which is not. It was found
        // by `everyEditableSettingLeavesTheWindow` failing and naming the *wrong* setting: the new
        // row reported its change and *Join the DHT*, last on the screen, reported nothing (B-104).
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            state.sections.forEach { section ->
                Text(
                    section.title,
                    style = KachokTheme.type.section.copy(letterSpacing = SECTION_TRACKING),
                    color = scheme.textFaint,
                    modifier = Modifier.padding(start = EDGE, end = EDGE, top = 16.dp, bottom = 6.dp),
                )
                section.settings.forEach { SettingRow(it, onChange) }
            }
        }
        Footnote(state)
    }
}

@Composable
private fun SettingRow(
    setting: Setting,
    onChange: (SettingChange) -> Unit,
) {
    val scheme = KachokTheme.colors
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = EDGE, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = if (setting.note != null) Alignment.Top else Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(setting.label, style = LABEL, color = scheme.text)
                val qualifier =
                    setting.key.disabledBecause?.let { "Not changeable here — $it." }
                        ?: setting.key.nextTorrentBecause?.let { "Applies to the next torrent — $it." }
                val note = listOfNotNull(setting.note, qualifier).joinToString(" ").ifBlank { null }
                note?.let {
                    Text(
                        it,
                        style = NOTE,
                        color = scheme.textFaint,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (setting.unit == null) 10.dp else 8.dp),
            ) {
                Text("default ${setting.default}", style = DEFAULT, color = scheme.textFaint)
                when {
                    setting.toggle != null -> {
                        // The kit's switch since B-160; whether it can be pressed is decided here.
                        KachokTheme.kit.Toggle(
                            setting.toggle,
                            setting.label,
                            setting.enabled && setting.key.editable,
                        ) {
                            onChange(SettingChange.Toggled(setting.key, !setting.toggle))
                        }
                    }

                    setting.swatches != null -> {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Swatches(setting, setting.swatches) { onChange(SettingChange.Swatched(setting.key, it)) }
                            setting.choices?.let { choices ->
                                Segmented(setting, choices, description = "${setting.label} style") {
                                    onChange(SettingChange.Chose(setting.key, it))
                                }
                            }
                        }
                    }

                    setting.choices != null -> {
                        Segmented(setting, setting.choices) { onChange(SettingChange.Chose(setting.key, it)) }
                    }

                    setting.folder -> {
                        FolderField(setting.value, setting.changed) {
                            onChange(SettingChange.Browsed(setting.key))
                        }
                    }

                    else -> {
                        ValueField(setting) { onChange(SettingChange.Typed(setting.key, it)) }
                    }
                }
                setting.unit?.let {
                    Text(it, style = DEFAULT, color = scheme.textFaint, modifier = Modifier.width(UNIT_WIDTH))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(HAIRLINE).background(KachokTheme.colors.rowHairline))
    }
}

/**
 * A number, typed into.
 *
 * `BasicTextField` and not `OutlinedTextField` for the reason the whole screen is drawn by hand:
 * the M3 field brings a label slot, a supporting line and 56 dp of height to a row that is 30.
 *
 * A key that cannot take effect is drawn as text and says why on the row, rather than as a field
 * that accepts what somebody types and throws it away.
 */
@Composable
private fun ValueField(
    setting: Setting,
    onTyped: (String) -> Unit,
) {
    val scheme = KachokTheme.colors
    if (!setting.key.editable) {
        Box(
            Modifier
                .height(FIELD_HEIGHT)
                .width(fieldWidth(setting))
                .padding(horizontal = FIELD_PADDING),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                setting.value,
                style = FIELD_TEXT,
                color = scheme.textFaint,
                maxLines = 1,
            )
        }
        return
    }
    Box(
        Modifier
            .height(FIELD_HEIGHT)
            .width(fieldWidth(setting))
            // Outlined in primary when it is no longer the default: the one place this screen
            // says "you changed this", and it says it where the change is.
            .border(
                HAIRLINE,
                if (setting.changed) scheme.accentText else scheme.outline,
                RoundedCornerShape(4.dp),
            ).padding(horizontal = FIELD_PADDING),
        contentAlignment = Alignment.CenterEnd,
    ) {
        BasicTextField(
            value = setting.value,
            onValueChange = onTyped,
            singleLine = true,
            textStyle =
                FIELD_TEXT.copy(
                    // A value that is absent is dimmed; a zero would not be, and that is the
                    // difference the words are there to carry.
                    color = if (setting.absent) scheme.textFaint else scheme.text,
                    textAlign = TextAlign.End,
                ),
            cursorBrush = SolidColor(scheme.accentText),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = setting.label },
        )
    }
}

/**
 * A number field's width: the design's, or wider when the words for an absent value need it.
 *
 * Measured in the field's own style rather than written as a number, because the words are the
 * kit's text in the kit's face, and a width guessed for one face is a clipped word in another.
 */
@Composable
private fun fieldWidth(setting: Setting): Dp {
    val base = if (setting.unit == null) FIELD_WIDTH else WIDE_FIELD
    val words = setting.absentAs ?: return base
    val measurer = rememberTextMeasurer()
    val measured =
        with(LocalDensity.current) {
            measurer
                .measure(words, FIELD_TEXT)
                .size.width
                .toDp()
        }
    return maxOf(base, measured + FIELD_PADDING * 2 + CARET_ROOM)
}

@Composable
private fun FolderField(
    path: String,
    changed: Boolean,
    onBrowse: () -> Unit,
) {
    val scheme = KachokTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .height(FIELD_HEIGHT)
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
                .height(FIELD_HEIGHT)
                .border(HAIRLINE, scheme.outline, RoundedCornerShape(4.dp))
                .semantics { contentDescription = "Browse" }
                .clickable(onClick = onBrowse)
                .padding(horizontal = 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Browse…", style = ChromeText.copy(fontSize = 12.sp), color = scheme.text, maxLines = 1)
        }
    }
}

/**
 * A few named values side by side, the chosen one filled with the accent.
 *
 * Drawn here from the kit's tokens rather than asked of the kit: it is a row of boxes and text, and
 * under any kit it is that kit's colours on this screen's shape — the same footing as the fields
 * beside it. The group carries the row's label; each segment is a radio button with its own.
 */
@Composable
private fun Segmented(
    setting: Setting,
    choices: List<Choice>,
    description: String = setting.label,
    onChose: (String) -> Unit,
) {
    val scheme = KachokTheme.colors
    Row(
        Modifier
            .height(FIELD_HEIGHT)
            .border(HAIRLINE, scheme.outline, RoundedCornerShape(4.dp))
            .semantics { contentDescription = description }
            .selectableGroup(),
    ) {
        choices.forEach { choice ->
            val chosen = choice.value == setting.value
            Box(
                Modifier
                    .fillMaxHeight()
                    .background(if (chosen) scheme.accent else Color.Transparent, RoundedCornerShape(4.dp))
                    .selectable(
                        selected = chosen,
                        enabled = setting.enabled && setting.key.editable,
                        role = Role.RadioButton,
                        onClick = { if (!chosen) onChose(choice.value) },
                    ).padding(horizontal = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    choice.label,
                    style = ChromeText.copy(fontSize = 12.sp),
                    color = if (chosen) scheme.onAccent else scheme.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A row of colours, each a square of itself — with its [Swatch.mark] on it, for a skin — the chosen
 * one ringed in the first text level.
 *
 * The ring and not a check mark: the swatch *is* the information, and a glyph on it would have to
 * be drawn in a colour that reads on all six. Each swatch is a radio button with the colour's name,
 * because a colour is not a name a screen reader can say.
 */
@Composable
private fun Swatches(
    setting: Setting,
    swatches: List<Swatch>,
    onPicked: (String) -> Unit,
) {
    val scheme = KachokTheme.colors
    Row(
        Modifier.semantics { contentDescription = setting.label }.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        swatches.forEach { swatch ->
            val chosen = swatch.value == setting.swatch
            Box(
                Modifier
                    .size(SWATCH)
                    .border(
                        if (chosen) 2.dp else HAIRLINE,
                        if (chosen) scheme.text else scheme.outline,
                        RoundedCornerShape(4.dp),
                    ).semantics { contentDescription = swatch.label }
                    .selectable(
                        selected = chosen,
                        enabled = setting.enabled && setting.key.editable,
                        role = Role.RadioButton,
                        onClick = { if (!chosen) onPicked(swatch.value) },
                    ).padding(4.dp)
                    .background(swatch.color, RoundedCornerShape(2.dp)),
                contentAlignment = Alignment.Center,
            ) {
                swatch.mark?.let { Box(Modifier.size(MARK).background(it, CircleShape)) }
            }
        }
    }
}

@Composable
private fun Footnote(state: SettingsState) {
    val scheme = KachokTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(HAIRLINE).background(scheme.raisedHigh))
        Row(
            Modifier
                .fillMaxWidth()
                .background(scheme.footnoteGround)
                .padding(horizontal = EDGE, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Glyph(Icons.BOLT, size = FOOTNOTE_GLYPH, tint = scheme.textFaint)
            Text(state.footnote, style = NOTE.copy(fontSize = 11.sp), color = scheme.textFaint)
            if (state.footnotePlanned) PlannedBadge()
        }
    }
}

@Composable
private fun PlannedBadge() {
    Box(
        Modifier
            .border(HAIRLINE, KachokTheme.colors.warningContainer, RoundedCornerShape(2.dp))
            .padding(horizontal = 3.dp),
    ) {
        Text(
            "planned",
            style = ChromeText.copy(fontSize = BADGE),
            color = KachokTheme.colors.warning,
        )
    }
}

private val EDGE = 20.dp

private val HAIRLINE = 1.dp

private val FIELD_HEIGHT = 30.dp

private val FIELD_WIDTH = 76.dp

private val WIDE_FIELD = 86.dp

/** A number field's own text: the figures' mono, a point larger than the status bar's. */
private val FIELD_TEXT = MonoSmall.copy(fontSize = 12.sp)

/** The field's inner padding on each side. */
private val FIELD_PADDING = 9.dp

/** The caret's width beside the last character, so a full field does not scroll by a pixel. */
private val CARET_ROOM = 2.dp

private val UNIT_WIDTH = 38.dp

private val PATH_WIDTH = 210.dp

private val SWATCH = 24.dp

private val MARK = 8.dp

private val FOLDER_GLYPH = 16.sp

private val FOOTNOTE_GLYPH = 15.sp

private val SECTION_TRACKING = 0.08.em

private val LABEL = ChromeText.copy(fontSize = 12.5.sp)

private val NOTE = ChromeText.copy(fontSize = 10.5.sp)

private val DEFAULT = MonoSmall.copy(fontSize = 10.5.sp)

private val BADGE = 9.5.sp
