package ru.workinprogress.kachok.engine.peer

import ru.workinprogress.kachok.engine.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BEP 20 is a convention, not a rule, and this is drawn in a table one line high.
 *
 * Every case here is a peer id that is legal on the wire. The parse may not throw on any of them
 * and may not come back empty on any of them: a row with nothing in its client column is worse than
 * one saying `unknown`, because a reader cannot tell it from a rendering fault.
 */
class PeerClientTest {
    private fun id(prefix: String): PeerId =
        PeerId(ByteArray(PeerId.SIZE) { at -> prefix.getOrNull(at)?.code?.toByte() ?: 0x2E })

    @Test
    fun theAzureusConventionIsRead() {
        assertEquals("qBittorrent 5.0.1", clientOf(id("-qB5010-")))
        assertEquals("Transmission 4.0.5", clientOf(id("-TR4050-")))
        assertEquals("µTorrent 3.5.5", clientOf(id("-UT3550-")))
        assertEquals("kachok 0.1", clientOf(id("-KA0100-")))
    }

    /**
     * Trailing zeroes go, down to two components and no further.
     *
     * `qBittorrent 5.1.0.0` is a column of noise; `libtorrent 2` is a different-looking claim from
     * `libtorrent 2.0`, which is what the design's own reference writes.
     */
    @Test
    fun aVersionKeepsTwoComponentsAndDropsTheZeroesAfterThem() {
        assertEquals("libtorrent 2.0", clientOf(id("-LT2000-")))
        assertEquals("qBittorrent 5.1", clientOf(id("-qB5100-")))
        assertEquals("Deluge 2.1.1", clientOf(id("-DE2110-")))
    }

    /** A code nobody has heard of is printed as itself: two characters somebody can search for. */
    @Test
    fun anUnknownTwoLetterCodeIsPrintedRatherThanDiscarded() {
        assertEquals("ZZ 1.2.3.4", clientOf(id("-ZZ1234-")))
    }

    @Test
    fun theShadowConventionIsReadToo() {
        assertEquals("BitTornado 0.9.9", clientOf(id("T099-")))
    }

    /**
     * And everything else falls back to whatever is printable.
     *
     * Twenty random bytes is a legal peer id — the specification says so in as many words — and it
     * is what a client that ignores the convention sends.
     */
    @Test
    fun anIdThatFollowsNoConventionIsNeverEmptyAndNeverThrows() {
        assertEquals("unknown", clientOf(PeerId(ByteArray(PeerId.SIZE))), "twenty zeroes")
        val random = PeerId(ByteArray(PeerId.SIZE) { at -> (at * 37 + 11).toByte() })
        assertTrue(clientOf(random).isNotBlank(), "a random id produced nothing to draw")
        val allHigh = PeerId(ByteArray(PeerId.SIZE) { 0xFF.toByte() })
        assertEquals("unknown", clientOf(allHigh), "nothing printable in it")
    }

    /** A peer that puts a newline or a tab in its id does not get to break the table. */
    @Test
    fun controlCharactersDoNotReachTheTable() {
        val nasty = PeerId(ByteArray(PeerId.SIZE) { at -> if (at % 2 == 0) '\n'.code.toByte() else 'x'.code.toByte() })
        val name = clientOf(nasty)
        assertTrue(name.none { it.code < 0x20 }, "a control character reached the row: $name")
    }

    /** However long an id pretends to be, one row's worth is what comes out. */
    @Test
    fun theAnswerFitsInARow() {
        assertTrue(clientOf(PeerId(ByteArray(PeerId.SIZE) { 'W'.code.toByte() })).length <= PeerId.SIZE)
    }
}
