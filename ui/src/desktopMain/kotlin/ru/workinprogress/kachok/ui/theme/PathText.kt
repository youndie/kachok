package ru.workinprogress.kachok.ui.theme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign

/**
 * A directory, cut from the front when it does not fit.
 *
 * **What identifies a directory is its last component.** `/private/tmp/claude-501/-Users-youndi…`
 * is what the details panel showed on a real machine: thirty-eight characters, every one of them
 * the same for every torrent on that disk, and the folder the file actually went into invisible.
 * The design solved it with `direction: rtl` on the one cell.
 *
 * **Not `TextOverflow.StartEllipsis`.** It compiles against Compose Multiplatform 1.12 and does
 * nothing — with or without `softWrap = false`, the paragraph still truncates at the end. Verified
 * twice against the golden `details_long-path.png` before this was written; a toolkit constant that
 * type-checks is not a behaviour.
 *
 * So the string is cut here, against a real measurement of the space it has. Exact rather than a
 * character count: a character count is only a width in a monospaced face, and two of the three
 * places this is used are not one.
 */
@Composable
internal fun PathText(
    path: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.End,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val available = constraints.maxWidth
        val shown = remember(path, available, style) { elide(path, style, available, measurer) }
        Text(shown, style = style, color = color, maxLines = 1, softWrap = false, textAlign = textAlign)
    }
}

/** The ellipsis this uses, which is one character and not three full stops. */
private const val ELLIPSIS = "…"

/**
 * The longest tail of [path] that fits in [maxWidth], with an ellipsis in front of it.
 *
 * A binary search over the number of characters kept, because measuring is the only way to know
 * and measuring every prefix of a long path is a lot of layout for one row.
 */
internal fun elide(
    path: String,
    style: TextStyle,
    maxWidth: Int,
    measurer: TextMeasurer,
): String {
    fun fits(text: String): Boolean = measurer.measure(text, style, softWrap = false).size.width <= maxWidth

    if (maxWidth <= 0 || fits(path)) return path
    var low = 0
    var high = path.length
    while (low < high) {
        val keep = (low + high + 1) / 2
        if (fits(ELLIPSIS + path.takeLast(keep))) low = keep else high = keep - 1
    }
    // Nothing fits at all — not even the ellipsis. Say so with the ellipsis rather than with
    // nothing, so the cell reads as truncated instead of as empty.
    return if (low == 0) ELLIPSIS else ELLIPSIS + path.takeLast(low)
}
