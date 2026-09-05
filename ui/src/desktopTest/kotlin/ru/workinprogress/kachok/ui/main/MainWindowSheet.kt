package ru.workinprogress.kachok.ui.main

import androidx.compose.runtime.Composable
import io.github.youndie.viddik.annotations.ViddikScreenshot
import ru.workinprogress.kachok.ui.theme.KachokTheme

/**
 * The whole shell, with the design's sixteen torrents in it.
 *
 * **The details panel is closed here, and that is the deliberate difference from
 * `docs/design/screens/main-window.png`.** The reference draws it open, and drawing it is
 * [B-49](../../../../../../../../docs/backlog/B-49-details-panel.md); a placeholder in its place
 * would put something in a golden that the product does not have. Everything full-width — the
 * toolbar, the banner, the status bar — is directly comparable to the reference; the list is
 * 1200 px instead of 859, which is what `minmax(0, 1fr)` does to the name column when the panel is
 * not there, and every other column keeps its width.
 */
@ViddikScreenshot(name = "shell", group = "main", width = 1200, height = 731)
@Composable
internal fun MainWindowSheet() {
    KachokTheme {
        MainWindow(
            MainWindowState(
                torrents = designTorrents,
                status = designStatus,
                degradedSummary = DEGRADED_SUMMARY,
                degradedDetail = DEGRADED_DETAIL,
            ),
        )
    }
}
