package ru.workinprogress.kachok.ui.icons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The window icon is loaded from a *string*, and a string that no longer names a file fails quietly.
 *
 * `AppFrame(icon = …)` takes a `Painter`; if the resource is missing the choice is between throwing
 * on startup and drawing nothing, and nothing looks exactly like the stock Java coffee cup that was
 * there before ([B-86](../../../../../../../../docs/backlog/B-86-the-application-icon.md)). Neither
 * a golden nor a compile can tell: the path is a literal and the resources directory is a build
 * input nobody else reads.
 *
 * What `make check`'s `make_icon.py --check` guards is that the committed files match the geometry.
 * What this guards is that one of them is on the classpath under the name the window asks for.
 */
class AppIconTest {
    @Test
    fun theWindowIconIsOnTheClasspathUnderTheNameTheWindowAsksFor() {
        val stream = AppIconTest::class.java.getResourceAsStream("/icon/icon.png")
        assertNotNull(stream, "icon/icon.png is not packaged: the window would open with no icon")
        val header = stream.use { it.readNBytes(24) }
        assertEquals(
            listOf(0x89, 'P'.code, 'N'.code, 'G'.code),
            header.take(4).map { it.toInt() and 0xFF },
            "the window icon is not a PNG",
        )

        // Width and height, big-endian, at offset 16 of the IHDR: `jpackage` wants 512 on Linux and
        // a smaller one there would be resampled rather than drawn.
        fun intAt(offset: Int) = (0..3).fold(0) { acc, i -> (acc shl 8) or (header[offset + i].toInt() and 0xFF) }
        assertEquals(512, intAt(16))
        assertEquals(512, intAt(20))
    }
}
