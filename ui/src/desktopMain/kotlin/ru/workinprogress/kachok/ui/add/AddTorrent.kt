package ru.workinprogress.kachok.ui.add

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.workinprogress.kachok.ui.icons.Glyph
import ru.workinprogress.kachok.ui.icons.Icons
import ru.workinprogress.kachok.ui.theme.ChromeButton
import ru.workinprogress.kachok.ui.theme.ChromeText
import ru.workinprogress.kachok.ui.theme.DialogButton
import ru.workinprogress.kachok.ui.theme.KachokPalette
import ru.workinprogress.kachok.ui.theme.MonoSmall
import ru.workinprogress.kachok.ui.theme.PathText
import ru.workinprogress.kachok.ui.theme.RowName
import ru.workinprogress.kachok.ui.theme.warningColors

/** One file inside a torrent, as the dialog lists it. */
internal class AddFile(
    val name: String,
    val size: String,
    val wanted: Boolean,
)

/**
 * What the dialog is showing about the thing that was just dropped, pasted or opened.
 *
 * Built by `addFrom` out of a `.torrent`'s bytes or a magnet URI, so the dialog cannot show a
 * detail the source did not carry — which is the whole point of the magnet case.
 */
internal class AddTorrentState(
    val source: String,
    /** `3.70 GiB · 1 772 pieces of 2.00 MiB · 9 files`, or what a magnet can say instead. */
    val summary: String,
    val hash: String,
    val magnet: Boolean,
    val saveTo: String,
    val defaultNote: String,
    val files: List<AddFile>,
    val wantedSummary: String,
    val sequential: Boolean = false,
    val startImmediately: Boolean = true,
    /**
     * Whether *Add* can do anything, and what to say when it cannot.
     *
     * A greyed button with a sentence beside it is the dialog admitting a gap; a live one that
     * silently did nothing would not be. Today the one gap is a magnet, whose metainfo the window
     * has no `MetadataFetcher` in front of a session to fetch.
     */
    val canAdd: Boolean = true,
    val whyNot: String? = null,
) {
    /** The same recognition, saved somewhere else. */
    internal fun savingTo(path: String): AddTorrentState =
        AddTorrentState(
            source = source,
            summary = summary,
            hash = hash,
            magnet = magnet,
            saveTo = path,
            defaultNote = defaultNote,
            files = files,
            wantedSummary = wantedSummary,
            sequential = sequential,
            startImmediately = startImmediately,
            canAdd = canAdd,
            whyNot = whyNot,
        )

    /** The same recognition, with the button off and a reason beside it. */
    internal fun refused(reason: String): AddTorrentState =
        AddTorrentState(
            source = source,
            summary = summary,
            hash = hash,
            magnet = magnet,
            saveTo = saveTo,
            defaultNote = defaultNote,
            files = files,
            wantedSummary = wantedSummary,
            sequential = sequential,
            startImmediately = startImmediately,
            canAdd = false,
            whyNot = reason,
        )
}

/**
 * One gesture, then one dialog.
 *
 * There is one decision here — where to save — and the design gives it the only editable control
 * on the screen. Everything above it is what was recognised, and everything below it is marked
 * `planned`: file selection and sequential download both need engine support that does not exist,
 * and *add paused* needs a paused state the engine does not have.
 *
 * **A magnet is shown as what it is.** It carries a hash and, if it is lucky, a display name; it
 * carries no size and no file list, and the dialog says the metainfo is fetched from the swarm
 * first rather than leaving three blanks.
 */
@Composable
internal fun AddTorrentDialog(
    state: AddTorrentState,
    modifier: Modifier = Modifier,
    onCancel: () -> Unit = {},
    onAdd: () -> Unit = {},
    onBrowse: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .width(DIALOG_WIDTH)
            .background(scheme.surfaceContainerHigh, RoundedCornerShape(6.dp))
            .border(HAIRLINE, DIALOG_BORDER, RoundedCornerShape(6.dp)),
    ) {
        Text(
            "Add torrent",
            style = MaterialTheme.typography.headlineSmall,
            color = scheme.onSurface,
            modifier = Modifier.padding(start = EDGE, end = EDGE, top = 18.dp, bottom = 14.dp),
        )
        SourceCard(state)
        SaveTo(state, onBrowse)
        if (state.files.isNotEmpty()) Files(state)
        Sequential(state)
        StartMode(state)
        Row(
            Modifier.fillMaxWidth().padding(EDGE),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.whyNot?.let {
                Text(
                    it,
                    style = NOTE,
                    color = MaterialTheme.warningColors.warning,
                    modifier = Modifier.weight(1f),
                )
            }
            DialogButton("Cancel", primary = false, onClick = onCancel)
            DialogButton("Add", primary = true, enabled = state.canAdd, onClick = onAdd)
        }
    }
}

