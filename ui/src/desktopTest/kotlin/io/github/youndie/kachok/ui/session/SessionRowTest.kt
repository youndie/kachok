package io.github.youndie.kachok.ui.session

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.session.SessionState
import io.github.youndie.kachok.ui.list.TorrentState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `SessionState` as a row, and the three places where the design asks for something the engine
 * does not have.
 *
 * This is the half of [B-52](../../../../../../../../docs/backlog/B-52-ui-on-the-real-engine.md)
 * that can be asserted without a swarm; `AppDownloadTest` is the other half.
 */
class SessionRowTest {
    private val mib = 1024L * 1024

    private fun state(
        downloaded: Long = 0,
        uploaded: Long = 0,
        total: Long = 100 * mib,
        complete: Boolean = false,
        verifiedPieces: Int = 0,
        verifyingOf: Int = 0,
        connected: Int = 0,
        unchoked: Int = 0,
        outstanding: Int = 0,
        sessionError: String? = null,
        paused: Boolean = false,
    ) = SessionState(
        infoHash = InfoHash(ByteArray(20)),
        name = "payload.bin",
        totalLength = total,
        pieceCount = 100,
        downloaded = downloaded,
        uploaded = uploaded,
        left = total - downloaded,
        connectedPeers = connected,
        unchokedPeers = unchoked,
        outstandingRequests = outstanding,
        verifiedPieces = verifiedPieces,
        verifyingOf = verifyingOf,
        sessionError = sessionError,
        isComplete = complete,
        paused = paused,
    )

    @Test
    fun aRunningDownloadIsTheDesignsDownloadingRow() {
        val row =
            rowOf(
                state(downloaded = 78 * mib, uploaded = 8 * mib, connected = 24, unchoked = 4, outstanding = 61),
                Rates(down = 4312 * 1024, up = 812 * 1024),
            )
        assertEquals(TorrentState.Downloading, row.state)
        assertEquals("100 MiB", row.size)
        assertEquals("78%", row.percent)
        assertEquals("4 312", row.down)
        assertEquals("812", row.up)
        assertEquals("0.10", row.ratio)
        assertEquals(24, row.connected)
        assertEquals(4, row.unchoked)
        assertEquals(61, row.outstanding)
    }

    /** *Checking* shows its own progress: pieces verified, not bytes downloaded. */
    @Test
    fun aCheckingRowCountsPiecesAndNotBytes() {
        val row = rowOf(state(downloaded = 0, verifiedPieces = 27, verifyingOf = 100), Rates())
        assertEquals(TorrentState.Checking, row.state)
        assertEquals("27%", row.percent)
    }

    @Test
    fun aFinishedTorrentSeedsForEver() {
        val row = rowOf(state(downloaded = 100 * mib, complete = true, connected = 31, unchoked = 4), Rates())
        assertEquals(TorrentState.Seeding, row.state)
        assertEquals("100%", row.percent)
        assertEquals(Figures.FOREVER, row.eta)
    }

    /**
     * A degraded session outranks everything: it is the one thing on the screen a person has to
     * act on, so it cannot be hidden behind "but it is also still downloading".
     */
    @Test
    fun aDegradedSessionIsAnErrorRowWhateverElseIsTrue() {
        val row =
            rowOf(
                state(downloaded = 50 * mib, connected = 24, sessionError = "writer loop failed"),
                Rates(down = 1024),
            )
        assertEquals(TorrentState.Error, row.state)
    }

    /** The two states the surface owns, because the engine has nothing to observe them by. */
    @Test
    fun theLifecycleSuppliesMetadataAndStopping() {
        assertEquals(TorrentState.Metadata, stateOf(state(), Lifecycle.Fetching))
        assertEquals(TorrentState.Stopping, stateOf(state(), Lifecycle.Stopping))
        val fetching = rowOf(state(), Rates(), Lifecycle.Fetching)
        assertEquals(Figures.DASH, fetching.size)
        assertEquals(Figures.DASH, fetching.ratio)
        assertEquals(Figures.DASH, fetching.eta)
        assertNull(fetching.progress, "a magnet has no percentage to draw")
    }

    /**
     * *Paused* is a state the engine reports now, and this is what used to assert it could not be.
     *
     * The replacement asserts the ordering rather than the existence, because that is where the
     * mistake is: a complete torrent that is paused is still not uploading, and a *Seeding* row on
     * it would be the window claiming a swarm this client has hung up on. *Error* is the one thing
     * that outranks it — a session that failed needs looking at whether or not somebody paused it.
     */
    @Test
    fun aPausedTorrentIsPausedWhateverElseItWasDoing() {
        assertEquals(TorrentState.Paused, stateOf(state(paused = true), Lifecycle.Running))
        assertEquals(
            TorrentState.Paused,
            stateOf(state(paused = true, complete = true), Lifecycle.Running),
            "a paused seed is not seeding",
        )
        assertEquals(
            TorrentState.Paused,
            stateOf(state(paused = true, verifiedPieces = 1, verifyingOf = 2), Lifecycle.Running),
        )
        assertEquals(
            TorrentState.Error,
            stateOf(state(paused = true, sessionError = "x"), Lifecycle.Running),
            "a degraded session is Error whether or not it is paused",
        )
        assertEquals(
            TorrentState.Stopping,
            stateOf(state(paused = true), Lifecycle.Stopping),
            "leaving outranks waiting",
        )
    }

    /**
     * A remembered torrent that will not open is a row in `Error` and nothing else.
     *
     * Every figure on it is a dash rather than a zero: there is no session, so `0 KiB/s` and `0%`
     * would be measurements of a torrent this client cannot even read
     * ([B-81](../../../../../../../../docs/backlog/B-81-the-torrent-list-survives-a-restart.md)).
     */
    @Test
    fun aTorrentThatCannotBeOpenedIsAnErrorRowOfDashes() {
        val row = brokenRow("alpha.bin")
        assertEquals("alpha.bin", row.name)
        assertEquals(TorrentState.Error, row.state)
        assertEquals(
            listOf(Figures.DASH, Figures.DASH, Figures.DASH, Figures.DASH),
            listOf(row.size, row.percent, row.ratio, row.eta),
        )
        assertEquals(null, row.progress, "a bar would claim a fraction of something nobody read")
    }

    /** An ETA divided by a rate of zero is infinity; the design draws a dash instead of claiming it. */
    @Test
    fun anEtaWithNoRateIsADashRatherThanForever() {
        assertEquals(Figures.DASH, rowOf(state(downloaded = mib, connected = 1), Rates()).eta)
        assertEquals(
            "1m 39s",
            rowOf(state(downloaded = mib, connected = 1), Rates(down = mib)).eta,
        )
    }
}
