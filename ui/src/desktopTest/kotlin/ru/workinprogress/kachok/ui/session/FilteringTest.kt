package ru.workinprogress.kachok.ui.session

import ru.workinprogress.kachok.ui.list.TorrentRowModel
import ru.workinprogress.kachok.ui.list.TorrentState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The one rule the filter field applies, and the things it deliberately does not match. */
class FilteringTest {
    private fun row(
        name: String,
        state: TorrentState = TorrentState.Downloading,
    ) = TorrentRowModel(
        name = name,
        size = "3.70 GiB",
        progress = 0.5f,
        percent = "50%",
        down = "1 204",
        up = "0",
        connected = 24,
        unchoked = 4,
        outstanding = 61,
        ratio = "0.14",
        eta = "1h 04m",
        state = state,
    )

    private val list =
        listOf(
            row("debian-13.1.0-amd64-DVD-1.iso"),
            row("Sintel-2010-1080p-DCP.tar", TorrentState.Seeding),
            row("alpine-standard-3.20.iso", TorrentState.Paused),
        )

    @Test
    fun anEmptyFilterKeepsEverything() {
        assertEquals(list, list.filter { it.matches("") })
        assertEquals(list, list.filter { it.matches("   ") }, "a field holding spaces is an empty field")
    }

    @Test
    fun aNameIsMatchedAnywhereInItAndWithoutCase() {
        assertEquals(listOf("debian-13.1.0-amd64-DVD-1.iso"), list.filter { it.matches("DEBIAN") }.map { it.name })
        assertEquals(2, list.filter { it.matches("iso") }.size, "the extension is in the middle of neither")
    }

    /**
     * And the state, which is the question a name cannot answer.
     *
     * *What is paused* is not a substring of anything a person named their torrents, and it is the
     * second thing anybody asks of a list of sixteen.
     */
    @Test
    fun theStateIsMatchedToo() {
        assertEquals(listOf("alpine-standard-3.20.iso"), list.filter { it.matches("paus") }.map { it.name })
        assertEquals(listOf("Sintel-2010-1080p-DCP.tar"), list.filter { it.matches("Seeding") }.map { it.name })
    }

    /** Numbers wearing units are not matched, because `1` would match all three of them. */
    @Test
    fun theFiguresAreNotMatched() {
        assertTrue(list.none { it.matches("3.70 GiB") }, "the size cell was matched")
        assertTrue(list.none { it.matches("0.14") }, "the ratio cell was matched")
        assertTrue(list.none { it.matches("1h 04m") }, "the ETA cell was matched")
    }

    /** A filter that matches nothing matches nothing, rather than falling back to everything. */
    @Test
    fun aFilterThatMatchesNothingLeavesAnEmptyList() {
        assertEquals(emptyList(), list.filter { it.matches("ubuntu") })
    }
}
