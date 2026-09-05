package ru.workinprogress.kachok.ui.main

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.workinprogress.kachok.ui.icons.Glyph
import ru.workinprogress.kachok.ui.icons.Icons
import ru.workinprogress.kachok.ui.theme.KachokPalette
import ru.workinprogress.kachok.ui.theme.MonoSmall
import ru.workinprogress.kachok.ui.theme.RowStateLabel

/**
 * A session is degraded, and somebody has to look.
 *
 * **Docked and permanent, not a `Snackbar`.** A snackbar is the shape for "this happened"; a
 * degraded session is a condition that lasts until a person acts on it, and one that vanishes on a
 * three-second timer is a diagnosis nobody read. It stays until the session is restarted or the
 * torrent is removed.
 *
 * **The exception is verbatim, in mono, on the banner itself.** The engine's rule — that "no peers,
 * no reason" is a state nobody can act on — is inherited here: the class name and the message are
 * what a person searches for, and hiding them behind a hover or a dialog costs the one click that
 * decides whether anybody ever sees them. It is ellipsized rather than wrapped because the banner
 * is one line high; *Show it* opens the whole of it in the details panel.
 */
@Composable
internal fun DegradedBanner(
    summary: String,
    detail: String,
    modifier: Modifier = Modifier,
    onShow: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Bar(
        height = Chrome.bannerHeight,
        background = KachokPalette.degradedBanner,
        line = scheme.errorContainer,
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Glyph(Icons.ERROR, size = BANNER_GLYPH, tint = scheme.error)
        Text(summary, style = MaterialTheme.typography.labelLarge, color = scheme.error, maxLines = 1)
        Text(
            detail,
            style = MonoSmall,
            color = KachokPalette.errorFigure,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer()
        Row(
            Modifier
                .height(BUTTON_HEIGHT)
                .border(Chrome.hairline, scheme.errorContainer, RoundedCornerShape(4.dp))
                .clickable(onClick = onShow)
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Show it", style = RowStateLabel, color = scheme.error, maxLines = 1)
        }
    }
}

private val BANNER_GLYPH = 17.sp

/** The design's `height: 22px` plus the border CSS measures outside it. */
private val BUTTON_HEIGHT = 24.dp
