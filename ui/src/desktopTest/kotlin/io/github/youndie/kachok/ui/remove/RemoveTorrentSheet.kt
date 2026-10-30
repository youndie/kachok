package io.github.youndie.kachok.ui.remove

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.youndie.kachok.ui.theme.KachokTheme
import io.github.youndie.kachok.ui.theme.oldge.OldgeKit
import io.github.youndie.viddik.annotations.ViddikScreenshot

/**
 * Both answers to the checkbox, one above the other.
 *
 * The difference between them is the whole point of the dialog, and it is a difference in wording
 * and colour — exactly the kind a golden catches and a unit test describes badly.
 */
@ViddikScreenshot(name = "dialog", group = "remove", width = 576, height = 672)
@Composable
internal fun RemoveTorrentSheet() {
    val state =
        RemoveState(name = "Sintel-2010-1080p-DCP.tar", where = "~/Downloads", howMuch = "2.89 GiB on disk")
    KachokTheme {
        Box(
            Modifier.fillMaxSize().background(KachokTheme.colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                RemoveTorrentDialog(state)
                RemoveTorrentDialog(state.withData(true))
            }
        }
    }
}

/**
 * The same two answers in the oldge kit (B-160), about a torrent with a Russian name: the dialog's
 * buttons are `OldgeButton`, and *Remove* is the chrome one because the design system's lime is
 * its "go".
 */
@ViddikScreenshot(name = "dialog-oldge", group = "remove", width = 576, height = 760)
@Composable
internal fun RemoveTorrentOldgeSheet() {
    val state =
        RemoveState(name = "Мастер и Маргарита (1994).mkv", where = "~/Downloads", howMuch = "2.89 GiB on disk")
    KachokTheme(kit = OldgeKit) {
        Box(
            Modifier.fillMaxSize().background(KachokTheme.colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                RemoveTorrentDialog(state)
                RemoveTorrentDialog(state.withData(true))
            }
        }
    }
}
