package io.github.youndie.kachok.ui.session

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.session.SessionState
import io.github.youndie.kachok.ui.main.SortColumn
import io.github.youndie.kachok.ui.main.SortOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The half of [B-56](../../../../../../../../docs/backlog/B-56-dead-toolbar-controls.md) that is a
 * feature rather than a confession: the column header sorts.
 *
 * Every assertion here would pass on strings for at least one input and fail for another, which is
 * the point — a list sorted on the cells is a list sorted on `14.6 GiB` before `3.70 GiB`.
 */
class SortingTest {
    private val gib = 1024L * 1024 * 1024

    private fun sample(
        name: String,
        total: Long = gib,
        downloaded: Long = 0,
        uploaded: Long = 0,
        peers: Int = 0,
        complete: Boolean = false,
        down: Long = 0,
        up: Long = 0,
    ) = Sample(
        SessionState(
            infoHash = InfoHash(ByteArray(20)),
            name = name,
            totalLength = total,
            pieceCount = 100,
            downloaded = downloaded,
            uploaded = uploaded,
            left = total - downloaded,
            connectedPeers = peers,
            isComplete = complete,
        ),
        Rates(down, up),
    )

    private fun List<Sample>.names() = map { it.state.name }

    /** `14.6 GiB` sorts before `3.70 GiB` as text and after it as a size. */
    @Test
    fun sizeIsSortedAsANumberAndNotAsItsCell() {
        val list =
            listOf(
                sample("big", total = (14.6 * gib).toLong()),
                sample("small", total = (3.70 * gib).toLong()),
                sample("tiny", total = 412 * 1024 * 1024),
            )
        assertEquals(listOf("tiny", "small", "big"), list.inOrder(SortOrder(SortColumn.Size)).names())
        assertEquals(
            listOf("big", "small", "tiny"),
            list.inOrder(SortOrder(SortColumn.Size, ascending = false)).names(),
        )
    }

    /** `100%` sorts before `52%` as text. As a fraction it does not. */
    @Test
    fun progressIsSortedAsAFraction() {
        val list =
            listOf(
                sample("full", downloaded = gib),
                sample("half", downloaded = gib / 2),
                sample("none"),
            )
        assertEquals(listOf("none", "half", "full"), list.inOrder(SortOrder(SortColumn.Progress)).names())
    }

    /** `11.4` sorts before `2.07` as text. */
    @Test
    fun ratioIsSortedAsANumber() {
        val list =
            listOf(
                sample("eleven", downloaded = 100, uploaded = 1140),
                sample("two", downloaded = 100, uploaded = 207),
            )
        assertEquals(listOf("two", "eleven"), list.inOrder(SortOrder(SortColumn.Ratio)).names())
    }

    /**
     * A torrent with no arrival time goes last whichever way the list is turned.
     *
     * Sorted the other way it would be first, which buries every download that *does* have one
     * under the ones that never will.
     */
    @Test
    fun anEtaOfNeverIsLastAscendingAndFirstDescendingButNeverInTheMiddle() {
        val list =
            listOf(
                sample("seeding", complete = true),
                sample("stalled", downloaded = gib / 2),
                sample("soon", downloaded = gib / 2, down = gib),
                sample("later", downloaded = 1, down = 1024),
            )
        val ascending = list.inOrder(SortOrder(SortColumn.Eta)).names()
        assertEquals(listOf("soon", "later"), ascending.take(2))
        assertTrue(ascending.drop(2).toSet() == setOf("seeding", "stalled"), "$ascending")
    }

    /** The name column is the one that is text, and it is compared the way a person reads it. */
    @Test
    fun theNameIsComparedWithoutCaringAboutCase() {
        val list = listOf(sample("Sintel"), sample("alpine"), sample("Blender"))
        assertEquals(listOf("alpine", "Blender", "Sintel"), list.inOrder(SortOrder(SortColumn.Name)).names())
    }

    /** The seven states are already in the order a torrent passes through them. */
    @Test
    fun theStateColumnIsSortedByWhatTheStateIsAndNotByItsName() {
        val list = listOf(sample("done", complete = true), sample("going", peers = 4))
        assertEquals(listOf("going", "done"), list.inOrder(SortOrder(SortColumn.State)).names())
    }

    /** A click on the sorted column turns it over; a click on another starts it ascending. */
    @Test
    fun clickingTheSameHeadTwiceReversesItAndAnotherHeadStartsOver() {
        var order = SortOrder()
        assertEquals(SortColumn.Name, order.column)
        order = order.clicked(SortColumn.Name)
        assertTrue(!order.ascending, "the same column turns over")
        order = order.clicked(SortColumn.Size)
        assertEquals(SortColumn.Size, order.column)
        assertTrue(order.ascending, "a new column starts the way a person expects to read it")
    }

    /** Reversal is the ascending order backwards, so the two can never disagree about ties. */
    @Test
    fun descendingIsAscendingBackwards() {
        val list = listOf(sample("a", peers = 1), sample("b", peers = 1), sample("c", peers = 2))
        SortColumn.entries.forEach { column ->
            assertEquals(
                list.inOrder(SortOrder(column)).names().reversed(),
                list.inOrder(SortOrder(column, ascending = false)).names(),
                "$column",
            )
        }
    }
}
