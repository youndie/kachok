package ru.workinprogress.kachok.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.workinprogress.kachok.ui.icons.Glyph
import ru.workinprogress.kachok.ui.icons.Icons
import ru.workinprogress.kachok.ui.list.RowCell
import ru.workinprogress.kachok.ui.list.TorrentState
import ru.workinprogress.kachok.ui.list.stateLabelColor
import ru.workinprogress.kachok.ui.theme.ChromeText
import ru.workinprogress.kachok.ui.theme.KachokPalette
import ru.workinprogress.kachok.ui.theme.MonoSmall
import ru.workinprogress.kachok.ui.theme.PathText
import ru.workinprogress.kachok.ui.theme.RowName
import ru.workinprogress.kachok.ui.theme.RowStateLabel
import ru.workinprogress.kachok.ui.theme.warningColors
import java.awt.Cursor
import kotlin.time.Duration.Companion.milliseconds

/**
 * The four tabs, in the design's order.
 *
 * Three of them carried a `plannedBecause` — the engine change they were waiting for, printed where
 * their rows would be. All four draw the session now, so the field and the placeholder it fed are
 * gone; what replaced them is a per-tab test that the words are on the screen.
 */
internal enum class DetailsTab(
    val label: String,
) {
    Overview("Overview"),
    Files("Files"),
    Peers("Peers"),
    Trackers("Trackers"),
}

/** How much a value is allowed to stand out. The design uses exactly three levels here. */
internal enum class FieldTone { Plain, Good, Warning }

internal class DetailsField(
    val label: String,
    val value: String,
    val tone: FieldTone = FieldTone.Plain,
    /** The design's own badge: the field is drawn, and it says where the number came from. */
    val planned: Boolean = false,
    val copyable: Boolean = false,
    /**
     * What the copy button puts on the clipboard.
     *
     * Not the same string as [value] for the info hash: the panel shows the first and last five
     * characters because forty do not fit, and a person copying it wants the forty.
     */
    val copyText: String = value,
    /** A path: identified by its end, so it is elided from the front rather than the back. */
    val path: Boolean = false,
)

internal class DetailsSection(
    val title: String,
    val fields: List<DetailsField>,
)

/** A complaint, in whoever's words made it. */
internal class Complaint(
    val title: String,
    val text: String,
    val warning: Boolean,
)

/**
 * One announce URL, as the design's *Trackers* tab draws it.
 *
 * [status] is a word and a colour, not a boolean: *not tried* is a third thing, and it is what most
 * of a torrent's trackers are — BEP 12 says a client uses the first one that answers.
 */
internal class TrackerRow(
    val url: String,
    val status: String,
    val tone: FieldTone,
    /** `3 m ago · 142 peers · next in 27 m`, or what is known of it. */
    val detail: String,
    /** The tracker's own words, when it refused. */
    val message: String? = null,
)

/**
 * One file, as the design's *Files* tab draws it.
 *
 * The tick is live now; the panel does not draw it as a control because changing the selection on a
 * running torrent needs the picker to give back pieces it has started — the not-covered half of
 * [B-67](../../../../../../../../docs/backlog/B-67-per-file-selection.md). The tick a person can
 * press is in the add dialog.
 */
internal class FileRow(
    val name: String,
    val size: String,
    /** `79%`, or `skip` for a file this client is not fetching. */
    val progress: String,
    val wanted: Boolean,
)

/**
 * One peer, as the design's *Peers* tab draws it.
 *
 * The flags are two booleans and not a string: the design gives each its own colour, and a
 * pre-joined `"U I"` would have to be taken apart again to draw it.
 */
internal class PeerRow(
    val address: String,
    val client: String,
    /** They are not choking us, so something can actually be asked of them. */
    val unchoked: Boolean,
    /** We want something they have. */
    val interested: Boolean,
    /** `1 842`, grouped the way every other figure in this window is. */
    val rate: String,
)

internal class DetailsState(
    val name: String,
    val state: TorrentState,
    val stateLabel: String,
    val summary: String,
    val sections: List<DetailsSection>,
    val complaints: List<Complaint>,
    val sessionError: String?,
    val tab: DetailsTab = DetailsTab.Overview,
    /** Sorted by rate, which is what puts the peers doing something at the top. */
    val peers: List<PeerRow> = emptyList(),
    /** In the torrent's own order, which is the order the design lists them in. */
    val files: List<FileRow> = emptyList(),
    /** `9 files · 3.70 GiB · 8 wanted`. */
    val filesSummary: String = "",
    /** In the metainfo's own order, so a row does not move when a tracker fails. */
    val trackers: List<TrackerRow> = emptyList(),
    /** `3 trackers + DHT`. */
    val trackersSummary: String = "",
    /** The DHT's own line, or null when it is off. */
    val dht: String? = null,
)

