package ru.workinprogress.kachok.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.youndie.viddik.annotations.ViddikScreenshot
import ru.workinprogress.kachok.ui.details.DetailsPanel
import ru.workinprogress.kachok.ui.details.DetailsTab
import ru.workinprogress.kachok.ui.session.Rates
import ru.workinprogress.kachok.ui.session.detailsOf
import ru.workinprogress.kachok.ui.theme.KachokTheme

/**
 * The whole window, with the design's sixteen torrents and its details panel.
 *
 * 1200 x 731 is `docs/design/screens/main-window.png` below the title bar, which the operating
 * system draws and this does not: the reference is 1200 x 760 with a 29 px chrome on top of it.
 * Everything under that line is comparable pixel for pixel.
 */
@ViddikScreenshot(name = "window", group = "main", width = 1200, height = 731)
@Composable
internal fun MainWindowSheet() {
    KachokTheme {
        MainWindow(
            MainWindowState(
                torrents = designTorrents,
                status = designStatus,
                degradedSummary = DEGRADED_SUMMARY,
                degradedDetail = DEGRADED_DETAIL,
                details = designDetails(),
            ),
        )
    }
}

/**
 * The three tabs the engine cannot fill, side by side the way the design lays them out.
 *
 * The design draws all three full of rows — a mockup can — and this draws what the engine can
 * actually say about them, which is nothing yet and why. See the deviation recorded in
 * [B-49](../../../../../../../../docs/backlog/B-49-details-panel.md).
 */
@ViddikScreenshot(name = "planned-tabs", group = "details", width = 1023, height = 300)
@Composable
internal fun DetailsTabsSheet() {
    KachokTheme {
        Row(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            horizontalArrangement = Arrangement.Start,
        ) {
            listOf(DetailsTab.Files, DetailsTab.Peers, DetailsTab.Trackers).forEach { tab ->
                DetailsPanel(designDetails(tab))
            }
        }
    }
}

/**
 * The one row whose value is longer than its cell, at a length a real machine produces.
 *
 * `Save to` was drawn touching its own label and cut at the end — `Save to/private/tmp/claude-501/…`
 * — where every visible character is the same for every torrent on the machine. Neither fault shows
 * at `~/Downloads/iso`, which is why the window had to be run to find them.
 */
@ViddikScreenshot(name = "long-path", group = "details", width = 341, height = 250)
@Composable
internal fun DetailsLongPathSheet() {
    KachokTheme {
        DetailsPanel(
            detailsOf(
                state = designSession,
                rates = Rates(),
                pieceLength = 2L * 1024 * 1024,
                directory = "/private/tmp/claude-501/-Users-youndie-Documents-GitHub/scratchpad/live/downloads",
            ),
        )
    }
}
