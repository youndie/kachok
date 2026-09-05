package ru.workinprogress.kachok.ui.remove

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.workinprogress.kachok.ui.icons.Glyph
import ru.workinprogress.kachok.ui.icons.Icons
import ru.workinprogress.kachok.ui.theme.ChromeText
import ru.workinprogress.kachok.ui.theme.DialogButton
import ru.workinprogress.kachok.ui.theme.KachokPalette
import ru.workinprogress.kachok.ui.theme.MonoSmall
import ru.workinprogress.kachok.ui.theme.RowName

/**
 * What the dialog is asking about.
 *
 * [where] and [howMuch] are here because the question is unanswerable without them: *delete the
 * data* means nothing until a person can see which directory and how much of it there is.
 */
internal class RemoveState(
    val name: String,
    /** Where the files are, shown so the checkbox names something real. */
    val where: String,
    /** `96.0 MiB on disk`, or what is known of it. */
    val howMuch: String,
    val deleteData: Boolean = false,
) {
    fun withData(delete: Boolean): RemoveState = RemoveState(name, where, howMuch, delete)
}

/**
 * The dialog the design's ellipsis promises, and does not draw.
 *
 * **Two buttons, not three.** *Remove* and *Remove and delete* as separate buttons would put the
 * destructive one a mis-click away from the safe one; a checkbox that is off until somebody turns
 * it on makes deleting a decision rather than an aim.
 *
 * **The primary button changes colour and wording with the checkbox.** A button that says *Remove*
 * in the accent and deletes a torrent's data is the shape of every accidental deletion; when the
 * box is ticked it says so and turns red.
 */
@Composable
internal fun RemoveTorrentDialog(
    state: RemoveState,
    modifier: Modifier = Modifier,
    onCancel: () -> Unit = {},
    onToggleData: (Boolean) -> Unit = {},
    onRemove: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .width(DIALOG_WIDTH)
            .background(scheme.surfaceContainerHigh, RoundedCornerShape(6.dp))
            .border(HAIRLINE, DIALOG_BORDER, RoundedCornerShape(6.dp)),
    ) {
        Text(
            "Remove torrent",
            style = MaterialTheme.typography.headlineSmall,
            color = scheme.onSurface,
            modifier = Modifier.padding(start = EDGE, end = EDGE, top = 18.dp, bottom = 14.dp),
        )
        Column(
            Modifier
                .padding(horizontal = EDGE)
                .fillMaxWidth()
                .background(KachokPalette.neutralCard, RoundedCornerShape(4.dp))
                .border(HAIRLINE, scheme.outline, RoundedCornerShape(4.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(state.name, style = RowName, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state.howMuch, style = MonoSmall, color = KachokPalette.onSurfaceMuted, maxLines = 1)
        }
        Row(
            Modifier
                .padding(start = EDGE, end = EDGE, top = 16.dp)
                .clickable { onToggleData(!state.deleteData) }
                .semantics { contentDescription = DELETE_DATA },
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Glyph(
                if (state.deleteData) Icons.CHECK_BOX else Icons.CHECK_BOX_OUTLINE_BLANK,
                size = CONTROL_GLYPH,
                tint = if (state.deleteData) scheme.error else scheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    DELETE_DATA,
                    style = CHOICE,
                    color = if (state.deleteData) scheme.error else scheme.onSurface,
                )
                Text(
                    state.where,
                    style = MonoSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(EDGE),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.deleteData) {
                Text(
                    "This cannot be undone.",
                    style = NOTE,
                    color = scheme.error,
                    modifier = Modifier.weight(1f),
                )
            }
            DialogButton("Cancel", primary = false, onClick = onCancel)
            DialogButton(
                if (state.deleteData) "Remove and delete" else "Remove",
                primary = true,
                destructive = state.deleteData,
                onClick = onRemove,
            )
        }
    }
}

/** The label is the handle: the checkbox has no text of its own for a test to reach. */
internal const val DELETE_DATA: String = "Delete the downloaded data"

private val DIALOG_WIDTH = 420.dp

private val DIALOG_BORDER = Color(0xFF303836)

private val EDGE = 20.dp

private val HAIRLINE = 1.dp

private val CONTROL_GLYPH = 18.sp

private val CHOICE = ChromeText.copy(fontSize = 12.5.sp)

private val NOTE = ChromeText.copy(fontSize = 10.5.sp)
