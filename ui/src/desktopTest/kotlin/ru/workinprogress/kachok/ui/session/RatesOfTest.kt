package ru.workinprogress.kachok.ui.session

import ru.workinprogress.kachok.engine.InfoHash
import ru.workinprogress.kachok.engine.session.PeerView
import ru.workinprogress.kachok.engine.session.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rate a person reads, and the way it used to lie.
 *
 * It was a one-second delta of `SessionState.downloaded` — *verified* bytes, which advance one whole
 * piece at a time. At 60 KiB/s with 256 KiB pieces a piece lands every four seconds, so three
 * samples in four were exactly zero and the column read `0 0 0 250`. On a live download it sat at
 * zero while the progress bar moved: one number on screen contradicting another beside it, found by
 * watching rather than by a test.
 */
class RatesOfTest {
    private fun state(vararg peers: PeerView) =
        SessionState(
            infoHash = InfoHash(ByteArray(HASH_BYTES)),
            name = "payload.bin",
            totalLength = 100 * MIB,
            pieceCount = 400,
            // Deliberately far from the peers' rates: nothing here may be derived from it any more.
            downloaded = 42 * MIB,
            peers = peers.toList(),
        )

    private fun peer(
        down: Long,
        up: Long = 0,
    ) = PeerView(
        address = "10.0.0.1:6881",
        client = "kachok 0.1",
        dialled = true,
        choking = false,
        choked = false,
        interested = true,
        peerInterested = false,
        fast = false,
        extended = false,
        outstanding = 4,
        pieces = 100,
        downBytesPerSecond = down,
        upBytesPerSecond = up,
    )

    @Test
    fun theRateIsThePeersRatesAddedUp() {
        val rates = ratesOf(state(peer(down = 60 * KIB), peer(down = 40 * KIB, up = 8 * KIB)))
        assertEquals(100 * KIB, rates.down)
        assertEquals(8 * KIB, rates.up)
    }

    /** A torrent with no peers transfers nothing, whatever its piece counter says. */
    @Test
    fun noPeersIsNoRate() {
        assertEquals(0, ratesOf(state()).down)
        assertEquals(0, ratesOf(state()).up)
    }

    /**
     * A peer moving less than one piece a second still reads as something.
     *
     * This is the whole complaint: 64 KiB/s is 64 KiB/s on every tick, and never `0` on three of
     * four because a piece has not finished verifying.
     */
    @Test
    fun aRateBelowOnePieceASecondIsStillARate() {
        assertEquals(64 * KIB, ratesOf(state(peer(down = 64 * KIB))).down)
    }

    private companion object {
        const val HASH_BYTES = 20
        const val KIB = 1024L
        const val MIB = 1024L * 1024
    }
}
