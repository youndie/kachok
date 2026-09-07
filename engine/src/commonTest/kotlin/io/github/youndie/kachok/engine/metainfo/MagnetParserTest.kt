package io.github.youndie.kachok.engine.metainfo

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The acceptance criteria of B-05 and the magnet scenarios of feature-metainfo.
 *
 * The two spellings of the hash are the same 20 bytes; the base32 form was produced by Python's
 * `base64.b32encode` over the hex, not by this code.
 */
class MagnetParserTest {
    private val hex = "b9f1cc77410aac4f6169c7c129113c198d440625"
    private val base32 = "XHY4Y52BBKWE6YLJY7ASSEJ4DGGUIBRF"

    @Test
    fun bothHashEncodingsDecodeToTheSameIdentity() {
        val first =
            MagnetParser.parse(
                "magnet:?xt=urn:btih:$hex&dn=readme&tr=http%3A%2F%2Ftracker.example%2Fannc" +
                    "&tr=udp%3A%2F%2Fother.example%2Fannc",
            )
        val second =
            MagnetParser.parse(
                "magnet:?xt=urn:btih:$base32&dn=readme&tr=http%3A%2F%2Ftracker.example%2Fannc" +
                    "&tr=udp%3A%2F%2Fother.example%2Fannc",
            )
        assertTrue(first.infoHash.bytes.contentEquals(second.infoHash.bytes))
        assertEquals(hex, first.infoHash.bytes.toHex())
        assertEquals(
            listOf("http://tracker.example/annc", "udp://other.example/annc"),
            first.trackers,
        )
        assertEquals(first.trackers, second.trackers)
        assertEquals("readme", first.displayName)
    }

    @Test
    fun lowercaseBase32IsAccepted() {
        val link = MagnetParser.parse("magnet:?xt=urn:btih:${base32.lowercase()}")
        assertEquals(hex, link.infoHash.bytes.toHex())
    }

    @Test
    fun aLinkWithoutXtIsRefused() {
        val thrown =
            assertFailsWith<MetainfoException> {
                MagnetParser.parse("magnet:?dn=only-a-name")
            }
        assertContains(thrown.message ?: "", "xt")
    }

    @Test
    fun aHashOfTheWrongLengthIsRefused() {
        val thrown =
            assertFailsWith<MetainfoException> {
                MagnetParser.parse("magnet:?xt=urn:btih:abcdef")
            }
        assertContains(thrown.message ?: "", "6 characters")
    }

    @Test
    fun somethingThatIsNotAMagnetLinkIsRefused() {
        val thrown =
            assertFailsWith<MetainfoException> {
                MagnetParser.parse("http://example/x.torrent")
            }
        assertContains(thrown.message ?: "", "magnet")
    }

    @Test
    fun unknownParametersAreIgnoredAndTrackersKeepTheirOrder() {
        val link =
            MagnetParser.parse(
                "magnet:?xt=urn:btih:$hex&tr=http%3A%2F%2Fb.example%2Fa&xl=1234" +
                    "&tr=http%3A%2F%2Fa.example%2Fa&ws=http%3A%2F%2Fseed.example%2Ff",
            )
        assertEquals(listOf("http://b.example/a", "http://a.example/a"), link.trackers)
        assertNull(link.displayName)
    }

    @Test
    fun aTrackerUrlKeepsItsQueryAndItsPlus() {
        val link =
            MagnetParser.parse(
                "magnet:?xt=urn:btih:$hex&tr=http%3A%2F%2Ftracker.example%2Fannc%3Fx%3D1%26y%3Da+b",
            )
        // `+` stays a `+`: the form-encoding convention that reads it as a space is not this URI's.
        assertEquals(listOf("http://tracker.example/annc?x=1&y=a+b"), link.trackers)
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
