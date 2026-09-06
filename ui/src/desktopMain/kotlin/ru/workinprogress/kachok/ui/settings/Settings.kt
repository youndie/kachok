package ru.workinprogress.kachok.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ru.workinprogress.kachok.ui.icons.Glyph
import ru.workinprogress.kachok.ui.icons.Icons
import ru.workinprogress.kachok.ui.theme.ChromeText
import ru.workinprogress.kachok.ui.theme.KachokPalette
import ru.workinprogress.kachok.ui.theme.MonoSmall
import ru.workinprogress.kachok.ui.theme.PathText
import ru.workinprogress.kachok.ui.theme.warningColors

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
    UploadLimit,
    DownloadLimit,
    Dht,
    Autostart,
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
    /** A directory rather than a number: a wider field with a folder in it, and a Browse button. */
    val folder: Boolean = false,
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
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize().background(scheme.surface)) {
        Column(Modifier.weight(1f).fillMaxWidth()) {
            state.sections.forEach { section ->
                Text(
                    section.title,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = SECTION_TRACKING),
                    color = scheme.onSurfaceVariant,
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
    val scheme = MaterialTheme.colorScheme
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = EDGE, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = if (setting.note != null) Alignment.Top else Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(setting.label, style = LABEL, color = scheme.onSurface)
                val qualifier =
                    setting.key.disabledBecause?.let { "Not changeable here — $it." }
                        ?: setting.key.nextTorrentBecause?.let { "Applies to the next torrent — $it." }
                val note = listOfNotNull(setting.note, qualifier).joinToString(" ").ifBlank { null }
                note?.let {
                    Text(
                        it,
                        style = NOTE,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (setting.unit == null) 10.dp else 8.dp),
            ) {
                Text("default ${setting.default}", style = DEFAULT, color = scheme.onSurfaceVariant)
                when {
                    setting.toggle != null -> {
                        Toggle(setting.toggle, setting.key, setting.label) {
                            onChange(SettingChange.Toggled(setting.key, !setting.toggle))
                        }
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
                    Text(it, style = DEFAULT, color = scheme.onSurfaceVariant, modifier = Modifier.width(UNIT_WIDTH))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(HAIRLINE).background(KachokPalette.rowHairline))
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
    val scheme = MaterialTheme.colorScheme
    if (!setting.key.editable) {
        Box(
            Modifier
                .height(FIELD_HEIGHT)
                .width(if (setting.unit == null) FIELD_WIDTH else WIDE_FIELD)
                .padding(horizontal = 9.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                setting.value,
                style = MonoSmall.copy(fontSize = 12.sp),
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        return
    }
    Box(
        Modifier
            .height(FIELD_HEIGHT)
            .width(if (setting.unit == null) FIELD_WIDTH else WIDE_FIELD)
            // Outlined in primary when it is no longer the default: the one place this screen
            // says "you changed this", and it says it where the change is.
            .border(
                HAIRLINE,
                if (setting.changed) scheme.primary else scheme.outline,
                RoundedCornerShape(4.dp),
            ).padding(horizontal = 9.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        BasicTextField(
            value = setting.value,
            onValueChange = onTyped,
            singleLine = true,
            textStyle =
                MonoSmall.copy(
                    fontSize = 12.sp,
                    // A value that is absent is dimmed; a zero would not be, and that is the
                    // difference the words are there to carry.
                    color = if (setting.absent) scheme.onSurfaceVariant else scheme.onSurface,
                    textAlign = TextAlign.End,
                ),
            cursorBrush = SolidColor(scheme.primary),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = setting.label },
        )
    }
}

@Composable
private fun FolderField(
    path: String,
    changed: Boolean,
    onBrowse: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .height(FIELD_HEIGHT)
                .width(PATH_WIDTH)
                .border(
                    HAIRLINE,
                    if (changed) scheme.primary else scheme.outline,
                    RoundedCornerShape(4.dp),
                ).padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Glyph(Icons.FOLDER, size = FOLDER_GLYPH, tint = scheme.onSurfaceVariant)
            PathText(path, MonoSmall, scheme.onSurface, Modifier.weight(1f), textAlign = TextAlign.Start)
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
            Text("Browse…", style = ChromeText.copy(fontSize = 12.sp), color = scheme.onSurface, maxLines = 1)
        }
    }
}

@Composable
private fun Toggle(
    on: Boolean,
    key: SettingKey,
    label: String,
    onToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .semantics { contentDescription = label }
            .clickable(enabled = key.editable, onClick = onToggle)
            .width(TOGGLE_WIDTH)
            .height(TOGGLE_HEIGHT)
            .background(
                if (on) scheme.primary else scheme.surfaceContainerHighest,
                RoundedCornerShape(TOGGLE_HEIGHT / 2),
            ).border(
                if (on) 0.dp else HAIRLINE,
                if (on) scheme.primary else scheme.outline,
                RoundedCornerShape(TOGGLE_HEIGHT / 2),
            ).padding(horizontal = 3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(KNOB)
                .background(
                    if (on) scheme.onPrimary else scheme.onSurfaceVariant,
                    RoundedCornerShape(KNOB / 2),
                ),
        )
    }
}

@Composable
private fun Footnote(state: SettingsState) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(HAIRLINE).background(scheme.surfaceContainerHigh))
        Row(
            Modifier
                .fillMaxWidth()
                .background(FOOTNOTE_GROUND)
                .padding(horizontal = EDGE, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Glyph(Icons.BOLT, size = FOOTNOTE_GLYPH, tint = scheme.onSurfaceVariant)
            Text(state.footnote, style = NOTE.copy(fontSize = 11.sp), color = scheme.onSurfaceVariant)
            if (state.footnotePlanned) PlannedBadge()
        }
    }
}

@Composable
private fun PlannedBadge() {
    Box(
        Modifier
            .border(HAIRLINE, MaterialTheme.warningColors.warningContainer, RoundedCornerShape(2.dp))
            .padding(horizontal = 3.dp),
    ) {
        Text(
            "planned",
            style = ChromeText.copy(fontSize = BADGE),
            color = MaterialTheme.warningColors.warning,
        )
    }
}

private val EDGE = 20.dp

private val HAIRLINE = 1.dp

private val FIELD_HEIGHT = 30.dp

private val FIELD_WIDTH = 76.dp

private val WIDE_FIELD = 86.dp

private val UNIT_WIDTH = 38.dp

private val PATH_WIDTH = 210.dp

private val FOLDER_GLYPH = 16.sp

private val TOGGLE_WIDTH = 34.dp

private val TOGGLE_HEIGHT = 20.dp

private val KNOB = 14.dp

private val FOOTNOTE_GLYPH = 15.sp

/** A shade above the surface and below the raised one: the screen's own last line. */
private val FOOTNOTE_GROUND = Color(0xFF111716)

private val SECTION_TRACKING = 0.08.em

private val LABEL = ChromeText.copy(fontSize = 12.5.sp)

private val NOTE = ChromeText.copy(fontSize = 10.5.sp)

private val DEFAULT = MonoSmall.copy(fontSize = 10.5.sp)

private val BADGE = 9.5.sp
