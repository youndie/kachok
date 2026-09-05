package ru.workinprogress.kachok.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The pieces every bar of the window is built out of.
 *
 * The design's chrome has no elevation anywhere: a bar is a fixed height, a background, and a
 * one-pixel line on the side it meets the content. Making that a shape here is what keeps the
 * toolbar, the banner, the column header and the status bar consistent when four different items
 * build them.
 */
internal object Chrome {
    val toolbarHeight: Dp = 48.dp
    val bannerHeight: Dp = 32.dp
    val headerHeight: Dp = 28.dp
    val statusHeight: Dp = 24.dp

    /** A toolbar control, and the size the design draws every icon button at. */
    val controlHeight: Dp = 28.dp

    val edge: Dp = 10.dp
    val hairline: Dp = 1.dp

    /** The vertical rule that groups the toolbar's buttons. */
    val separatorHeight: Dp = 20.dp
}

/**
 * A horizontal bar with its hairline.
 *
 * [lineOnTop] rather than a boolean pair: a bar is above the content or below it, and every one in
 * this window draws its line on the side that faces the content.
 */
@Composable
internal fun Bar(
    height: Dp,
    background: Color,
    line: Color,
    modifier: Modifier = Modifier,
    lineOnTop: Boolean = false,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(0.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val rule =
        @Composable {
            Box(Modifier.fillMaxWidth().height(Chrome.hairline).background(line))
        }
    androidx.compose.foundation.layout.Column(modifier.fillMaxWidth().background(background)) {
        if (lineOnTop) rule()
        Row(
            Modifier
                .fillMaxWidth()
                .height(height)
                .padding(horizontal = Chrome.edge),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = horizontalArrangement,
            content = content,
        )
        if (!lineOnTop) rule()
    }
}

/** The 1 × 20 rule that separates one group of toolbar buttons from the next. */
@Composable
internal fun ToolbarSeparator() {
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .width(Chrome.hairline)
            .height(Chrome.separatorHeight)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** The empty space that pushes everything after it to the right edge. */
@Composable
internal fun RowScope.Spacer(): Unit = Box(Modifier.weight(1f).size(0.dp))
