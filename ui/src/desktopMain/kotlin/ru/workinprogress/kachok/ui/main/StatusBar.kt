package ru.workinprogress.kachok.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.workinprogress.kachok.ui.icons.Glyph
import ru.workinprogress.kachok.ui.icons.Icons
import ru.workinprogress.kachok.ui.theme.KachokPalette
import ru.workinprogress.kachok.ui.theme.MonoSmall

/**
 * What the whole process is doing, in one 24 dp line.
 *
 * Everything here is a session total rather than a torrent's, and each item is a number a person
 * would otherwise have to open a terminal for: the two rates, what the list adds up to, whether
 * DHT is up, whether the port is actually listening, and how much of the heap is gone.
 *
 * The heap is the one that looks like developer trivia and is not: this client is built to run in
 * 128 MiB, and the number that says whether that is still true belongs where it can be read
 * without a profiler.
 */
internal class SessionStatus(
    val down: String,
    val up: String,
    val torrents: String,
    val dht: String?,
    val port: String,
    val heap: String,
)

@Composable
internal fun StatusBar(
    status: SessionStatus,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Bar(
        height = Chrome.statusHeight,
        background = scheme.surfaceVariant,
        line = scheme.outlineVariant,
        modifier = modifier,
        lineOnTop = true,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Rate(Icons.ARROW_DOWNWARD, scheme.primary, status.down)
        Rate(Icons.ARROW_UPWARD, KachokPalette.onSurfaceMuted, status.up)
        Dot()
        Figure(status.torrents)
        Spacer()
        // DHT off is dimmed rather than absent: "no nodes" and "not asked for" are different
        // answers to the same question, and a missing line answers neither.
        if (status.dht == null) {
            Figure("DHT off", scheme.onSurfaceVariant)
        } else {
            Figure(status.dht)
        }
        Dot()
        Figure(status.port)
        Dot()
        Figure(status.heap)
    }
}

@Composable
private fun RowScope.Rate(
    glyph: String,
    tint: Color,
    text: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Glyph(glyph, size = STATUS_GLYPH, tint = tint)
        Text(text, style = MonoSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

@Composable
private fun RowScope.Figure(
    text: String,
    color: Color = KachokPalette.onSurfaceMuted,
) {
    Text(text, style = MonoSmall, color = color, maxLines = 1)
}

/**
 * The separator between two groups of figures.
 *
 * Given a width, because the design's own is the one span in the whole document with no face on
 * it: it inherits the browser's default, which is not one of the three families this ships, and
 * an unconstrained mono dot is four pixels wider — enough to push everything after it out of line
 * with the reference by the third separator.
 */
@Composable
private fun RowScope.Dot(): Unit =
    Text(
        "·",
        style = MonoSmall,
        color = MaterialTheme.colorScheme.outline,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(DOT_WIDTH),
    )

private val DOT_WIDTH = 5.dp

private val STATUS_GLYPH = 14.sp