internal object Details {
    /** The design draws it at 340; the item's 280–520 is what a drag would be allowed to make it. */
    val width: Dp = 340.dp
    val minimumWidth: Dp = 280.dp
    val maximumWidth: Dp = 520.dp

    val tabHeight: Dp = 32.dp
    val rowHeight: Dp = 24.dp
    val edge: Dp = 12.dp
}

/**
 * The right-hand panel: what one torrent is, in more words than a row has room for.
 *
 * **Recessed, not raised.** There is no elevation anywhere in this design, so the panel is a
 * darker ground with a hairline rather than a surface with a shadow.
 *
 * *Overview* is built from `SessionState` and every line of it is real. The other three are drawn
 * as what they are — waiting on the engine — rather than filled with numbers nothing produced,
 * which is the one thing worse than an empty tab.
 */
@Composable
internal fun DetailsPanel(
    state: DetailsState,
    modifier: Modifier = Modifier,
    onTab: (DetailsTab) -> Unit = {},
    onCopy: (String) -> Unit = {},
    onAnnounce: () -> Unit = {},
    /** Where the drag has put the edge, clamped by the caller to [Details.minimumWidth]..[Details.maximumWidth]. */
    width: Dp = Details.width,
    onResize: (Dp) -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    Row(modifier.width(width + HAIRLINE)) {
        // The hairline between the list and the panel *is* the handle. A separate grab strip would
        // be either invisible or a second line the design does not draw; this widens the pointer's
        // reach instead of the line, so what is drawn is the design's one pixel and what can be
        // caught is eight.
        Box(
            Modifier
                .width(HAIRLINE)
                .fillMaxHeight()
                .background(scheme.outlineVariant)
                .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                .draggable(
                    orientation = Orientation.Horizontal,
                    state =
                        rememberDraggableState { delta ->
                            // Dragging the edge left makes the panel wider: the panel is on the
                            // right, so its width grows as the divider moves the other way.
                            onResize(width - with(density) { delta.toDp() })
                        },
                ).semantics { contentDescription = "Resize the details panel" },
        )
        Column(Modifier.fillMaxSize().background(KachokPalette.panel)) {
            Header(state)
            Tabs(state.tab, onTab)
            when (state.tab) {
                DetailsTab.Overview -> Overview(state, onCopy)
                DetailsTab.Peers -> Peers(state.peers)
                DetailsTab.Files -> Files(state)
                DetailsTab.Trackers -> Trackers(state, onAnnounce)
            }
        }
    }
}

@Composable
private fun ColumnScope.Header(state: DetailsState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Details.edge, vertical = 10.dp)) {
        Text(
            state.name,
            style = RowName.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            // Broken anywhere rather than ellipsized: a torrent's name is how somebody recognises
            // it, and the half that identifies it is as often at the end as at the start.
            softWrap = true,
        )
        Row(
            Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                state.stateLabel,
                style = RowStateLabel,
                color = stateLabelColor(state.state),
                maxLines = 1,
            )
            Text("·", style = MonoSmall, color = MaterialTheme.colorScheme.outline)
            Text(
                state.summary,
                style = MonoSmall,
                color = KachokPalette.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(HAIRLINE).background(MaterialTheme.colorScheme.surfaceContainerHigh))
}

@Composable
private fun ColumnScope.Tabs(
    selected: DetailsTab,
    onTab: (DetailsTab) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(Details.tabHeight).padding(horizontal = 6.dp)) {
        DetailsTab.entries.forEach { tab ->
            val on = tab == selected
            Column(
                Modifier
                    // The indicator is as wide as the label and no wider, and the only way to say
                    // that is to measure the column by what is in it: `fillMaxWidth` on the
                    // indicator otherwise takes the whole tab row and the first tab eats the rest.
                    .width(IntrinsicSize.Max)
                    .clickable { onTab(tab) }
                    .padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    tab.label,
                    style =
                        if (on) {
                            ChromeText.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                        } else {
                            ChromeText
                        },
                    color =
                        if (on) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    maxLines = 1,
                    modifier = Modifier.padding(top = 8.dp, bottom = 7.dp),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(INDICATOR)
                        .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent),
                )
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(HAIRLINE).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun ColumnScope.Overview(
    state: DetailsState,
    onCopy: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = Details.edge, end = Details.edge, bottom = 14.dp),
    ) {
        state.sections.forEachIndexed { index, section ->
            SectionHead(section.title, first = index == 0)
            section.fields.forEach { Field(it, onCopy) }
        }
        if (state.complaints.isNotEmpty() || state.sessionError != null) {
            SectionHead("LAST COMPLAINTS", first = false)
            state.complaints.forEach { ComplaintCard(it) }
            Row(
                Modifier.fillMaxWidth().height(Details.rowHeight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Session", style = FIELD_LABEL, color = KachokPalette.onSurfaceMuted)
                Text(
                    state.sessionError ?: "no error",
                    style = MonoSmall,
                    color =
                        if (state.sessionError == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
            }
        }
    }
}

@Composable
private fun SectionHead(
    title: String,
    first: Boolean,
) {
    Text(
        title,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = SECTION_TRACKING),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = if (first) 12.dp else 14.dp, bottom = 5.dp),
    )
}

