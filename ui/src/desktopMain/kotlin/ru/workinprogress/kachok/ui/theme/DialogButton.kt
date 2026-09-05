package ru.workinprogress.kachok.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The button both dialogs end with.
 *
 * Shared rather than copied. Two dialogs whose buttons are two private functions are two dialogs
 * that drift, and the drift is invisible until they are on screen together — which they never are.
 *
 * [destructive] is a third appearance and not a colour argument: what makes *Remove* different from
 * *Add* is that it cannot be undone, and a caller that had to remember to pass the error colour is
 * a caller that will one day forget.
 */
@Composable
internal fun RowScope.DialogButton(
    label: String,
    primary: Boolean,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val ground =
        when {
            !primary -> Color.Transparent
            !enabled -> scheme.surfaceContainerHighest
            destructive -> scheme.error
            else -> scheme.primary
        }
    Box(
        Modifier
            .height(BUTTON_HEIGHT)
            .background(ground, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = if (primary) 18.dp else 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = if (primary) ChromeButton.copy(fontWeight = FontWeight.SemiBold) else ChromeButton,
            color =
                when {
                    !enabled -> scheme.onSurfaceVariant
                    destructive -> scheme.onError
                    primary -> scheme.onPrimary
                    else -> KachokPalette.onSurfaceMuted
                },
            maxLines = 1,
        )
    }
}

/**
 * 32 dp, which is what the add dialog drew before this function was shared.
 *
 * Written down because moving the button here silently made it 30 and shifted every row in
 * `add_dialog.png` by a pixel — a regression the golden caught only because the golden was
 * re-recorded and read, not because anything failed.
 */
private val BUTTON_HEIGHT = 32.dp
