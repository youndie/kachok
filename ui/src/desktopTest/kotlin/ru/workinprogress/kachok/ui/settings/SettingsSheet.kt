package ru.workinprogress.kachok.ui.settings

import androidx.compose.runtime.Composable
import io.github.youndie.viddik.annotations.ViddikScreenshot
import ru.workinprogress.kachok.ui.main.MainWindow
import ru.workinprogress.kachok.ui.main.MainWindowState
import ru.workinprogress.kachok.ui.main.designStatus
import ru.workinprogress.kachok.ui.session.Preferences
import ru.workinprogress.kachok.ui.session.settingsOf
import ru.workinprogress.kachok.ui.theme.KachokTheme

/**
 * The settings screen with the design's own state: everything default except a download limit.
 *
 * The point of the screen is the column of `default …` labels beside the fields, and the point of
 * this golden is that they are drawn — `SettingsFromTest` is what proves each one came out of
 * `SessionConfig` rather than out of a designer's memory.
 */
@ViddikScreenshot(name = "screen", group = "settings", width = 620, height = 640)
@Composable
internal fun SettingsSheet() {
    KachokTheme {
        SettingsScreen(
            settingsOf(
                Preferences(directory = "~/Downloads", downloadLimitKibPerSecond = 12_000),
            ),
        )
    }
}

/**
 * A window with nothing in it.
 *
 * The whole chrome is still there — a toolbar with somewhere to start and a status bar saying the
 * port is listening — because an empty client is idle rather than broken.
 */
@ViddikScreenshot(name = "empty", group = "main", width = 1200, height = 562)
@Composable
internal fun EmptyWindowSheet() {
    KachokTheme {
        MainWindow(
            MainWindowState(
                torrents = emptyList(),
                status =
                    ru.workinprogress.kachok.ui.main
                        .SessionStatus(
                            down = "0 KiB/s",
                            up = "0 KiB/s",
                            torrents = "idle",
                            dht = null,
                            port = designStatus.port,
                            heap = designStatus.heap,
                        ),
            ),
        )
    }
}
