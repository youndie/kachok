package io.github.youndie.kachok.engine.picker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bit order BEP 3 specifies, and the counter that has to agree with it.
 *
 * "The first byte of the bitfield corresponds to indices 0 - 7 from **high bit to low bit**" — the
 * one sentence in the specification that a natural implementation gets backwards, because a `Long`
 * numbers its bits the other way. Nothing else in this engine notices: a reversed field still round
 * trips through itself, still counts the same, and only a real peer ever disagrees.
 */
class BitfieldTest {
    @Test
    fun theFirstPieceIsTheHighBitOfTheFirstByte() {
        val field = Bitfield(TEN).apply { set(0) }
        assertEquals(0x80.toByte(), field.toBytes().first(), "piece 0 is the high bit, not the low one")
    }

    @Test
    fun theEighthPieceIsTheLowBitOfTheFirstByte() {
        assertEquals(0x01.toByte(), Bitfield(TEN).apply { set(7) }.toBytes().first())
    }

    @Test
    fun theNinthPieceStartsTheSecondByte() {
        val bytes = Bitfield(TEN).apply { set(8) }.toBytes()
        assertEquals(0x00.toByte(), bytes[0])
        assertEquals(0x80.toByte(), bytes[1])
    }

    /** Ten pieces need two bytes, and the six spare bits are zero. */
    @Test
    fun theSpareBitsAreZeroAndTheLengthIsRoundedUp() {
        val whole = Bitfield(TEN).apply { (0 until TEN).forEach { set(it) } }
        val bytes = whole.toBytes()
        assertEquals(2, bytes.size)
        assertEquals(0xFF.toByte(), bytes[0])
        // 1100 0000: two pieces in the second byte and six spare bits left at zero.
        assertEquals(0xC0.toByte(), bytes[1])
    }

    @Test
    fun whatGoesOutComesBack() {
        val field = Bitfield(TEN).apply { listOf(0, 3, 7, 9).forEach { set(it) } }
        val read = Bitfield.fromBytes(field.toBytes(), TEN)
        (0 until TEN).forEach { assertEquals(field[it], read[it], "piece $it") }
        assertEquals(field.cardinality, read.cardinality)
    }

    /**
     * A peer that sets a spare bit is refused.
     *
     * It is either speaking a different protocol or claiming a piece the torrent does not have, and
     * accepting it would put an out-of-range index into the availability array.
     */
    @Test
    fun aSetSpareBitIsRefused() {
        assertFailsWith<IllegalArgumentException> { Bitfield.fromBytes(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), TEN) }
    }

    @Test
    fun aFieldOfTheWrongLengthIsRefused() {
        assertFailsWith<IllegalArgumentException> { Bitfield.fromBytes(byteArrayOf(0xFF.toByte()), TEN) }
        assertFailsWith<IllegalArgumentException> {
            Bitfield.fromBytes(byteArrayOf(0, 0, 0), TEN)
        }
    }

    /** The counter is a counter and not a scan, so setting a bit twice must not count it twice. */
    @Test
    fun theCardinalityCountsPiecesAndNotCalls() {
        val field = Bitfield(TEN)
        assertEquals(0, field.cardinality)
        field.set(4)
        field.set(4)
        assertEquals(1, field.cardinality)
        assertFalse(field.isComplete)
        (0 until TEN).forEach { field.set(it) }
        assertEquals(TEN, field.cardinality)
        assertTrue(field.isComplete)
    }

    /** Clearing a bit that is not set is not a decrement. */
    @Test
    fun clearingOneBitLeavesTheRestAndTheCountRight() {
        val field = Bitfield(TEN).apply { listOf(1, 2, 3).forEach { set(it) } }
        field.clear(2)
        field.clear(2)
        field.clear(9)
        assertEquals(2, field.cardinality)
        assertTrue(field[1] && field[3])
        assertFalse(field[2])
    }

    @Test
    fun clearingTheWholeFieldEmptiesItAndTheCount() {
        val field = Bitfield(TEN).apply { (0 until TEN).forEach { set(it) } }
        field.clear()
        assertEquals(0, field.cardinality)
        assertFalse(field.isComplete)
        (0 until TEN).forEach { assertFalse(field[it], "piece $it survived") }
        assertTrue(field.toBytes().all { it == 0.toByte() })
    }

    /** An index outside the torrent is a bug in the caller, not a piece nobody has. */
    @Test
    fun anIndexOutsideTheTorrentIsRefusedByEveryAccessor() {
        val field = Bitfield(TEN)
        assertFailsWith<IllegalArgumentException> { field[TEN] }
        assertFailsWith<IllegalArgumentException> { field.set(TEN) }
        assertFailsWith<IllegalArgumentException> { field.clear(-1) }
    }

    /** A torrent whose piece count is not a multiple of 64 still uses every word it has. */
    @Test
    fun aFieldLongerThanOneWordWorksAtTheBoundary() {
        val big = 130
        val field = Bitfield(big).apply { listOf(0, 63, 64, 127, 129).forEach { set(it) } }
        assertEquals(5, field.cardinality)
        listOf(0, 63, 64, 127, 129).forEach { assertTrue(field[it], "piece $it") }
        assertFalse(field[65])
        val read = Bitfield.fromBytes(field.toBytes(), big)
        assertEquals(5, read.cardinality)
        assertTrue(read[129], "the last piece did not survive the wire")
    }

    private companion object {
        const val TEN = 10
    }
}