@Composable
private fun Field(
    field: DetailsField,
    onCopy: (String) -> Unit = {},
) {
    Column {
        Row(
            Modifier.fillMaxWidth().height(Details.rowHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(field.label, style = FIELD_LABEL, color = KachokPalette.onSurfaceMuted, maxLines = 1)
                if (field.planned) PlannedBadge()
            }
            Row(
                // The value takes the slack and the label keeps its width, so the two can never
                // run together — `Save to /private/tmp/...` with no gap was what they did.
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.End),
            ) {
                val tone =
                    when (field.tone) {
                        FieldTone.Plain -> MaterialTheme.colorScheme.onSurface
                        FieldTone.Good -> MaterialTheme.colorScheme.primary
                        FieldTone.Warning -> MaterialTheme.warningColors.warning
                    }
                if (field.path) {
                    PathText(field.value, MonoSmall, tone, Modifier.weight(1f, fill = false))
                } else {
                    Text(
                        field.value,
                        style = MonoSmall,
                        color = tone,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                    )
                }
                if (field.copyable) {
                    CopyButton(field, onCopy)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(HAIRLINE).background(KachokPalette.rowHairline))
    }
}

/**
 * The design's own badge.
 *
 * It marks a field whose number the engine does not produce — and it is drawn beside the number
 * rather than instead of it, because the field's *shape* is decided and only its source is not.
 */
@Composable
private fun PlannedBadge() {
    Box(
        Modifier
            .border(HAIRLINE, MaterialTheme.warningColors.warningContainer, RoundedCornerShape(2.dp))
            .padding(horizontal = 3.dp),
    ) {
        Text(
            "planned",
            style = ChromeText.copy(fontSize = BADGE_SIZE),
            color = MaterialTheme.warningColors.warning,
            maxLines = 1,
        )
    }
}

@Composable
private fun ComplaintCard(complaint: Complaint) {
    val scheme = MaterialTheme.colorScheme
    val warning = MaterialTheme.warningColors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .background(
                if (complaint.warning) KachokPalette.warningCard else KachokPalette.neutralCard,
                RoundedCornerShape(4.dp),
            ).border(
                HAIRLINE,
                if (complaint.warning) warning.warningContainer else scheme.outline,
                RoundedCornerShape(4.dp),
            ).padding(horizontal = 9.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            complaint.title,
            style = ChromeText.copy(fontSize = BADGE_SIZE, letterSpacing = CARD_TRACKING),
            color = if (complaint.warning) warning.warning else scheme.onSurfaceVariant,
        )
        Text(
            complaint.text,
            style = MonoSmall.copy(fontSize = CARD_TEXT, lineHeight = CARD_LINE),
            color = if (complaint.warning) KachokPalette.onWarningCard else KachokPalette.onSurfaceMuted,
        )
    }
}

/**
 * A card per announce URL, and one for the DHT.
 *
 * **Cards and not table rows.** A tracker's complaint is a sentence in somebody else's words —
 * `announce failed: 502 Bad Gateway` — and it does not fit a column. The design draws each tracker
 * as a block with its URL, a status word and a line of figures, which is what a variable-length
 * message needs.
 *
 * The engine's own rule shows through: BEP 12 says a client uses the first tracker that answers, so
 * the others read *not tried* rather than pretending to be in use.
 */
