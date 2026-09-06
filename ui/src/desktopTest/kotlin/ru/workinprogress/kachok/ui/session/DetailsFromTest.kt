package ru.workinprogress.kachok.ui.session

import ru.workinprogress.kachok.engine.session.PeerView
import ru.workinprogress.kachok.engine.session.SessionState
import ru.workinprogress.kachok.ui.details.DetailsTab
import ru.workinprogress.kachok.ui.details.FieldTone
import ru.workinprogress.kachok.ui.main.designDetails
import ru.workinprogress.kachok.ui.main.designSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The acceptance criterion of [B-49](../../../../../../../../docs/backlog/B-49-details-panel.md):
 * every field in *Overview* reads from `SessionState`, and the planned ones are visibly marked.
 *
 * The fixture is a `SessionState` carrying the design's own numbers, put through the same mapping
 * the running window uses — so this asserts that the panel's strings *come out of* the session
 * rather than that some strings were written down twice.
 */
class DetailsFromTest {
    private val details = designDetails()

    private fun value(label: String) =
        details.sections
            .flatMap { it.fields }
            .single { it.label == label }

    @Test
    fun everyOverviewFieldComesOutOfTheSession() {
        assertEquals("2b3a…c7f1", value("Info hash").value)
        assertEquals("3.70 GiB", value("Total length").value)
        assertEquals("2.00 MiB", value("Piece length").value)
        assertEquals("~/Downloads/iso", value("Save to").value)
        assertEquals("1 384 / 1 772", value("Pieces").value)
        assertEquals("2.89 GiB", value("Downloaded").value)
        assertEquals("828 MiB", value("Left").value)
        assertEquals("412 MiB", value("Uploaded").value)
        assertEquals("24", value("Connected peers").value)
        assertEquals("4", value("Unchoked by them").value)
        assertEquals("61", value("Requests in flight").value)
        assertEquals("187", value("Known peers").value)
        assertEquals("19", value("Extended (BEP 10)").value)
        assertEquals("214", value("DHT nodes").value)
        assertEquals("2", value("Hash failures").value)
        assertEquals("1 772 / 1 772", value("Verified on start-up").value)
    }

    /**
     * The design's own numbers do not divide into its own ratio: 412 MiB over 2.89 GiB is 0.14 and
     * the mockup writes 0.11. Derived here, so the panel says what the session actually is.
     */
    @Test
    fun theRatioIsDividedRatherThanCopied() {
        assertEquals("0.14", value("Ratio").value)
        assertEquals(
            Figures.ratio(designSession.uploaded, designSession.downloaded),
            value("Ratio").value,
        )
    }

    /**
     * A path is elided from the front, because what identifies a directory is its last component.
     *
     * `Save to /private/tmp/claude-501/-Users-youndie-…` is what the panel showed when it was cut
     * the other way: every character of it is the same for every torrent on the machine.
     */
    @Test
    fun theOnlyPathFieldIsMarkedAsOne() {
        assertTrue(value("Save to").path)
        assertTrue(
            details.sections.flatMap { it.fields }.count { it.path } == 1,
            "only a path is elided from the front",
        )
    }

    /**
     * Nothing in the panel wears the badge any more, and the speeds are why.
     *
     * They were the one field the engine had no counter behind — it counted totals and nothing per
     * second. It counts per second per peer now, for the choker, and the panel adds those up
     * ([B-77](../../../../../../../../docs/backlog/B-77-the-rate-column-reads-zero.md)). The
     * assertion is inverted rather than deleted: the day a field is drawn ahead of its number
     * again, this is where the badge belongs.
     */
    @Test
    fun noFieldIsDrawnAheadOfItsNumber() {
        assertEquals(
            emptyList(),
            details.sections
                .flatMap { it.fields }
                .filter { it.planned }
                .map { it.label },
        )
        assertEquals(
            "4 312 / 812",
            details.sections
                .flatMap { it.fields }
                .single { it.label == "Speed down / up" }
                .value,
        )
    }

    /**
     * Two numbers say whether the download can move at all, and they are green only when they are
     * non-zero — a zero drawn in the "this is fine" colour is the panel agreeing with a stall.
     */
    @Test
    fun theTwoNumbersThatDecideProgressAreMarkedOnlyWhenTheyAreThere() {
        assertEquals(FieldTone.Good, value("Unchoked by them").tone)
        assertEquals(FieldTone.Good, value("Requests in flight").tone)
        val stalled =
            detailsOf(
                state = designSession,
                rates = Rates(),
                pieceLength = 2L * 1024 * 1024,
                directory = ".",
            )
        assertEquals(
            FieldTone.Good,
            stalled.sections
                .flatMap { it.fields }
                .single { it.label == "Unchoked by them" }
                .tone,
        )
        assertEquals(FieldTone.Warning, value("Hash failures").tone, "a discarded piece is worth seeing")
    }

