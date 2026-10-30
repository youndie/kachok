package io.github.youndie.kachok.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import io.github.youndie.appframe.TitleBar
import io.github.youndie.kachok.ui.KACHOK_TITLE_BAR
import io.github.youndie.kachok.ui.details.DetailsPanel
import io.github.youndie.kachok.ui.details.DetailsTab
import io.github.youndie.kachok.ui.session.Rates
import io.github.youndie.kachok.ui.session.detailsOf
import io.github.youndie.kachok.ui.theme.KachokTheme
import io.github.youndie.kachok.ui.theme.material.MaterialColours
import io.github.youndie.kachok.ui.theme.material.MaterialKit
import io.github.youndie.kachok.ui.theme.material.SchemeStyle
import io.github.youndie.kachok.ui.theme.oldge.OldgeKit
import io.github.youndie.viddik.annotations.ViddikScreenshot

/**
 * The whole window, title bar included, at the size the design draws it.
 *
 * The bar is AppFrame's `TitleBar` — the same one `main()` puts above the same content — so the
 * golden covers all 1200 x 760 of `docs/design/screens/main-window.png` rather than the 731
 * underneath it. `TitleBarStyle.MacOs` because the reference is a macOS window; the host's own
 * style is what the application uses.
 */
@ViddikScreenshot(name = "window", group = "main", width = 1440, height = 912)
@Composable
internal fun MainWindowSheet() {
    KachokTheme {
        Column(Modifier.fillMaxSize().background(KachokTheme.colors.surface)) {
            TitleBar(
                state = rememberWindowState(size = DpSize(1200.dp, 760.dp)),
                onCloseRequest = {},
                title = "kachok",
                // The application's own style, so the golden cannot drift from the window's
                // geometry; only the radius differs, because a golden has no corners to round.
                style = KACHOK_TITLE_BAR.copy(cornerRadius = 0.dp),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(KachokTheme.colors.raisedHigh),
            )
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
}

/**
 * The same window in the oldge kit (B-160), its title bar included — `AppFrame` draws it from the
 * Material scheme the kit's root builds out of the skin's bezel.
 *
 * **One torrent has a Russian name**, which is what the kit's choice of faces is for: the design
 * system's lcd and pixel faces have no Cyrillic, and torrent names here are often Russian. The row
 * is the third, so it sits between two Latin names in the same face and a fallback font would show.
 */
@ViddikScreenshot(name = "window-oldge", group = "main", width = 1440, height = 912)
@Composable
internal fun MainWindowOldgeSheet() {
    KachokTheme(kit = OldgeKit) {
        Column(Modifier.fillMaxSize().background(KachokTheme.colors.surface)) {
            TitleBar(
                state = rememberWindowState(size = DpSize(1200.dp, 760.dp)),
                onCloseRequest = {},
                title = "kachok",
                style = KACHOK_TITLE_BAR.copy(cornerRadius = 0.dp),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(KachokTheme.colors.raisedHigh),
            )
            MainWindow(
                MainWindowState(
                    torrents =
                        designTorrents.mapIndexed { at, row ->
                            if (at ==
                                2
                            ) {
                                row.copy(name = CYRILLIC_NAME)
                            } else {
                                row
                            }
                        },
                    status = designStatus,
                    degradedSummary = DEGRADED_SUMMARY,
                    degradedDetail = DEGRADED_DETAIL,
                    details = designDetails(),
                ),
            )
        }
    }
}

/**
 * The whole window in a seed the person picked (B-161): violet, under the default style.
 *
 * The design's own rows — downloading, seeding, checking, stopping, an error, the selected one —
 * so the picture answers what a seed must not change: three text levels apart, the error row red,
 * checking and stopping amber, the selected row readable in the new accent.
 */
@ViddikScreenshot(name = "window-violet", group = "main", width = 1440, height = 912)
@Composable
internal fun MainWindowVioletSheet() {
    SeededWindow(MaterialColours(Color(0xFFA78BFA), SchemeStyle.TonalSpot))
}

/** And orange under *Vibrant*: the seed nearest the warning amber the swatches offer, at its loudest. */
@ViddikScreenshot(name = "window-orange", group = "main", width = 1440, height = 912)
@Composable
internal fun MainWindowOrangeSheet() {
    SeededWindow(MaterialColours(Color(0xFFF39A5B), SchemeStyle.Vibrant))
}

@Composable
private fun SeededWindow(colours: MaterialColours) {
    KachokTheme(kit = MaterialKit.of(colours)) {
        Column(Modifier.fillMaxSize().background(KachokTheme.colors.surface)) {
            TitleBar(
                state = rememberWindowState(size = DpSize(1200.dp, 760.dp)),
                onCloseRequest = {},
                title = "kachok",
                style = KACHOK_TITLE_BAR.copy(cornerRadius = 0.dp),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(KachokTheme.colors.raisedHigh),
            )
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
}

/** A Russian torrent name, as they come: a title, a year, an extension. */
internal const val CYRILLIC_NAME: String = "Мастер и Маргарита (1994).mkv"

/**
 * The three tabs the engine cannot fill, side by side the way the design lays them out.
 *
 * The design draws all three full of rows — a mockup can — and this draws what the engine can
 * actually say about them, which is nothing yet and why. See the deviation recorded in
 * [B-49](../../../../../../../../docs/backlog/B-49-details-panel.md).
 */
@ViddikScreenshot(name = "planned-tabs", group = "details", width = 1228, height = 528)
@Composable
internal fun DetailsTabsSheet() {
    KachokTheme {
        Row(
            Modifier.fillMaxSize().background(KachokTheme.colors.surface),
            horizontalArrangement = Arrangement.Start,
        ) {
            listOf(DetailsTab.Files, DetailsTab.Peers, DetailsTab.Trackers).forEach { tab ->
                DetailsPanel(designDetails(tab))
            }
        }
    }
}

/**
 * The same window at 600 dp, which is what the design's own note asks for and never draws.
 *
 * Two things happen: the table gives up its lesser columns — ETA and RATIO first, being arithmetic
 * on the others — and the details panel stops being a column beside the list and becomes an overlay
 * over it. The alternative was a horizontal scrollbar, which keeps every column and makes the
 * window useless at exactly the width where it appears.
 */
@ViddikScreenshot(name = "narrow", group = "main", width = 720, height = 504)
@Composable
internal fun NarrowWindowSheet() {
    KachokTheme {
        MainWindow(
            MainWindowState(
                torrents = designTorrents,
                status = designStatus,
                details = designDetails(),
            ),
            Modifier.fillMaxSize(),
        )
    }
}

/**
 * The one row whose value is longer than its cell, at a length a real machine produces.
 *
 * `Save to` was drawn touching its own label and cut at the end — `Save to/private/tmp/claude-501/…`
 * — where every visible character is the same for every torrent on the machine. Neither fault shows
 * at `~/Downloads/iso`, which is why the window had to be run to find them.
 */
@ViddikScreenshot(name = "long-path", group = "details", width = 409, height = 300)
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