@Composable
private fun SourceCard(state: AddTorrentState) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = EDGE)
            .background(KachokPalette.neutralCard, RoundedCornerShape(4.dp))
            .border(HAIRLINE, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Glyph(
            if (state.magnet) Icons.LINK else Icons.DESCRIPTION,
            size = SOURCE_GLYPH,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                state.source,
                style = ChromeButton,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(state.summary, style = CARD_MONO, color = KachokPalette.onSurfaceMuted)
            Text(state.hash, style = CARD_MONO, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SaveTo(
    state: AddTorrentState,
    onBrowse: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(start = EDGE, end = EDGE, top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Save to", style = SECTION_LABEL, color = KachokPalette.onSurfaceMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier
                    .weight(1f)
                    .height(FIELD_HEIGHT)
                    .border(HAIRLINE, scheme.outline, RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Glyph(Icons.FOLDER, size = FIELD_GLYPH, tint = scheme.onSurfaceVariant)
                PathText(
                    state.saveTo,
                    MonoSmall.copy(fontSize = 12.sp),
                    scheme.onSurface,
                    Modifier.weight(1f),
                    textAlign = TextAlign.Start,
                )
            }
            Box(
                Modifier
                    .height(FIELD_HEIGHT)
                    .border(HAIRLINE, scheme.outline, RoundedCornerShape(4.dp))
                    // The one decision this dialog exists to take. It was a bordered box.
                    .clickable(onClick = onBrowse)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Browse…", style = ChromeButton, color = scheme.onSurface, maxLines = 1)
            }
        }
        Text(state.defaultNote, style = NOTE, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun Files(state: AddTorrentState) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(start = EDGE, end = EDGE, top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Files", style = SECTION_LABEL, color = KachokPalette.onSurfaceMuted)
            PlannedBadge()
            Box(Modifier.weight(1f))
            Text(state.wantedSummary, style = CARD_MONO, color = scheme.onSurfaceVariant)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = FILE_LIST_HEIGHT)
                .background(KachokPalette.neutralCard, RoundedCornerShape(4.dp))
                .border(HAIRLINE, scheme.outline, RoundedCornerShape(4.dp)),
        ) {
            state.files.forEach { file ->
                Row(
                    Modifier.fillMaxWidth().height(FILE_ROW).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Glyph(
                        if (file.wanted) Icons.CHECK_BOX else Icons.CHECK_BOX_OUTLINE_BLANK,
                        size = LIST_GLYPH,
                        tint = if (file.wanted) scheme.primary else scheme.onSurfaceVariant,
                    )
                    Text(
                        file.name,
                        style = ChromeText.copy(fontSize = 11.5.sp),
                        color = if (file.wanted) scheme.onSurface else scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        file.size,
                        style = CARD_MONO,
                        color = if (file.wanted) KachokPalette.onSurfaceMuted else scheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.width(FILE_SIZE_COLUMN),
                    )
                }
            }
        }
    }
}

@Composable
private fun Sequential(state: AddTorrentState) {
    Row(
        Modifier.fillMaxWidth().padding(start = EDGE, end = EDGE, top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Glyph(
            if (state.sequential) Icons.CHECK_BOX else Icons.CHECK_BOX_OUTLINE_BLANK,
            size = CONTROL_GLYPH,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Sequential download", style = CHOICE, color = MaterialTheme.colorScheme.onSurface)
                PlannedBadge()
            }
            Text(
                "Ask for pieces in order rather than rarest first. Slower overall, and it makes " +
                    "this client a worse swarm member.",
                style = NOTE,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One choice, and one that is not a choice yet.
 *
 * *Add paused* needs a paused state the engine does not have
 * ([B-57](../../../../../../../../docs/backlog/B-57-a-paused-torrent.md)), so it carries the
 * design's badge and does not respond — rather than moving the dot to an option that would then
 * start the torrent anyway.
 */
@Composable
private fun StartMode(state: AddTorrentState) {
    Column(
        Modifier.fillMaxWidth().padding(start = EDGE, end = EDGE, top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Choice("Start immediately", on = state.startImmediately)
        Choice("Add paused", on = !state.startImmediately, planned = true)
    }
}

@Composable
private fun Choice(
    label: String,
    on: Boolean,
    planned: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Glyph(
            if (on) Icons.RADIO_BUTTON_CHECKED else Icons.RADIO_BUTTON_UNCHECKED,
            size = CONTROL_GLYPH,
            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            label,
            style = CHOICE,
            color = if (on) MaterialTheme.colorScheme.onSurface else KachokPalette.onSurfaceMuted,
        )
        if (planned) PlannedBadge()
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
            maxLines = 1,
        )
    }
}

/**
 * The window while something is being dragged over it.
 *
 * The whole window is the target, not a strip of it: a person dragging a file has no reason to aim,
 * and a small target is a gesture that fails silently. It names what it would add, because a drop
 * of the wrong two files is a mistake that is cheap to prevent and expensive to undo.
 */
@Composable
internal fun DropOverlay(
    names: List<String>,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val primary = scheme.primary
    Box(
        modifier
            .fillMaxSize()
            .padding(8.dp)
            .background(scheme.primaryContainer.copy(alpha = OVERLAY_ALPHA), RoundedCornerShape(4.dp))
            // Dashed, which is the design's way of saying the edge is a target rather than a
            // component: a solid one reads as a panel that is already there.
            .drawBehind {
                drawRoundRect(
                    color = primary,
                    size = size,
                    cornerRadius = CornerRadius(OVERLAY_RADIUS.toPx()),
                    style =
                        Stroke(
                            width = HAIRLINE.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH_ON, DASH_OFF)),
                        ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Glyph(Icons.DOWNLOAD, size = OVERLAY_GLYPH, tint = scheme.primary)
            Text(
                "Drop to add ${names.size} ${if (names.size == 1) "torrent" else "torrents"}",
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 15.sp),
                color = scheme.onPrimaryContainer,
            )
            Text(
                names.joinToString(", "),
                style = CARD_MONO,
                color = KachokPalette.primaryBright,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A magnet is on the clipboard, and the window noticed when it came back into focus.
 *
 * An offer rather than an action: reading the clipboard is not consent to download what is in it,
 * so the prompt shows the link and waits. It is dismissible, and dismissing it is remembered for
 * that link — otherwise it is a prompt that reappears every time the window is focused.
 */
@Composable
internal fun ClipboardMagnetPrompt(
    link: String,
    modifier: Modifier = Modifier,
    onAdd: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainerHighest, RoundedCornerShape(4.dp))
            .border(HAIRLINE, DIALOG_BORDER, RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Glyph(Icons.LINK, size = CONTROL_GLYPH, tint = scheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("A magnet link is on the clipboard.", style = ChromeText, color = scheme.onSurface)
            Text(
                link,
                style = MonoSmall.copy(fontSize = 10.5.sp),
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .height(PROMPT_BUTTON)
                .background(scheme.primaryContainer, RoundedCornerShape(4.dp))
                .clickable(onClick = onAdd)
                .padding(horizontal = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Add it", style = ChromeText.copy(fontWeight = FontWeight.Medium), color = KachokPalette.primaryBright)
        }
        Box(
            Modifier.height(PROMPT_BUTTON).width(PROMPT_BUTTON).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Glyph(Icons.CLOSE, size = CONTROL_GLYPH, tint = scheme.onSurfaceVariant)
        }
    }
}

private val DIALOG_WIDTH = 520.dp

/** One step above `outline`: the dialog sits on the raised surface and needs an edge on it. */
private val DIALOG_BORDER = Color(0xFF303836)

private val EDGE = 20.dp

private val HAIRLINE = 1.dp

private val FIELD_HEIGHT = 36.dp

private val BUTTON_HEIGHT = 32.dp

private val PROMPT_BUTTON = 28.dp

private val FILE_ROW = 24.dp

private val FILE_LIST_HEIGHT = 96.dp

private val FILE_SIZE_COLUMN = 58.dp

private val SOURCE_GLYPH = 19.sp

private val FIELD_GLYPH = 17.sp

private val LIST_GLYPH = 16.sp

private val CONTROL_GLYPH = 18.sp

private val OVERLAY_GLYPH = 26.sp

private val BADGE = 9.5.sp

/** The design's dimmed 22 % teal over whatever the window is showing underneath. */
private const val OVERLAY_ALPHA = 0.22f

private val OVERLAY_RADIUS = 4.dp

private const val DASH_ON = 5f

private const val DASH_OFF = 4f

private val SECTION_LABEL = ChromeText.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium)

private val CARD_MONO = MonoSmall.copy(fontSize = 11.sp)

private val NOTE = ChromeText.copy(fontSize = 10.5.sp)

private val CHOICE = ChromeText.copy(fontSize = 12.5.sp)
