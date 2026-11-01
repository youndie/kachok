package io.github.youndie.kachok.ui.add

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.youndie.kachok.ui.icons.Glyph
import io.github.youndie.kachok.ui.icons.Icons
import io.github.youndie.kachok.ui.session.Figures
import io.github.youndie.kachok.ui.theme.ChromeButton
import io.github.youndie.kachok.ui.theme.ChromeText
import io.github.youndie.kachok.ui.theme.DialogButton
import io.github.youndie.kachok.ui.theme.KachokTheme
import io.github.youndie.kachok.ui.theme.KitChoice
import io.github.youndie.kachok.ui.theme.MonoSmall
import io.github.youndie.kachok.ui.theme.RowName
import io.github.youndie.kachok.ui.theme.Text

/** One file inside a torrent, as the dialog lists it. */
internal data class AddFile(
    val name: String,
    val size: String,
    /** The same number the [size] prints, kept so the summary can add them up. */
    val bytes: Long,
    val wanted: Boolean,
)

/**
 * What the dialog is showing about the thing that was just dropped, pasted or opened.
 *
 * Built by `addFrom` out of a `.torrent`'s bytes or a magnet URI, so the dialog cannot show a
 * detail the source did not carry — which is the whole point of the magnet case.
 */
internal data class AddTorrentState(
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
    internal fun savingTo(path: String): AddTorrentState = copy(saveTo = path)

    /** The same recognition, with the button off and a reason beside it. */
    internal fun refused(reason: String): AddTorrentState = copy(canAdd = false, whyNot = reason)

    /**
     * One file ticked or unticked, and the summary line kept honest about it.
     *
     * The size in the summary is the *wanted* size, because that is the number a person is deciding
     * about: unticking the 3.6 GiB ISO out of a 3.7 GiB torrent has to be visible somewhere other
     * than on the row itself.
     */
    internal fun sequentially(on: Boolean): AddTorrentState = copy(sequential = on)

    internal fun withFile(
        index: Int,
        wanted: Boolean,
    ): AddTorrentState {
        val changed = files.mapIndexed { at, file -> if (at == index) file.copy(wanted = wanted) else file }
        return copy(files = changed, wantedSummary = wantedSummaryOf(changed))
    }

    internal companion object {
        /** The label is the handle: the checkbox has no text of its own for a test to reach. */
        internal const val SEQUENTIAL: String = "Sequential download"

        /** `8 of 9 wanted · 3.61 GiB`, which is the design's own line. */
        internal fun wantedSummaryOf(files: List<AddFile>): String =
            "${files.count { it.wanted }} of ${files.size} wanted" +
                " · ${Figures.bytes(files.filter { it.wanted }.sumOf { it.bytes })}"
    }
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
    onFile: (Int, Boolean) -> Unit = { _, _ -> },
    onSequential: (Boolean) -> Unit = {},
) {
    // The frame is the kit's since B-178, and so is the body's margin from it.
    KachokTheme.kit.DialogFrame("Add torrent", DIALOG_WIDTH, modifier) { edge ->
        SourceCard(state, edge)
        SaveTo(state, edge, onBrowse)
        if (state.files.isNotEmpty()) Files(state, edge, onFile)
        Sequential(state, edge, onSequential)
        StartMode(state, edge)
        Row(
            Modifier.fillMaxWidth().padding(start = edge, end = edge, top = ACTIONS_TOP, bottom = edge),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.whyNot?.let {
                Text(
                    it,
                    style = NOTE,
                    color = KachokTheme.colors.warning,
                    modifier = Modifier.weight(1f),
                )
            }
            DialogButton("Cancel", primary = false, onClick = onCancel)
            DialogButton("Add", primary = true, enabled = state.canAdd, onClick = onAdd)
        }
    }
}

