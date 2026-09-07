package io.github.youndie.kachok.ui.icons

import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter

/**
 * The window's icon: the same drawing the installer puts on the desktop.
 *
 * `jpackage` only dresses an *installed* build, and it reads the three files
 * `scripts/make_icon.py` writes ([B-86](../../../../../../../../../docs/backlog/B-86-the-application-icon.md)).
 * A jar run straight from Gradle is not an installed build, so without this the window somebody
 * develops against carries the stock Java coffee cup — which is every window anyone here sees.
 *
 * Read once and held: an icon is set on a window that outlives every recomposition, and decoding a
 * 512 px PNG on each one would be work done for nobody. The classpath entry is
 * `ui/src/desktopMain/resources/icon/icon.png`, generated, and `make check` compares it against the
 * geometry it claims to be.
 */
internal val appIcon: Painter by lazy {
    val bytes =
        checkNotNull(AppIconMarker::class.java.getResourceAsStream("/icon/icon.png")) {
            "icon/icon.png is not on the classpath: run `python3 scripts/make_icon.py`"
        }.use { it.readBytes() }
    BitmapPainter(bytes.decodeToImageBitmap())
}

/** Somewhere to hang `getResourceAsStream` — a file-level property has no class of its own. */
private class AppIconMarker
