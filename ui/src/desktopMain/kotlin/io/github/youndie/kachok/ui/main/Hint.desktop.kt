package io.github.youndie.kachok.ui.main

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.youndie.kachok.ui.theme.ChromeText

/**
 * `TooltipArea`, drawn in the inverse surface so it reads against the bar it covers, and placed
 * below the pointer rather than on it: a tooltip under the cursor hides the glyph it is naming.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal actual fun Hint(
    text: String,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    TooltipArea(
        tooltip = {
            Surface(shape = RoundedCornerShape(4.dp), color = scheme.inverseSurface, shadowElevation = 4.dp) {
                Text(
                    text,
                    style = ChromeText,
                    color = scheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        },
        delayMillis = HINT_DELAY_MILLIS,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 18.dp)),
        content = content,
    )
}

/** Long enough that sweeping the pointer across the bar does not flash six of them. */
internal const val HINT_DELAY_MILLIS = 500
