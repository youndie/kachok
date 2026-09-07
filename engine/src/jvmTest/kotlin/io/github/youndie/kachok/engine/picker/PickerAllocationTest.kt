package io.github.youndie.kachok.engine.picker

import io.github.youndie.kachok.engine.bencode.BDictionary
import io.github.youndie.kachok.engine.bencode.BInteger
import io.github.youndie.kachok.engine.bencode.BString
import io.github.youndie.kachok.engine.bencode.Bencode
import io.github.youndie.kachok.engine.metainfo.Metainfo
import io.github.youndie.kachok.engine.metainfo.MetainfoParser
import io.github.youndie.kachok.engine.peer.PeerAddress
import io.github.youndie.kachok.engine.wire.PeerWire
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The acceptance criterion of B-43, measured rather than reasoned about.
 *
 * The claim is about *allocation*, so the test measures allocation: `getThreadAllocatedBytes` over
 * a hundred choices on a hundred-thousand-piece torrent. The old code built a `List<Int>` of every
 * candidate on every request, which is a boxed `Integer` per piece — sixteen bytes times a hundred
 * thousand, about 1.6 MB per decision. The bound below is a thousandth of that, which is far
 * enough from both numbers that neither JIT noise nor escape analysis decides the outcome.
 *
 * JVM-only because the measurement is: there is no common way to ask what a thread allocated.
 */
class PickerAllocationTest {
    private val peer = PeerAddress("10.0.0.1", 6881)
    private val pieces = 100_000

    private fun torrent(): Metainfo {
        val info =
            BDictionary(
                mapOf(
                    BString("length") to BInteger(pieces.toLong() * PeerWire.BLOCK_SIZE),
                    BString("name") to BString("large"),
                    BString("piece length") to BInteger(PeerWire.BLOCK_SIZE.toLong()),
                    BString("pieces") to BString(ByteArray(pieces * Metainfo.HASH_SIZE)),
                ),
            )
        return MetainfoParser.parse(
            Bencode.encode(
                BDictionary(
                    mapOf(BString("announce") to BString("http://tracker.example/annc"), BString("info") to info),
                ),
            ),
        )
    }

    private fun everything(): ByteArray =
        Bitfield(pieces).also { bits -> (0 until pieces).forEach { bits.set(it) } }.toBytes()

    /**
     * One choice, with the piece it started freed again so the next call scans afresh.
     *
     * Without the `pieceFailed` the second call would be answered out of the started piece and
     * would never reach the scan this test is about.
     */
    private fun choose(picker: PiecePicker): Int {
        val requests = picker.next(peer, count = 1, nowMillis = 0)
        requests.forEach { picker.pieceFailed(it.piece) }
        return requests.size
    }

    @Test
    fun choosingABlockDoesNotAllocatePerPiece() {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val picker = PiecePicker(torrent(), maxStartedPieces = 1)
        picker.addPeer(peer)
        picker.setBitfield(peer, everything())
        // The first piece of a torrent is chosen at random, which is a different branch; give the
        // picker one piece so that every measured call is the rarest-first one.
        picker.pieceVerified(
            io.github.youndie.kachok.engine
                .PieceIndex(0),
        )

        var chosen = 0
        repeat(WARM_UP) { chosen += choose(picker) }
        val before = threads.getThreadAllocatedBytes(Thread.currentThread().threadId())
        repeat(MEASURED) { chosen += choose(picker) }
        val perChoice = (threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before) / MEASURED

        assertTrue(chosen == WARM_UP + MEASURED, "the picker returned nothing, so nothing was measured")
        assertTrue(
            perChoice < BUDGET_BYTES,
            "one choice over $pieces pieces allocated $perChoice bytes; a boxed integer per piece " +
                "would be about ${pieces * 16}",
        )
    }

    private companion object {
        const val WARM_UP = 200
        const val MEASURED = 100

        /** A thousandth of what one boxed integer per piece costs. */
        const val BUDGET_BYTES = 1_600L
    }
}
