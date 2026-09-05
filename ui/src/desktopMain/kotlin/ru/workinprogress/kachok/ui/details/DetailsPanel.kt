package ru.workinprogress.kachok.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
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
import kotlin.time.Duration.Companion.milliseconds

/** The four tabs, in the design's order. Three of them are waiting on the engine. */
internal enum class DetailsTab(
    val label: String,
    /** What the engine would have to grow before this tab has anything true to show. */
    val plannedBecause: String? = null,
) {
    Overview("Overview"),
    Files(
        "Files",
        "The engine opens a FileSet and never reports it: there is no per-file progress, and no " +
            "way to mark a file unwanted.",
    ),
    Peers(
        "Peers",
        "SessionState counts peers and does not name them: no address, no client string, no " +
            "per-peer rate.",
    ),
    Trackers(
        "Trackers",
        "There is one trackerError for the whole session, not a status for each tracker in the " +
            "announce list.",
    ),
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

internal class DetailsState(
    val name: String,
    val state: TorrentState,
    val stateLabel: String,
    val summary: String,
    val sections: List<DetailsSection>,
    val complaints: List<Complaint>,
    val sessionError: String?,
    val tab: DetailsTab = DetailsTab.Overview,
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
) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.width(Details.width + HAIRLINE)) {
        Box(Modifier.width(HAIRLINE).fillMaxSize().background(scheme.outlineVariant))
        Column(Modifier.fillMaxSize().background(KachokPalette.panel)) {
            Header(state)
            Tabs(state.tab, onTab)
            when (state.tab) {
                DetailsTab.Overview -> Overview(state, onCopy)
                else -> Planned(state.tab)
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
 * A tab whose list the engine cannot fill yet.
 *
 * It says which engine change it is waiting for, in the engine's own vocabulary, rather than
 * showing an empty table that looks like a torrent with no files. The design draws these three
 * full of rows; a mockup can, and a client that did would be inventing data.
 */
@Composable
private fun ColumnScope.Planned(tab: DetailsTab) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = Details.edge, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                tab.label,
                style = ChromeText.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
            PlannedBadge()
        }
        Text(
            tab.plannedBecause.orEmpty(),
            style = FIELD_LABEL,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Every label in the panel is Archivo at the panel's one size. */
private val FIELD_LABEL = ChromeText.copy(fontSize = 11.5.sp)

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