@Composable
private fun ColumnScope.Trackers(
    state: DetailsState,
    onAnnounce: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(PEER_HEAD).padding(horizontal = Details.edge),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.trackersSummary,
                style = FIELD_LABEL,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Row(
                Modifier
                    .clickable(onClick = onAnnounce)
                    .semantics { contentDescription = "Re-announce" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Glyph(Icons.CAMPAIGN, size = COPY_GLYPH, tint = scheme.primary)
                Text("Re-announce", style = FIELD_LABEL, color = scheme.primary, maxLines = 1)
            }
        }
        Box(Modifier.fillMaxWidth().height(HAIRLINE).background(KachokPalette.rowHairline))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (state.trackers.isEmpty()) {
                Text(
                    "This torrent names no trackers.",
                    style = FIELD_LABEL,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Details.edge, vertical = 10.dp),
                )
            }
            state.trackers.forEach { tracker -> TrackerCard(tracker) }
            state.dht?.let { DhtCard(it) }
        }
    }
}

@Composable
private fun TrackerCard(tracker: TrackerRow) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Details.edge, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            tracker.url,
            style = MonoSmall,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tracker.status, style = MonoSmall, color = toneColor(tracker.tone), maxLines = 1)
            Text(tracker.detail, style = MonoSmall, color = scheme.onSurfaceVariant, maxLines = 1)
        }
        tracker.message?.let {
            Text(it, style = MonoSmall, color = KachokPalette.errorFigure, softWrap = true)
        }
        Box(Modifier.fillMaxWidth().height(HAIRLINE).background(KachokPalette.rowHairline))
    }
}

@Composable
private fun DhtCard(detail: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Details.edge, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("DHT (BEP 5)", style = MonoSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Text(detail, style = MonoSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

@Composable
private fun toneColor(tone: FieldTone) =
    when (tone) {
        FieldTone.Good -> MaterialTheme.colorScheme.primary
        FieldTone.Warning -> MaterialTheme.colorScheme.error
        FieldTone.Plain -> KachokPalette.onSurfaceMuted
    }

/**
 * A row per file, with the share of it that is verified.
 *
 * **The percentage is of the file, not of the pieces that touch it.** A 700-byte file inside a
 * 256 KiB piece is not complete because its neighbour's piece arrived, and counting whole pieces is
 * the implementation that says it is.
 *
 * The tick is drawn and does not respond; the summary line carries the design's badge to say so.
 */
@Composable
private fun ColumnScope.Files(state: DetailsState) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(PEER_HEAD).padding(horizontal = Details.edge),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            // No badge any more: the ticks in the add dialog are live, and the ones here are
            // indicators of what that dialog decided rather than controls waiting on anything.
            Text(state.filesSummary, style = FIELD_LABEL, color = scheme.onSurfaceVariant)
        }
        Box(Modifier.fillMaxWidth().height(HAIRLINE).background(KachokPalette.rowHairline))
        if (state.files.isEmpty()) {
            Text(
                "No files yet — the metainfo has not arrived.",
                style = FIELD_LABEL,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Details.edge, vertical = 10.dp),
            )
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            state.files.forEach { file -> FileLine(file) }
        }
    }
}

@Composable
private fun FileLine(file: FileRow) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(Details.rowHeight).padding(horizontal = Details.edge),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Glyph(
            if (file.wanted) Icons.CHECK_BOX else Icons.CHECK_BOX_OUTLINE_BLANK,
            size = FILE_TICK,
            tint = if (file.wanted) scheme.primary else scheme.onSurfaceVariant,
        )
        // `PathText` and not `TextOverflow.StartEllipsis`, which type-checks against Compose
        // Multiplatform 1.12 and truncates at the *end* anyway — checked twice against a golden
        // before `PathText` was written. Files in a torrent share a directory prefix, so the half
        // that tells them apart is the end.
        PathText(
            file.name,
            FIELD_LABEL,
            if (file.wanted) scheme.onSurface else scheme.onSurfaceVariant,
            Modifier.weight(1f),
        )
        Text(
            file.size,
            style = MonoSmall,
            color = KachokPalette.onSurfaceMuted,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(FILE_SIZE),
        )
        Text(
            file.progress,
            style = MonoSmall,
            color = if (file.wanted) KachokPalette.onSurfaceMuted else scheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(FILE_PERCENT),
        )
    }
}

/**
 * The design's four columns, sorted by rate.
 *
 * **The legend is drawn and not a tooltip.** `U`, `C` and `I` are one character each; a person who
 * has not seen them before has no way to guess, and the design puts the key at the foot of the tab
 * for exactly that reason.
 *
 * **An empty list says which of the two empties it is.** A torrent with no peers and a torrent
 * whose peers have not been sampled yet look identical, and the first is a thing to act on.
 */