@Composable
private fun SourceCard(
    state: AddTorrentState,
    edge: Dp,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = edge)
            .background(KachokTheme.colors.neutralCard, RoundedCornerShape(4.dp))
            .border(HAIRLINE, KachokTheme.colors.outline, RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Glyph(
            if (state.magnet) Icons.LINK else Icons.DESCRIPTION,
            size = SOURCE_GLYPH,
            tint = KachokTheme.colors.accentText,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                state.source,
                style = ChromeButton,
                color = KachokTheme.colors.text,
            )
            Text(state.summary, style = CARD_MONO, color = KachokTheme.colors.textMuted)
            Text(state.hash, style = CARD_MONO, color = KachokTheme.colors.textFaint)
        }
    }
}

/** The one decision this dialog exists to take: the kit's field and *Browse…* since B-178. */
@Composable
private fun SaveTo(
    state: AddTorrentState,
    edge: Dp,
    onBrowse: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().padding(start = edge, end = edge, top = 16.dp)) {
        KachokTheme.kit.SaveToField("Save to", state.saveTo, state.defaultNote, onBrowse)
    }
}

@Composable
private fun Files(
    state: AddTorrentState,
    edge: Dp,
    onFile: (Int, Boolean) -> Unit,
) {
    val scheme = KachokTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(start = edge, end = edge, top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Files", style = SECTION_LABEL, color = KachokTheme.colors.textMuted)
            Box(Modifier.weight(1f))
            Text(state.wantedSummary, style = CARD_MONO, color = scheme.textFaint)
        }
        // Scrollable, because the box is four rows high and a torrent has as many files as it has.
        // Without this the fifth file and everything after it was drawn outside the box and could
        // not be reached at all — found by a test that ticked all nine and heard from four.
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = FILE_LIST_HEIGHT)
                .background(KachokTheme.colors.neutralCard, RoundedCornerShape(4.dp))
                .border(HAIRLINE, scheme.outline, RoundedCornerShape(4.dp))
                .verticalScroll(rememberScrollState()),
        ) {
            state.files.forEachIndexed { index, file ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(FILE_ROW)
                        .toggleable(value = file.wanted, role = Role.Checkbox) { onFile(index, it) }
                        .semantics { contentDescription = file.name }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Glyph(
                        if (file.wanted) Icons.CHECK_BOX else Icons.CHECK_BOX_OUTLINE_BLANK,
                        size = LIST_GLYPH,
                        tint = if (file.wanted) scheme.accentText else scheme.textFaint,
                    )
                    Text(
                        file.name,
                        style = ChromeText.copy(fontSize = 11.5.sp),
                        color = if (file.wanted) scheme.text else scheme.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        file.size,
                        style = CARD_MONO,
                        color = if (file.wanted) KachokTheme.colors.textMuted else scheme.textFaint,
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
private fun Sequential(
    state: AddTorrentState,
    edge: Dp,
    onSequential: (Boolean) -> Unit,
) {
    // The kit's checkbox since B-183.
    KachokTheme.kit.Check(
        AddTorrentState.SEQUENTIAL,
        note =
            "Ask for pieces in order rather than rarest first. Slower overall, and it makes " +
                "this client a worse swarm member.",
        path = false,
        danger = false,
        checked = state.sequential,
        onCheckedChange = onSequential,
        modifier = Modifier.fillMaxWidth().padding(start = edge, end = edge, top = 16.dp),
    )
}

/**
 * One choice, and one that is not a choice yet.
 *
 * *Add paused* needs a paused state the engine does not have
 * ([B-57](../../../../../../../../docs/backlog/B-57-a-paused-torrent.md)), so it is shown and not
 * offered — rather than moving the dot to an option that would then start the torrent anyway. The
 * Material kit draws the design's *planned* badge beside it; the oldge kit, the library's disabled
 * option with the reason under it (B-183).
 */
@Composable
private fun StartMode(
    state: AddTorrentState,
    edge: Dp,
) {
    KachokTheme.kit.Choices(
        "When it is added",
        START_MODES,
        selected = if (state.startImmediately) 0 else 1,
        onSelect = {},
        modifier = Modifier.fillMaxWidth().padding(start = edge, end = edge, top = 16.dp),
    )
}

private val START_MODES =
    listOf(
        KitChoice("Start immediately"),
        KitChoice("Add paused", planned = "Planned: the engine has no paused state yet."),
    )

/**
 * The window while something is being dragged over it.
 *
 * The whole window is the target, not a strip of it: a person dragging a file has no reason to aim,
 * and a small target is a gesture that fails silently. It names what it would add, because a drop
 * of the wrong two files is a mistake that is cheap to prevent and expensive to undo — when the
 * platform will say the names before the drop, which macOS will not ([UNNAMED_DROP]); then it says
 * only that a drop would add a torrent, rather than staying invisible until it is too late.
 */
@Composable
internal fun DropOverlay(
    names: List<String>,
    modifier: Modifier = Modifier,
) {
    val named = names.filter { it != UNNAMED_DROP }
    val scheme = KachokTheme.colors
    val primary = scheme.accentText
    Box(
        modifier
            .fillMaxSize()
            .padding(8.dp)
            .background(scheme.accentContainer.copy(alpha = OVERLAY_ALPHA), RoundedCornerShape(4.dp))
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
            Glyph(Icons.DOWNLOAD, size = OVERLAY_GLYPH, tint = scheme.accentText)
            Text(
                when {
                    named.isEmpty() -> "Drop to add a torrent"
                    named.size == 1 -> "Drop to add 1 torrent"
                    else -> "Drop to add ${named.size} torrents"
                },
                style = KachokTheme.type.headline.copy(fontSize = 15.sp),
                color = scheme.onAccentContainer,
            )
            if (named.isNotEmpty()) {
                Text(
                    named.joinToString(", "),
                    style = CARD_MONO,
                    color = KachokTheme.colors.accentBright,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
    val scheme = KachokTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(scheme.raisedHighest, RoundedCornerShape(4.dp))
            .border(HAIRLINE, scheme.dialogBorder, RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Glyph(Icons.LINK, size = CONTROL_GLYPH, tint = scheme.accentText)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("A magnet link is on the clipboard.", style = ChromeText, color = scheme.text)
            Text(
                link,
                style = MonoSmall.copy(fontSize = 10.5.sp),
                color = scheme.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .height(PROMPT_BUTTON)
                .background(scheme.accentContainer, RoundedCornerShape(4.dp))
                .clickable(role = Role.Button, onClick = onAdd)
                .padding(horizontal = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Add it",
                style = ChromeText.copy(fontWeight = FontWeight.Medium),
                color = KachokTheme.colors.accentBright,
            )
        }
        Box(
            Modifier
                .height(PROMPT_BUTTON)
                .width(PROMPT_BUTTON)
                .clickable(role = Role.Button, onClick = onDismiss)
                .semantics { contentDescription = "Dismiss" },
            contentAlignment = Alignment.Center,
        ) {
            Glyph(Icons.CLOSE, size = CONTROL_GLYPH, tint = scheme.textFaint)
        }
    }
}

private val DIALOG_WIDTH = 520.dp

/** Above the dialog's buttons: the design's 20, whatever the kit's side margin is. */
private val ACTIONS_TOP = 20.dp

private val HAIRLINE = 1.dp

private val PROMPT_BUTTON = 28.dp

private val FILE_ROW = 24.dp

private val FILE_LIST_HEIGHT = 96.dp

private val FILE_SIZE_COLUMN = 58.dp

private val SOURCE_GLYPH = 19.sp

private val LIST_GLYPH = 16.sp

private val CONTROL_GLYPH = 18.sp

private val OVERLAY_GLYPH = 26.sp

/** The design's dimmed 22 % teal over whatever the window is showing underneath. */
private const val OVERLAY_ALPHA = 0.22f

private val OVERLAY_RADIUS = 4.dp

private const val DASH_ON = 5f

private const val DASH_OFF = 4f

private val SECTION_LABEL = ChromeText.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium)

private val CARD_MONO = MonoSmall.copy(fontSize = 11.sp)

private val NOTE = ChromeText.copy(fontSize = 10.5.sp)
