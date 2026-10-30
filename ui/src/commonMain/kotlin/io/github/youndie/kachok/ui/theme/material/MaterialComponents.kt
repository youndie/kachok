package io.github.youndie.kachok.ui.theme.material

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.youndie.kachok.ui.theme.ChromeButton
import io.github.youndie.kachok.ui.theme.KachokTheme
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
            }.clickable(enabled = enabled, onClick = onToggle)
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