@Composable
private fun ColumnScope.Peers(peers: List<PeerRow>) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(PEER_HEAD)
                .padding(horizontal = Details.edge),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ADDRESS", style = PEER_HEADING, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("CLIENT", style = PEER_HEADING, color = scheme.onSurfaceVariant, modifier = Modifier.width(CLIENT))
            Text("FLAG", style = PEER_HEADING, color = scheme.onSurfaceVariant, modifier = Modifier.width(FLAGS))
            Text(
                "KIB/S",
                style = PEER_HEADING,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(PEER_RATE),
            )
        }
        Box(Modifier.fillMaxWidth().height(HAIRLINE).background(KachokPalette.rowHairline))
        if (peers.isEmpty()) {
            Text(
                "No peers connected.",
                style = FIELD_LABEL,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Details.edge, vertical = 10.dp),
            )
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            peers.forEach { peer -> PeerLine(peer) }
        }
        Box(Modifier.fillMaxWidth().height(HAIRLINE).background(KachokPalette.rowHairline))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Details.edge, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Legend("U", "unchoked", scheme.primary)
            Legend("C", "choked", scheme.onSurfaceVariant)
            Legend("I", "interested", scheme.primary)
        }
    }
}

@Composable
private fun PeerLine(peer: PeerRow) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(Details.rowHeight).padding(horizontal = Details.edge),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            peer.address,
            style = MonoSmall,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            peer.client,
            style = FIELD_LABEL,
            color = KachokPalette.onSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(CLIENT),
        )
        Row(Modifier.width(FLAGS), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                if (peer.unchoked) "U" else "C",
                style = PEER_FLAG,
                color = if (peer.unchoked) scheme.primary else scheme.onSurfaceVariant,
            )
            Text(
                if (peer.interested) "I" else "·",
                style = PEER_FLAG,
                color = if (peer.interested) scheme.primary else scheme.onSurfaceVariant,
            )
        }
        Text(
            peer.rate,
            style = MonoSmall,
            // A rate of nothing is drawn as a figure that is not there rather than as a number,
            // which is the same rule the table's own zero columns follow.
            color = if (peer.rate == "0") scheme.onSurfaceVariant else KachokPalette.onSurfaceMuted,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(PEER_RATE),
        )
    }
}

@Composable
private fun Legend(
    flag: String,
    meaning: String,
    tint: Color,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(flag, style = PEER_FLAG, color = tint)
        Text(meaning, style = FIELD_LABEL, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Every label in the panel is Archivo at the panel's one size. */
private val FIELD_LABEL = ChromeText.copy(fontSize = 11.5.sp)

/** The design's column heads: the same 9.5 sp, letter-spaced capitals the table's header uses. */
private val PEER_HEADING = ChromeText.copy(fontSize = 9.5.sp, letterSpacing = 0.08.em)

private val PEER_FLAG = MonoSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)

private val PEER_HEAD = 24.dp

private val CLIENT = 100.dp

private val FLAGS = 30.dp

private val PEER_RATE = 42.dp

private val FILE_TICK = 13.sp

private val FILE_SIZE = 58.dp

private val FILE_PERCENT = 34.dp

private val HAIRLINE = 1.dp

private val INDICATOR = 2.dp

private val SECTION_TRACKING = 0.08.em

private val CARD_TRACKING = 0.06.em

private val BADGE_SIZE = 9.5.sp

private val CARD_TEXT = 11.sp

private val CARD_LINE = 16.5.sp

/**
 * It copies, and it says so.
 *
 * The glyph lights up in the accent for a second and a half. Something has to: the clipboard is not
 * on screen, so a press with no acknowledgement is indistinguishable from the dead button this
 * replaced — which is the whole complaint behind
 * [B-76](../../../../../../../../docs/backlog/B-76-the-last-dead-controls.md).
 *
 * A tick would read better and is not available. The icon font is subset by codepoint from a 15 MB
 * source that is deliberately not in the repository, so a new glyph means fetching it and running
 * `scripts/subset_icon_font.sh`; a colour change carries the same information out of the twenty-seven
 * glyphs already there.
 */
@Composable
private fun CopyButton(
    field: DetailsField,
    onCopy: (String) -> Unit,
) {
    var copied by remember(field.copyText) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(ACKNOWLEDGED)
            copied = false
        }
    }
    Glyph(
        Icons.CONTENT_COPY,
        size = COPY_GLYPH,
        tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .clickable {
                    onCopy(field.copyText)
                    copied = true
                }.semantics { contentDescription = "Copy ${field.label}" },
    )
}

private val ACKNOWLEDGED = 1500.milliseconds

private val COPY_GLYPH = 14.sp
