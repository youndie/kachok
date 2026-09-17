package io.github.youndie.kachok.ui.add

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.dnd.InvalidDnDOperationException
import java.io.IOException
import java.nio.file.Path

/**
 * What a drag is offering the window, read off its transferable — the decision, apart from the
 * AWT event that carries it, so that the decision has a test and the event stays the one line in
 * `App.kt` that unwraps it.
 *
 * **The names of the files are not readable before the drop, on any AWT platform.** Until the
 * drop is accepted, the drop-target peer answers `getTransferData` with
 * `InvalidDnDOperationException("No drop current")` for anything dragged in from another
 * application — the flavours are known, the data is not — and a hover that asks anyway and does
 * not catch that is a hover that throws out of `onEntered`, which Compose turns into a drag the
 * window never accepts: no overlay, no drop, the file flies back to where it came from. That is
 * [B-107](../../../../../../../../docs/backlog/B-107-dropping-a-torrent-does-nothing-on-macos.md),
 * read off `SunDropTargetContextPeer.getTransferData` rather than reproduced. So the overlay is
 * given the names when the platform will say them and [UNNAMED_DROP] when it will not, and is drawn
 * either way.
 */
internal object DroppedFiles {
    /**
     * Stands in for a file whose name the platform will not give before the drop, in the overlay's
     * list. An empty string, so nothing on disk can be mistaken for it.
     */
    const val UNNAMED_DROP: String = ""

    /** Whether the drag carries files at all; readable before the drop, unlike the files. */
    fun offered(transferable: Transferable): Boolean = transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)

    /**
     * The files the drag is carrying, or empty: for a drag of something else, for a transferable
     * whose flavour is not what it advertised — a browser tab, say — and for the hover-time case
     * where the flavour is there and the data is not yet.
     */
    @Suppress("UNCHECKED_CAST")
    fun paths(transferable: Transferable): List<Path> =
        try {
            if (!offered(transferable)) {
                emptyList()
            } else {
                (transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<java.io.File>)
                    .map { it.toPath() }
            }
        } catch (unsupported: UnsupportedFlavorException) {
            emptyList()
        } catch (unreadable: IOException) {
            emptyList()
        } catch (notYet: InvalidDnDOperationException) {
            emptyList()
        }

    /** What the overlay says while the drag hovers: the names, or one unnamed file, or nothing. */
    fun hovering(transferable: Transferable): List<String> =
        if (!offered(transferable)) {
            emptyList()
        } else {
            paths(transferable).map { it.fileName.toString() }.ifEmpty { listOf(UNNAMED_DROP) }
        }

    /**
     * The first `.torrent` among the files, and not all of them: the add dialog asks about one
     * torrent, and four would need a queue the window has not got.
     */
    fun firstTorrent(paths: List<Path>): Path? = paths.firstOrNull { it.toString().endsWith(".torrent") }
}
