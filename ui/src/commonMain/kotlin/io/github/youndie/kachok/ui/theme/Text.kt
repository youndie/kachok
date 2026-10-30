package io.github.youndie.kachok.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/**
 * Text, drawn by the current kit.
 *
 * The parameters are the ones the screens use, with Material's defaults: an unset [color] is the
 * kit's content colour, and [TextStyle.Default] is passed through to mean "the kit root's style" —
 * what Material's own `LocalTextStyle.current` default means. The defaults live here and not on [KachokKit], because a default on an
 * abstract composable is a default every kit would have to repeat.
 */
@Composable
internal fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = TextStyle.Default,
    textAlign: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    KachokTheme.kit.Text(text, modifier, color, style, textAlign, overflow, softWrap, maxLines, onTextLayout)
}

@Composable
internal fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = TextStyle.Default,
    textAlign: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    KachokTheme.kit.Text(text, modifier, color, style, textAlign, overflow, softWrap, maxLines, onTextLayout)
}

/** The colour text and glyphs take when nobody names one. */
internal val KachokTheme.contentColor: Color
    @Composable get() = kit.contentColor
