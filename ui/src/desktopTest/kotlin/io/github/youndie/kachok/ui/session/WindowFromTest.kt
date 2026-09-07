package io.github.youndie.kachok.ui.session

import io.github.youndie.kachok.ui.list.TorrentRowModel
import io.github.youndie.kachok.ui.list.TorrentState
import io.github.youndie.kachok.ui.main.SortColumn
import io.github.youndie.kachok.ui.main.SortOrder
import io.github.youndie.kachok.ui.main.ToolbarCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The status bar's sentence, and the two places the window is allowed to disagree with itself.
 *
 * `windowOf` is where every number a person reads at the bottom of the screen is decided, and it is
 * the seam a filter crosses: the table draws what matched and the status bar counts everything.
 * Both of those have been wrong in this window, and each was found by looking rather than by a test.
 */
class WindowFromTest {
    private fun row(
        name: String = "payload.bin",
        state: TorrentState = TorrentState.Downloading,
        selected: Boolean = false,
    ) = TorrentRowModel(
        name = name,
        size = "96.0 MiB",
        progress = 0.5f,
        percent = "50%",
        down = "254",
        up = "0",
        connected = 1,
        unchoked = 1,
        outstanding = 3,
        ratio = "0.00",
        eta = "5m 43s",
        state = state,
        selected = selected,
    )

    private fun window(
        rows: List<TorrentRowModel>,
        all: List<TorrentRowModel> = rows,
        rates: Rates = Rates(),
        dhtNodes: Int? = null,
    ) = windowOf(
        rows = rows,
        rates = rates,
        listenPort = 6881,
        dhtNodes = dhtNodes,
        heapUsedBytes = 13L * 1024 * 1024,
        heapMaxBytes = 128L * 1024 * 1024,
        allRows = all,
    )

    @Test
    fun theStatusLineCountsWhatIsThereAndNamesTheStates() {
        val status =
            window(
                listOf(
                    row(state = TorrentState.Seeding),
                    row(state = TorrentState.Seeding),
                    row(state = TorrentState.Paused),
                    row(state = TorrentState.Downloading),
                ),
            ).status
        assertEquals("4 torrents, 2 seeding, 1 paused", status.torrents)
    }

    /** One is a torrent, not torrents. */
    @Test
    fun oneTorrentIsSingular() {
        assertEquals("1 torrent, 0 seeding, 0 paused", window(listOf(row())).status.torrents)
        assertEquals("0 torrents, 0 seeding, 0 paused", window(emptyList()).status.torrents)
    }

    /**
     * The status bar counts every torrent, and the table draws the ones that matched.
     *
     * This shipped the other way for the length of one live check: a filter matching nothing drew
     * *Nothing downloading* under a status bar saying *1 torrent*. The count comes from `allRows`
     * for that reason, and `hiddenByFilter` is the difference.
     */
    @Test
    fun aFilterNarrowsTheTableAndNotTheCount() {
        val all = listOf(row(name = "debian.iso"), row(name = "sintel.tar"), row(name = "alpine.iso"))
        val shown = all.take(1)
        val state = window(rows = shown, all = all)
        assertEquals("3 torrents, 0 seeding, 0 paused", state.status.torrents, "the bar counted the visible ones")
        assertEquals(1, state.torrents.size)
        assertEquals(2, state.hiddenByFilter)
    }

    @Test
    fun nothingIsHiddenWhenNothingIsFiltered() {
        val all = listOf(row(), row())
        assertEquals(0, window(rows = all, all = all).hiddenByFilter)
    }

    /**
     * Null is *not asked for*; zero is *asked for and nothing answered*.
     *
     * The status bar draws those differently, so they must not arrive as the same value — a client
     * that never opened a DHT socket saying "DHT 0 nodes" is a client claiming to have tried.
     */
    @Test
    fun theDhtSaysNothingWhenItWasNeverAskedFor() {
        assertNull(window(listOf(row()), dhtNodes = null).status.dht)
        assertEquals("DHT 0 nodes", window(listOf(row()), dhtNodes = 0).status.dht)
        assertEquals("DHT 214 nodes", window(listOf(row()), dhtNodes = 214).status.dht)
    }

    @Test
    fun thePortAndTheHeapAreTheOnesHandedIn() {
        val status = window(listOf(row())).status
        assertEquals("port 6881 listening", status.port)
        assertEquals("heap 13 / 128 MiB", status.heap)
    }

    @Test
    fun theRatesAreTheProcessesOwnAndAreLabelled() {
        val status = window(listOf(row()), rates = Rates(down = 24_988 * 1024, up = 812 * 1024)).status
        assertEquals("24 988 KiB/s", status.down)
        assertEquals("812 KiB/s", status.up)
    }

    /**
     * The toolbar is built from the selected row, so a window and its bar cannot disagree.
     *
     * Two places deciding "which torrent is this about" is one place for them to differ, which is
     * how *Pause* would end up enabled for a torrent that is already paused.
     */
    @Test
    fun theToolbarIsDecidedByTheRowThatIsSelected() {
        val running = window(listOf(row(selected = true))).toolbar
        assertEquals(ToolbarCommand.Pause, running.pause.command)
        assertNull(running.resume.command)

        val paused = window(listOf(row(state = TorrentState.Paused, selected = true))).toolbar
        assertEquals(ToolbarCommand.Resume, paused.resume.command)
        assertNull(paused.pause.command)

        val nothing = window(listOf(row())).toolbar
        assertNull(nothing.pause.command, "no row is selected, so there is nothing to pause")
        assertTrue(nothing.pause.disabledBecause!!.contains("no torrent selected"))
    }

    /** A degraded session gets a banner; a healthy one gets none. */
    @Test
    fun theBannerAppearsOnlyWhenSomethingIsDegraded() {
        assertNull(window(listOf(row())).degradedSummary)
        val degraded =
            windowOf(
                rows = listOf(row()),
                rates = Rates(),
                listenPort = 6881,
                dhtNodes = null,
                heapUsedBytes = 0,
                heapMaxBytes = 1,
                sessionError = "commands: the picker is already in use",
            )
        assertEquals("payload.bin is degraded.", degraded.degradedSummary)
        assertEquals("commands: the picker is already in use", degraded.degradedDetail)
    }

    /** With more than one torrent the banner cannot name which, and does not pretend to. */
    @Test
    fun theBannerNamesTheTorrentOnlyWhenThereIsOne() {
        val two =
            windowOf(
                rows = listOf(row(name = "a"), row(name = "b")),
                rates = Rates(),
                listenPort = 6881,
                dhtNodes = null,
                heapUsedBytes = 0,
                heapMaxBytes = 1,
                sessionError = "boom",
            )
        assertEquals("One session is degraded.", two.degradedSummary)
    }

    /** The header's sort order is whatever was handed in, and there is always one. */
    @Test
    fun theSortOrderSurvivesTheTripThroughTheWindow() {
        val sorted =
            windowOf(
                rows = listOf(row()),
                rates = Rates(),
                listenPort = 6881,
                dhtNodes = null,
                heapUsedBytes = 0,
                heapMaxBytes = 1,
                sort = SortOrder(SortColumn.Ratio, ascending = false),
            )
        assertEquals(SortColumn.Ratio, sorted.sort.column)
        assertEquals(false, sorted.sort.ascending)
    }
}