    @Test
    fun bothComplaintsAreCarriedVerbatimAndTheSessionSaysItIsFine() {
        assertEquals(listOf("TRACKER", "LAST PEER"), details.complaints.map { it.title })
        assertEquals(designSession.trackerError, details.complaints.first().text)
        assertEquals(designSession.lastPeerError, details.complaints.last().text)
        assertTrue(details.complaints.first().warning, "a tracker's refusal is the warning role")
        assertTrue(!details.complaints.last().warning, "a peer that hung up is not")
        assertEquals(null, details.sessionError)
    }

    /**
     * The tracker cards, and the status the design does not have a colour for.
     *
     * BEP 12 has a client use the first tracker that answers, so a torrent with three trackers
     * normally has one that worked and two nobody touched. Hiding the untouched ones would make a
     * three-tracker torrent look like a one-tracker torrent, so *not tried* is a status of its own.
     */
    @Test
    fun everyAnnounceUrlGetsACardIncludingTheOnesNobodyReached() {
        val details = designDetails(DetailsTab.Trackers)
        assertEquals(
            designSession.trackers.map { it.url },
            details.trackers.map { it.url },
            "a tracker was dropped, or the list was reordered",
        )
        assertEquals(listOf("failed", "working", "not tried"), details.trackers.map { it.status })
        assertEquals("announce failed: 502 Bad Gateway", details.trackers.first().message)
        assertEquals(null, details.trackers.last().message, "a tracker nobody tried has nothing to say")
    }

    /** The figures read the way the design writes them: one unit, coarsest that says something. */
    @Test
    fun aWorkingTrackerSaysWhenAndHowMany() {
        val working = designDetails(DetailsTab.Trackers).trackers[1]
        assertEquals("3 m ago · 142 peers · next in 27 m", working.detail)
    }

    @Test
    fun theSummaryCountsTheTrackersAndSaysWhetherTheDhtIsIn() {
        val details = designDetails(DetailsTab.Trackers)
        assertEquals("3 trackers + DHT", details.trackersSummary)
        assertEquals("214 nodes · announced 6 m ago · next in 9 m", details.dht)
    }

    /**
     * The peer rows, in the order the design puts them: doing something at the top.
     *
     * Descending by rate, and ties left exactly where the engine had them. A tiebreak on the
     * address was the first version and is wrong twice: it is a text sort of IPv4, which puts
     * `5.181.190.7` after `45.83.220.66`, and it reorders the design's own reference.
     */
    @Test
    fun thePeerRowsAreSortedByRateAndTiesKeepTheEnginesOrder() {
        val rows =
            rowsFor(
                peer("10.0.0.3:6881", down = 0, choking = true),
                peer("10.0.0.1:6881", down = 0, choking = true),
                peer("10.0.0.2:6881", down = 1_842 * 1024, choking = false, interested = true),
            )

        assertEquals(
            listOf("10.0.0.2:6881", "10.0.0.3:6881", "10.0.0.1:6881"),
            rows.map { it.address },
            "the two idle peers were reordered",
        )
        assertEquals(true, rows.first().unchoked)
        assertEquals(true, rows.first().interested)
        assertEquals(false, rows.last().unchoked)
    }

    /** And an address is never compared as text: `5.181` is below `45.83`, not above it. */
    @Test
    fun anAddressIsNeverUsedToOrderTheList() {
        val rows =
            rowsFor(
                peer("45.83.220.66:24810", down = 0, choking = true),
                peer("5.181.190.7:6892", down = 0, choking = true),
            )
        assertEquals(listOf("45.83.220.66:24810", "5.181.190.7:6892"), rows.map { it.address })
    }

    /** A peer whose id said nothing usable gets the same dash every other unknown figure gets. */
    @Test
    fun aPeerWithNoUsableClientStringIsADashRatherThanAnEmptyCell() {
        assertEquals(
            Figures.DASH,
            rowsFor(peer("10.0.0.9:6881", down = 0, choking = true, client = "unknown")).single().client,
        )
    }

    /** The peer rows `detailsOf` makes out of a session carrying exactly these peers. */
    private fun rowsFor(vararg peers: PeerView) =
        detailsOf(
            SessionState(
                infoHash = designSession.infoHash,
                name = designSession.name,
                totalLength = designSession.totalLength,
                pieceCount = designSession.pieceCount,
                peers = peers.toList(),
            ),
            Rates(),
            pieceLength = 0,
            directory = "/tmp",
        ).peers

    private fun peer(
        address: String,
        down: Long,
        choking: Boolean,
        interested: Boolean = false,
        client: String = "qBittorrent 5.0.1.0",
    ) = PeerView(
        address = address,
        client = client,
        dialled = true,
        choking = choking,
        choked = true,
        interested = interested,
        peerInterested = false,
        fast = false,
        extended = false,
        outstanding = 0,
        pieces = 0,
        downBytesPerSecond = down,
        upBytesPerSecond = 0,
    )

    private companion object {
        const val SHORT = 40
    }
}
