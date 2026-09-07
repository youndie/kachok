package io.github.youndie.kachok.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.youndie.viddik.annotations.ViddikScreenshot
import io.github.youndie.kachok.ui.theme.KachokTheme

/**
 * The seven states as the product draws them, one under another.
 *
 * Not a copy of the design's own states table — that table is documentation, with a prose column
 * explaining each row. What has to match is the *product* row, and the design draws those too:
 * `docs/design/screens/main-window.png` is the same seven states in the same nine columns, at the
 * same 859 px, so this sheet is comparable to it pixel for pixel and not merely in spirit.
 *
 * The numbers, the names and the order are the design's own, so a difference is a difference in
 * drawing rather than in data.
 */
@Composable
private fun Sheet() {
    KachokTheme {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        ) {
            designRows.forEach { model -> TorrentRow(model) }
        }
    }
}

// 859 is the design's list panel, measured between the window edge and the details divider; 203 is
// seven rows of 28 with the hairline each one draws under itself.
@ViddikScreenshot(name = "seven-states", group = "list", width = 859, height = 203)
@Composable
internal fun RowStatesSheet(): Unit = Sheet()
