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
import io.github.youndie.oldge.theme.OldgeSkin
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
 * The settings screen in the oldge kit, with the *Interface* row on it set to oldge-ui (B-160) and
 * the *Colours* row under it offering the skins, Toxic ringed (B-162).
 *
 * The section is the screen's last, below the fold at this height, so the sheet puts it first: the
 * golden is of the switches (`OldgeSwitch`), the segmented choice, the skins and the fields in this
 * kit's colours, and a picture of the rows that chose them is the one worth having.
 */
@ViddikScreenshot(name = "screen-oldge", group = "settings", width = 744, height = 912)
@Composable
internal fun SettingsOldgeSheet() {
    OldgeSettings(OldgeSkin.Toxic)
}

/**
 * And with Crystal chosen (B-162): the light skin, its swatch ringed in the skin's own `ink`, the
 * footnote's ground mixed from its `surface` and `ground` where Toxic keeps its literal.
 */
@ViddikScreenshot(name = "screen-crystal", group = "settings", width = 744, height = 912)
@Composable
internal fun SettingsCrystalSheet() {
    OldgeSettings(OldgeSkin.Crystal)
}

@Composable
private fun OldgeSettings(skin: OldgeSkin) {
    val preferences =
        Preferences(
            directory = "~/Downloads",
            downloadLimitKibPerSecond = 12_000,
            interfaceKit = InterfaceKit.Oldge,
            oldgeSkin = skin,
        )
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
 * The settings screen in the smallest window there is, with a default directory of 120 characters
 * (B-179).
 *
 * A home directory is the one length on this screen nobody chose, and the live run that found the
 * defect had one under `/private/tmp`: *Save to* was drawn «Sa / ve / to» beside a default that took
 * the whole row. Here the label is on one line and the default ends in the directory's own name.
 */
@ViddikScreenshot(name = "screen-long-default", group = "settings", width = 720, height = 504)
@Composable
internal fun SettingsLongDefaultSheet() {
    KachokTheme { SettingsScreen(withLongDefault(settingsOf(Preferences(directory = LONG_DEFAULT)))) }
}

/** And in the oldge kit, whose folder field and *Browse…* are its own widths (B-178). */
@ViddikScreenshot(name = "screen-long-default-oldge", group = "settings", width = 720, height = 504)
@Composable
internal fun SettingsLongDefaultOldgeSheet() {
    KachokTheme(kit = OldgeKit.Default) {
        SettingsScreen(withLongDefault(settingsOf(Preferences(directory = LONG_DEFAULT))))
    }
}

/** A default directory of exactly 120 characters, its last component one that appears nowhere else. */
internal const val LONG_DEFAULT: String =
    "/home/a-person-with-a-long-name/.local/share/containers/storage/volumes/kachok-home-1/_data/users/" +
        "someone-else/kachok-in"

/**
 * [state] with *Save to*'s default — and its value, as on a fresh install — set to [LONG_DEFAULT].
 *
 * The state is rebuilt rather than `settingsOf` taking the default as an argument: the default is
 * the machine's home directory, and the screen is what is under test, not where the default came
 * from.
 */
internal fun withLongDefault(state: SettingsState): SettingsState =
    SettingsState(
        sections =
            state.sections.map { section ->
                SettingsSection(
                    section.title,
                    section.settings.map {
                        if (it.key != SettingKey.SaveTo) {
                            it
                        } else {
                            Setting(
                                key = it.key,
                                label = it.label,
                                default = LONG_DEFAULT,
                                value = LONG_DEFAULT,
                                folder = true,
                            )
                        }
                    },
                )
            },
        footnote = state.footnote,
        footnotePlanned = state.footnotePlanned,
    )

/**
 * A window with nothing in it.
 *
 * The whole chrome is still there — a toolbar with somewhere to start and a status bar saying the
 * port is listening — because an empty client is idle rather than broken.
 */
@ViddikScreenshot(name = "empty", group = "main", width = 1440, height = 674)
@Composable
internal fun EmptyWindowSheet() {
    KachokTheme { EmptyWindow() }
}

/** The same window in the oldge kit (B-178): *Add a torrent* is the library's primary button. */
@ViddikScreenshot(name = "empty-oldge", group = "main", width = 1440, height = 674)
@Composable
internal fun EmptyWindowOldgeSheet() {
    KachokTheme(kit = OldgeKit.Default) { EmptyWindow() }
}

@Composable
private fun EmptyWindow() {
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
