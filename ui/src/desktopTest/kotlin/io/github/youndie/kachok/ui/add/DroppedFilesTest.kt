package io.github.youndie.kachok.ui.add

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.dnd.InvalidDnDOperationException
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The decision behind a drop, on transferables built by hand — every case the AWT event can carry,
 * including the one that made B-107: the flavour advertised and the data refused until the drop.
 */
class DroppedFilesTest {
    /** A transferable that advertises the file-list flavour and answers as told. */
    private class Files(
        private val answer: () -> List<File>,
    ) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.javaFileListFlavor

        override fun getTransferData(flavor: DataFlavor): Any {
            if (flavor != DataFlavor.javaFileListFlavor) throw UnsupportedFlavorException(flavor)
            return answer()
        }
    }

    private val torrent = File("/drops/debian-13.1.0-amd64-DVD-1.iso.torrent")
    private val iso = File("/drops/debian-13.1.0-amd64-DVD-1.iso")

    @Test
    fun theFilesOfADropAreReadAndTheFirstTorrentIsTheOneAdded() {
        val drop = Files { listOf(iso, torrent, File("/drops/other.torrent")) }
        assertTrue(DroppedFiles.offered(drop))
        assertEquals(listOf(iso.toPath(), torrent.toPath(), Path.of("/drops/other.torrent")), DroppedFiles.paths(drop))
        assertEquals(torrent.toPath(), DroppedFiles.firstTorrent(DroppedFiles.paths(drop)))
        assertEquals(listOf(iso.name, torrent.name, "other.torrent"), DroppedFiles.hovering(drop))
    }

    /**
     * The hover-time answer of every AWT platform for a drag from another application: the
     * flavour is there, the data is "No drop current". The overlay still has something to draw.
     */
    @Test
    fun aFlavourAdvertisedWithTheDataRefusedUntilTheDropIsAnUnnamedFileNotAThrow() {
        val hovering = Files { throw InvalidDnDOperationException("No drop current") }
        assertTrue(DroppedFiles.offered(hovering))
        assertEquals(emptyList(), DroppedFiles.paths(hovering))
        assertEquals(listOf(DroppedFiles.UNNAMED_DROP), DroppedFiles.hovering(hovering))
    }

    @Test
    fun aDragOfSomethingOtherThanFilesOffersNothing() {
        val text = StringSelection("magnet:?xt=urn:btih:2b3a91c4")
        assertFalse(DroppedFiles.offered(text))
        assertEquals(emptyList(), DroppedFiles.paths(text))
        assertEquals(emptyList(), DroppedFiles.hovering(text))
    }

    @Test
    fun filesWithNoTorrentAmongThemAddNothing() {
        val drop = Files { listOf(iso) }
        assertNull(DroppedFiles.firstTorrent(DroppedFiles.paths(drop)))
        assertEquals(listOf(iso.name), DroppedFiles.hovering(drop), "the overlay still names what hovers")
    }

    @Test
    fun aTransferableThatCannotBeReadIsAnEmptyDrop() {
        assertEquals(emptyList(), DroppedFiles.paths(Files { throw IOException("gone") }))
        assertEquals(
            emptyList(),
            DroppedFiles.paths(Files { throw UnsupportedFlavorException(DataFlavor.javaFileListFlavor) }),
        )
    }
}
