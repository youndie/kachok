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

    /**
     * A transferable that advertises the flavour and answers whatever the platform feels like —
     * `null` included, which the JDK's own signature says is impossible and macOS does anyway.
     *
     * **A proxy and not a class**, because a Kotlin `override fun getTransferData(): Any` cannot
     * return null: the compiler inserts the check and the double then throws before the code under
     * test is reached — which it did, and which would have made this test pass for the wrong
     * reason. A `Proxy` returns whatever the handler says, exactly as a Java method compiled
     * without a Kotlin null check does.
     */
    private fun answering(answer: () -> Any?): Transferable =
        java.lang.reflect.Proxy.newProxyInstance(
            Transferable::class.java.classLoader,
            arrayOf(Transferable::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getTransferDataFlavors" -> arrayOf(DataFlavor.javaFileListFlavor)
                "isDataFlavorSupported" -> args?.firstOrNull() == DataFlavor.javaFileListFlavor
                "getTransferData" -> answer()
                else -> null
            }
        } as Transferable

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
        assertEquals(listOf(UNNAMED_DROP), DroppedFiles.hovering(hovering))
    }

    /**
     * **The answer macOS actually gives, and the one that kept this bug alive through its own fix.**
     *
     * Iteration 1 read `SunDropTargetContextPeer` and caught `InvalidDnDOperationException`, which
     * is what the JDK documents for a drag that is still hovering. Driven against Finder on 2026-09-20
     * the platform returned **null** instead, and the unchecked cast raised `NullPointerException` —
     * *"null cannot be cast to non-null type kotlin.collections.List<java.io.File>"*, thrown out of
     * `onEntered`, so Compose never called `acceptDrag`, so there was no overlay and no drop. The
     * symptom the item was filed for, unchanged by the fix that was supposed to end it
     * ([B-107](../../../../../../../../docs/backlog/B-107-dropping-a-torrent-does-nothing-on-macos.md)).
     */
    @Test
    fun aFlavourAdvertisedThatAnswersNullIsAnUnnamedFileNotACrash() {
        val hovering = answering { null }

        assertTrue(DroppedFiles.offered(hovering))
        assertEquals(emptyList(), DroppedFiles.paths(hovering))
        assertEquals(listOf(UNNAMED_DROP), DroppedFiles.hovering(hovering))
    }

    /** And anything else it might answer is no files rather than a class cast at the drop. */
    @Test
    fun aFlavourAdvertisedThatAnswersSomethingElseIsNoFiles() {
        assertEquals(emptyList(), DroppedFiles.paths(answering { "/drops/not-a-list.torrent" }))
        assertEquals(
            listOf(torrent.toPath()),
            DroppedFiles.paths(answering { listOf("a string", torrent) }),
            "a list with something else in it hid the file that was really there",
        )
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
