package io.github.youndie.kachok.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.youndie.kachok.ui.icons.Glyph
import io.github.youndie.kachok.ui.icons.Icons
import io.github.youndie.kachok.ui.theme.KachokTheme
import io.github.youndie.kachok.ui.theme.MonoSmall
import io.github.youndie.kachok.ui.theme.Text

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
    val scheme = KachokTheme.colors
    Bar(
        height = Chrome.bannerHeight,
        background = KachokTheme.colors.degradedBanner,
        line = scheme.errorContainer,
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Glyph(Icons.ERROR, size = BANNER_GLYPH, tint = scheme.error)
        Text(summary, style = KachokTheme.type.action, color = scheme.error, maxLines = 1)
        Text(
            detail,
            style = MonoSmall,
            color = KachokTheme.colors.errorFigure,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // One weight, not a weight and a spacer: two of them share the leftover evenly and
            // the exception loses half the room it needs to be readable.
            modifier = Modifier.weight(1f),
        )
        KachokTheme.kit.BannerButton("Show it", onShow)
    }
}

private val BANNER_GLYPH = 17.sp
