package io.github.youndie.kachok.ui.remove

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.youndie.kachok.ui.icons.Glyph
import io.github.youndie.kachok.ui.icons.Icons
import io.github.youndie.kachok.ui.theme.ChromeText
import io.github.youndie.kachok.ui.theme.DialogButton
import io.github.youndie.kachok.ui.theme.KachokTheme
import io.github.youndie.kachok.ui.theme.MonoSmall
import io.github.youndie.kachok.ui.theme.RowName
import io.github.youndie.kachok.ui.theme.Text

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
    val scheme = KachokTheme.colors
    // The frame is the kit's since B-178, and so is the body's margin from it.
    KachokTheme.kit.DialogFrame("Remove torrent", DIALOG_WIDTH, modifier) { edge ->
        Column(
            Modifier
                .padding(horizontal = edge)
                .fillMaxWidth()
                .background(KachokTheme.colors.neutralCard, RoundedCornerShape(4.dp))
                .border(HAIRLINE, scheme.outline, RoundedCornerShape(4.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(state.name, style = RowName, color = scheme.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state.howMuch, style = MonoSmall, color = KachokTheme.colors.textMuted, maxLines = 1)
        }
        Row(
            Modifier
                .padding(start = edge, end = edge, top = 16.dp)
                // A checkbox with its label, and the platform is told so (B-180).
                .toggleable(value = state.deleteData, role = Role.Checkbox, onValueChange = onToggleData)
                .semantics { contentDescription = DELETE_DATA },
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Glyph(
                if (state.deleteData) Icons.CHECK_BOX else Icons.CHECK_BOX_OUTLINE_BLANK,
                size = CONTROL_GLYPH,
                tint = if (state.deleteData) scheme.error else scheme.textFaint,
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    DELETE_DATA,
                    style = CHOICE,
                    color = if (state.deleteData) scheme.error else scheme.text,
                )
                Text(
                    state.where,
                    style = MonoSmall,
                    color = scheme.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = edge, end = edge, top = ACTIONS_TOP, bottom = edge),
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

/** Above the dialog's buttons: the design's 20, whatever the kit's side margin is. */
private val ACTIONS_TOP = 20.dp

private val HAIRLINE = 1.dp

private val CONTROL_GLYPH = 18.sp

private val CHOICE = ChromeText.copy(fontSize = 12.5.sp)

private val NOTE = ChromeText.copy(fontSize = 10.5.sp)
