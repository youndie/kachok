package ru.workinprogress.kachok.ui.session

import ru.workinprogress.kachok.ui.main.SessionStatus
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the tray says without the window being opened
 * ([B-88](../../../../../../../../docs/backlog/B-88-closing-to-a-tray.md)).
 *
 * The whole point of closing to the tray is that the client keeps working with no window, and a
 * tooltip that says only its name asks a person to open one to find out whether anything is
 * happening — the single question the tray exists to answer without opening it.
 */
class TrayTooltipTest {
    private val status =
        SessionStatus(
            down = "24 988 KiB/s",
            up = "812 KiB/s",
            torrents = "16 torrents, 7 seeding, 2 paused",
            dht = "DHT 214 nodes",
            port = "port 6881 listening",
            heap = "heap 41 / 64 MiB",
        )

    @Test
    fun theTooltipCarriesBothRatesAndTheCount() {
        val tooltip = trayTooltip(status)
        assertContains(tooltip, "24 988 KiB/s")
        assertContains(tooltip, "812 KiB/s")
        assertContains(tooltip, "16 torrents, 7 seeding, 2 paused")
        assertTrue(tooltip.startsWith("kachok"), "the tooltip does not say whose it is: $tooltip")
    }

    /** Three short lines are read at a glance; one long one is parsed while holding a mouse still. */
    @Test
    fun theTooltipIsThreeLines() {
        assertEquals(3, trayTooltip(status).lines().size)
    }

    /**
     * Windows truncates a tray tooltip past 127 characters, and does it silently.
     *
     * The strings here come from the engine and grow with the number of torrents, so the one that
     * would be cut is the one belonging to somebody with a lot of them.
     */
    @Test
    fun aTooltipIsNeverLongerThanWindowsWillShow() {
        val long =
            SessionStatus(
                down = "1 234 567 KiB/s",
                up = "7 654 321 KiB/s",
                torrents = "128 torrents, 64 seeding, 32 paused, 4 checking, 2 degraded and more",
                dht = "DHT 214 nodes",
                port = "port 6881 listening",
                heap = "heap 41 / 64 MiB",
            )
        assertTrue(trayTooltip(long).length <= 127, "the tooltip is ${trayTooltip(long).length} characters")
    }

    /** Before the first tick there are no figures, and a name is better than three empty lines. */
    @Test
    fun withNoFiguresYetItIsJustTheName() {
        assertEquals("kachok", trayTooltip(null))
    }
}
