package ru.workinprogress.kachok.ui.session

import kotlin.test.Test

/**
 * The one branch of the tray-menu fix a machine without a display can answer
 * ([B-91](../../../../../../../../docs/backlog/B-91-the-tray-menu-is-not-hdpi.md)).
 *
 * **What this cannot test, and saying so is the point.** Whether an AWT `PopupMenu` honours a font
 * set on it needs a tray, a display and a pair of eyes; a headless JVM has none of the three, and
 * `SystemTray.isSupported()` is false there. So the fix itself is verified on a Windows machine by
 * a person, and what is nailed down here is that it *cannot go wrong on the way*: it returns
 * quietly on macOS and it returns quietly with no display, which are the two ways a cosmetic fix
 * for one platform can break the other two.
 */
class TrayMenuScaleTest {
    /** macOS scales the menu in the window server; doing it again here would double it. */
    @Test
    fun macOsIsLeftAlone() {
        scaleTrayMenu(os = "Mac OS X")
    }

    /**
     * And a machine with no display at all — which is every machine this suite runs on.
     *
     * Not a tautology: the function reaches for `SystemTray` and `GraphicsEnvironment`, both of
     * which throw `HeadlessException` rather than returning null, and an uncaught one here would
     * take the window down at start-up on a build machine.
     */
    @Test
    fun aHeadlessMachineIsLeftAloneRatherThanThrowing() {
        scaleTrayMenu(os = "Windows 11")
        scaleTrayMenu(os = "Linux")
    }
}
