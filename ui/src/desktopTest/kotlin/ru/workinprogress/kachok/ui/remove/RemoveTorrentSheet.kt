package ru.workinprogress.kachok.ui.remove

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.youndie.viddik.annotations.ViddikScreenshot
import ru.workinprogress.kachok.ui.theme.KachokTheme

/**
 * Both answers to the checkbox, one above the other.
 *
 * The difference between them is the whole point of the dialog, and it is a difference in wording
 * and colour — exactly the kind a golden catches and a unit test describes badly.
 */
@ViddikScreenshot(name = "dialog", group = "remove", width = 480, height = 560)
@Composable
internal fun RemoveTorrentSheet() {
    val state =
        RemoveState(name = "Sintel-2010-1080p-DCP.tar", where = "~/Downloads", howMuch = "2.89 GiB on disk")
    KachokTheme {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                RemoveTorrentDialog(state)
                RemoveTorrentDialog(state.withData(true))
            }
        }
    }
}
