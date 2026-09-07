package io.github.youndie.kachok.ui.session

import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.HeadlessException
import java.awt.SystemTray
import java.awt.TrayIcon

/**
 * The tray menu's font, scaled the way nothing else scales it.
 *
 * Compose's `Tray` is `java.awt.TrayIcon` with a `java.awt.PopupMenu` — a **heavyweight AWT menu**.
 * It is not drawn by Compose, it does not see the window's density, and on a display at 200 % it
 * comes out *small and sharp*: the process is DPI-aware, so Windows does not stretch it, and AWT
 * simply asks for a twelve-point font on a screen where everything else is twenty-four
 * ([B-91](../../../../../../../../docs/backlog/B-91-the-tray-menu-is-not-hdpi.md)).
 *
 * **Everything here is public JDK API.** `SystemTray.getTrayIcons` and `TrayIcon.getPopupMenu` are
 * documented methods on documented objects; nothing reflects into a private field. What it *is*
 * doing is reaching around Compose's `Tray`, which creates the menu and never hands it out — so if
 * a future Compose stops using `java.awt.TrayIcon`, this finds no icon and does nothing, and the
 * menu goes back to the size it is today. A silent no-op rather than a crash, which is the right
 * way round for a cosmetic fix.
 *
 * **Not on macOS**, where the window server scales the menu itself and this would double it.
 */
internal fun scaleTrayMenu(os: String = System.getProperty("os.name").orEmpty()) {
    if (os.startsWith("Mac")) return
    val scale = displayScale()
    // 1.0 is the ordinary case and 1.25 is a scale nobody would notice this at; below that the
    // rounding is worth more than the fix.
    if (scale < SMALLEST_WORTH_FIXING) return
    val tray =
        try {
            if (!SystemTray.isSupported()) return
            SystemTray.getSystemTray()
        } catch (noDisplay: HeadlessException) {
            return
        }

    // The icon is added by Compose inside its own effect, which may not have run yet. A listener
    // rather than a poll: `trayIcons` is a documented bound property, and waiting for an event is
    // the difference between a fix that works and one that works when the machine is slow enough.
    tray.trayIcons.forEach { it.scaleMenu(scale) }
    tray.addPropertyChangeListener("trayIcons") { event ->
        (event.newValue as? Array<*>)?.filterIsInstance<TrayIcon>()?.forEach { it.scaleMenu(scale) }
    }
}

private fun TrayIcon.scaleMenu(scale: Double) {
    val menu = popupMenu ?: return
    // Its own font, or the AWT default when it has none — a menu that inherits reports null, and
    // deriving from null is the failure this would otherwise have at exactly the moment it matters.
    val base = menu.font ?: Font(Font.DIALOG, Font.PLAIN, DEFAULT_POINTS)
    val wanted = base.deriveFont((base.size2D * scale).toFloat())
    if (menu.font?.size2D != wanted.size2D) {
        menu.font = wanted
        // The items carry their own font once one is set on them; setting the menu's is enough for
        // the ones that inherit, and these do — Compose adds plain `MenuItem`s.
        for (at in 0 until menu.itemCount) menu.getItem(at)?.font = wanted
    }
}

/**
 * What the primary screen is scaled by, which is where a tray lives.
 *
 * The transform and not a DPI reading: on Windows the JDK already reports a scaled transform for a
 * per-monitor-aware process, and that number is the one everything else in this application is
 * drawn at.
 */
private fun displayScale(): Double =
    try {
        GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .defaultScreenDevice
            .defaultConfiguration
            .defaultTransform
            .scaleX
    } catch (noDisplay: HeadlessException) {
        1.0
    }

private const val SMALLEST_WORTH_FIXING = 1.25
private const val DEFAULT_POINTS = 12
