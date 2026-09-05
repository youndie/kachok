package ru.workinprogress.kachok.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.workinprogress.kachok.ui.icons.Glyph
import ru.workinprogress.kachok.ui.icons.Icons
import ru.workinprogress.kachok.ui.theme.ChromeButton
import ru.workinprogress.kachok.ui.theme.ChromeText
import ru.workinprogress.kachok.ui.theme.JetBrainsMono
import ru.workinprogress.kachok.ui.theme.KachokPalette
import ru.workinprogress.kachok.ui.theme.MonoSmall

/**
 * Nothing downloading, which is what a new install looks like.
 *
 * **It says what to do rather than that there is nothing.** Three ways in, all of them named: drop
 * a file, paste a link, or press the button — and the two keystrokes underneath, because a person
 * who has done this once should not have to reach for the button again.
 *
 * The column header and the details panel are gone with the list. An empty table with nine column
 * heads over it is a table that looks broken; this looks like a place to start.
 */
@Composable
internal fun EmptyState(
    modifier: Modifier = Modifier,
    onAdd: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxSize().background(scheme.surface),
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(RING)
                .border(HAIRLINE, scheme.outlineVariant, RoundedCornerShape(RING / 2)),
            contentAlignment = Alignment.Center,
        ) {
            Glyph(Icons.ARROW_DOWNWARD, size = RING_GLYPH, tint = scheme.primary)
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                "Nothing downloading",
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = TITLE),
                color = scheme.onSurface,
            )
            Text(
                invitation(),
                style = ChromeText.copy(fontSize = 12.5.sp, lineHeight = 20.sp),
                color = KachokPalette.onSurfaceMuted,
                textAlign = TextAlign.Center,
            )
        }
        Row(
            Modifier
                .height(BUTTON)
                .background(scheme.primary, RoundedCornerShape(4.dp))
                .clickable(onClick = onAdd)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Glyph(Icons.ADD, size = BUTTON_GLYPH, tint = scheme.onPrimary)
            Text(
                "Add a torrent",
                style = ChromeButton.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                color = scheme.onPrimary,
            )
        }
        Text("⌘O  file    ⌘V  magnet", style = MonoSmall.copy(fontSize = 11.sp), color = scheme.onSurfaceVariant)
    }
}

/** `.torrent` is a file extension and is set as one, which is the design's own distinction. */
@Composable
private fun invitation(): AnnotatedString =
    buildAnnotatedString {
        append("Drop a ")
        withStyle(SpanStyle(fontFamily = JetBrainsMono, fontSize = 12.sp)) { append(".torrent") }
        append(" anywhere in this window, or paste a magnet link.")
    }

private val RING = 64.dp

private val RING_GLYPH = 28.sp

private val TITLE = 25.sp

private val BUTTON = 34.dp

private val BUTTON_GLYPH = 18.sp

private val HAIRLINE = 1.dp
