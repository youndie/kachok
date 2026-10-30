package io.github.youndie.kachok.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.youndie.kachok.ui.main.MainWindow
import io.github.youndie.kachok.ui.main.MainWindowState
import io.github.youndie.kachok.ui.main.designStatus
import io.github.youndie.kachok.ui.session.Preferences
import io.github.youndie.kachok.ui.session.settingsOf
import io.github.youndie.kachok.ui.theme.InterfaceKit
import io.github.youndie.kachok.ui.theme.KachokTheme
import io.github.youndie.kachok.ui.theme.material.MaterialColours
import io.github.youndie.kachok.ui.theme.material.SchemeStyle
import io.github.youndie.kachok.ui.theme.oldge.OldgeKit
import io.github.youndie.viddik.annotations.ViddikScreenshot

/**
 * The settings screen with the design's own state: everything default except a download limit.
 *
 * The point of the screen is the column of `default …` labels beside the fields, and the point of
 * this golden is that they are drawn — `SettingsFromTest` is what proves each one came out of
 * `SessionConfig` rather than out of a designer's memory.
 *
 * **760 tall rather than the design's 640.** A fifth section arrived with
 * [B-83](../../../../../../../../docs/backlog/B-83-autostart-and-its-setting.md), and at 640 the
 * picture stopped one section short — a golden that cannot see the last row is a golden that cannot
 * notice it changing.
 */
@ViddikScreenshot(name = "screen", group = "settings", width = 744, height = 912)
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
 * The settings screen in the oldge kit, with the *Interface* row on it set to oldge-ui (B-160).
 *
 * The section is the screen's last, below the fold at this height, so the sheet puts it first: the
 * golden is of the switches (`OldgeSwitch`), the segmented choice and the fields in this kit's
 * colours, and a picture of the row that chose them is the one worth having.
 */
@ViddikScreenshot(name = "screen-oldge", group = "settings", width = 744, height = 912)
@Composable
internal fun SettingsOldgeSheet() {
    val state =
        settingsOf(
            Preferences(
                directory = "~/Downloads",
                downloadLimitKibPerSecond = 12_000,
                interfaceKit = InterfaceKit.Oldge,
            ),
        )
    KachokTheme(kit = OldgeKit) {
        SettingsScreen(
            SettingsState(
                sections = state.sections.takeLast(1) + state.sections.dropLast(1),
                footnote = state.footnote,
                footnotePlanned = state.footnotePlanned,
            ),
        )
    }
}

/**
 * The settings screen in a violet seed, the *Colours* row chosen on it (B-161).
 *
 * The *Interface* section first for the reason the oldge sheet puts it there: the row that chose the
 * colours is the one worth a picture — the ringed swatch, the chosen style — and below the fold it
 * would not be in this one.
 */
@ViddikScreenshot(name = "screen-violet", group = "settings", width = 744, height = 912)
@Composable
internal fun SettingsVioletSheet() {
    SeededSettings(MaterialColours(Color(0xFFA78BFA), SchemeStyle.TonalSpot))
}

/** And in orange under the *Vibrant* style: the other end of the wheel, and the louder style. */
@ViddikScreenshot(name = "screen-orange", group = "settings", width = 744, height = 912)
@Composable
internal fun SettingsOrangeSheet() {
    SeededSettings(MaterialColours(Color(0xFFF39A5B), SchemeStyle.Vibrant))
}

@Composable
private fun SeededSettings(colours: MaterialColours) {
    val preferences =
        Preferences(directory = "~/Downloads", downloadLimitKibPerSecond = 12_000, materialColours = colours)
    val state = settingsOf(preferences)
    KachokTheme(kit = preferences.kit) {
        SettingsScreen(
            SettingsState(
                sections = state.sections.takeLast(1) + state.sections.dropLast(1),
                footnote = state.footnote,
                footnotePlanned = state.footnotePlanned,
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
@ViddikScreenshot(name = "empty", group = "main", width = 1440, height = 674)
@Composable
internal fun EmptyWindowSheet() {
    KachokTheme {
        MainWindow(
            MainWindowState(
                torrents = emptyList(),
                status =
                    io.github.youndie.kachok.ui.main
                        .SessionStatus(
                            down = "0 B/s",
                            up = "0 B/s",
                            torrents = "idle",
                            dht = null,
                            port = designStatus.port,
                            heap = designStatus.heap,
                        ),
            ),
        )
    }
}
