package ru.workinprogress.kachok.ui.session

import ru.workinprogress.kachok.engine.InfoHash
import ru.workinprogress.kachok.engine.session.SessionState
import ru.workinprogress.kachok.ui.list.TorrentState
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
     * The design marks *paused* planned, and so does the engine's absence of one.
     *
     * The day `Command.Pause` exists this stops being true and somebody has to come back here —
     * which is the point of writing it down rather than leaving it as a gap nobody can see.
     */
    @Test
    fun pausedIsStillPlanned() {
        assertTrue(PAUSED_IS_PLANNED)
        val everyReachableState =
            listOf(Lifecycle.Fetching, Lifecycle.Running, Lifecycle.Stopping).flatMap { lifecycle ->
                listOf(
                    state(),
                    state(complete = true),
                    state(verifiedPieces = 1, verifyingOf = 2),
                    state(sessionError = "x"),
                ).map { stateOf(it, lifecycle) }
            }
        assertTrue(
            TorrentState.Paused !in everyReachableState,
            "nothing the engine can report is a paused torrent",
        )
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
