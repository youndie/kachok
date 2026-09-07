package ru.workinprogress.kachok.ui.session

import ru.workinprogress.kachok.ui.main.SessionStatus

/**
 * What the tray icon says when somebody hovers over it.
 *
 * The whole point of closing to the tray is that the client keeps working with no window; a tooltip
 * that says only its name asks a person to open the window to find out whether anything is
 * happening, which is the one question the tray exists to answer without opening it
 * ([B-88](../../../../../../../../docs/backlog/B-88-closing-to-a-tray.md)).
 *
 * **Newlines, and they are not decoration.** A Windows tray tooltip is 128 characters and wraps at
 * them; three short lines are read at a glance and one long one is a sentence somebody has to parse
 * while holding a mouse still.
 *
 * The rates come straight from the status bar's own figures, so the two never disagree — a tooltip
 * that computed its own would be a second implementation of a number that already exists.
 */
internal fun trayTooltip(status: SessionStatus?): String {
    if (status == null) return NAME
    return buildString {
        append(NAME)
        appendLine()
        append("↓ ").append(status.down).append("   ↑ ").append(status.up)
        appendLine()
        append(status.torrents)
    }.take(LIMIT)
}

/** Windows truncates a tray tooltip past this, silently. macOS and Linux are more generous. */
private const val LIMIT = 127
private const val NAME = "kachok"
