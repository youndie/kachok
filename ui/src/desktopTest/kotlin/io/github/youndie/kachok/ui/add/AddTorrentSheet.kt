package io.github.youndie.kachok.ui.add

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.youndie.kachok.ui.theme.KachokTheme
import io.github.youndie.viddik.annotations.ViddikScreenshot

/** The dialog for a `.torrent`, which is the case that can say everything. */
@ViddikScreenshot(name = "dialog", group = "add", width = 560, height = 580)
@Composable
internal fun AddTorrentSheet() {
    KachokTheme {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            AddTorrentDialog(designTorrentToAdd)
        }
    }
}

/**
 * And for a magnet, which cannot.
 *
 * The AC's own sentence: the hash where the name would be. Side by side with the file case in the
 * same golden would hide that the dialog is *shorter* — no file list at all — which is the visible
 * half of "a magnet carries none of the torrent".
 */
@ViddikScreenshot(name = "magnet", group = "add", width = 560, height = 380)
@Composable
internal fun AddMagnetSheet() {
    KachokTheme {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            AddTorrentDialog(designMagnetToAdd)
        }
    }
}

/** The two ways in that are not a dialog: a drag over the window, and a link already copied. */
@ViddikScreenshot(name = "gestures", group = "add", width = 760, height = 360)
@Composable
internal fun AddGesturesSheet() {
    KachokTheme {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.width(728.dp).height(150.dp)) {
                DropOverlay(
                    listOf("debian-13.1.0…torrent", "archlinux-2026.09…torrent"),
                )
            }
            Row(Modifier.width(728.dp)) {
                ClipboardMagnetPrompt(
                    "magnet:?xt=urn:btih:e4f2c1a9d3b7…&dn=archlinux-2026.09.01",
                )
            }
        }
    }
}
