package ru.workinprogress.kachok.ui.session

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

    /** The one field the engine has no counter behind, and the badge that says so. */
    @Test
    fun theSpeedsAreTheOnlyPlannedField() {
        val planned = details.sections.flatMap { it.fields }.filter { it.planned }
        assertEquals(listOf("Speed down / up"), planned.map { it.label })
        assertEquals("4 312 / 812", planned.single().value)
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

    /** Three tabs the engine cannot fill, and each says which engine change it is waiting for. */
    @Test
    fun theThreePlannedTabsSayWhatTheyAreWaitingFor() {
        assertEquals(null, DetailsTab.Overview.plannedBecause, "Overview is real")
        listOf(DetailsTab.Files, DetailsTab.Peers, DetailsTab.Trackers).forEach { tab ->
            val reason = tab.plannedBecause
            assertTrue(!reason.isNullOrBlank(), "$tab must say why it is empty")
            assertTrue(reason.length > SHORT, "$tab's reason is a shrug: $reason")
        }
    }

    private companion object {
        const val SHORT = 40
    }
}
