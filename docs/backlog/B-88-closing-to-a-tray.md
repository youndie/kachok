---
id: B-88
title: "Closing the window leaves the client running, in a tray"
status: done
priority: P2
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: []
---

# B-88 — Closing the window leaves the client running, in a tray

Pressing the close button stops every torrent and exits. For a client whose whole job is to keep
transferring while somebody does something else, that is the wrong default and there is no other
one: no tray icon, no *minimise to tray*, no way to get a closed window back.

It is also the half [B-83](B-83-autostart-and-its-setting.md) had to work around. Starting with the
operating system means the window opens minimised, because "start the engine with no window" needs
somewhere for the window to come back from — and that somewhere is this item.

- **The decision this needs.** What the close button does. Three answers, and only the third needs a
  setting: always exit (today), always close to the tray, or a preference with a first-time prompt.
  Most clients take the second and let the tray's own menu quit; the trap in the third is a person
  who closes the window, sees nothing happen, and closes it again.
- **And whether the tray is Compose's or AWT's.** `androidx.compose.ui.window.Tray` exists inside
  `application {}` and draws a `Painter`; `java.awt.SystemTray` is the older road and is *not
  supported on every Linux desktop* — GNOME dropped the status icon protocol, and a client that
  vanishes into a tray that does not exist is a client somebody has to kill from a terminal.
  Whatever is chosen has to answer what happens when `SystemTray.isSupported()` is false.
- Rejected in advance: closing to the tray with no icon and no menu. A background process a person
  cannot see is one they find in a task manager a week later, wondering what it is.
- Not covered: a notification when a torrent finishes. That is its own decision about how loud this
  application is allowed to be.

- AC: closing the window leaves the transfers running and the client reachable from a tray icon;
  the icon's menu can show the window again and can quit; on a desktop with no tray the close button
  says what it will do instead of doing something invisible.

## Done

**Close leaves it running, with a setting and a notice — not a prompt.** Of the three answers the
item listed, "always exit" is the one that makes a torrent client something you have to leave open
to do its job, and "a preference with a first-time prompt" puts a dialog in front of somebody who
pressed *close*. What is here is the middle one plus the cheap half of the third: closing hides the
window, and **the first time** — once, remembered in the settings file — the tray says *kachok is
still running*. That is the answer to the failure the item named: press close, see nothing, press
again, conclude it is broken.

**Compose's `Tray`, not AWT's `SystemTray` directly**, because `isTraySupported` is the same
question and the composable is already inside `application {}` where the window lives.

**A desktop with no tray is the case that decides the shape.** GNOME dropped the status-icon
protocol, and a client that closes into a tray that is not there is one somebody has to kill from a
terminal. So `isTraySupported` is read once and everything asks it: with no tray the close button
stops the torrents exactly as before, the settings row says *this desktop has no tray, so the close
button stops the torrents*, and the row is **not clickable** — a switch that could be turned on
there would be a promise the close button does not keep. `Setting.enabled` is new for that, and it
is deliberately not `SettingKey.disabledBecause`: that one is the permanent answer — the listening
port is bound at start-up on every machine — and this one depends on where the application is
running.

**And it closes B-83's loop.** Autostart had to settle for *minimised* because "start the engine
with no window" needed somewhere for the window to come back from. With a tray it starts **hidden**,
and the settings row says which of the two it will do.

### A defect found on the way in

The toggles announced nothing. They are drawn `Box`es rather than `Switch`es, so a screen reader
read the label and stopped, and a test could press one but not read it — which is how the no-tray
case was going to be asserted. They carry `stateDescription` now, the way the column heads already carry
their sort order.

The settings golden is re-recorded: `STARTUP` has two rows, and the autostart note now says *starts
in the tray* where there is one.

**The tooltip carries the rates**, added the same day it was asked for: the point of closing to the
tray is that the client keeps working with no window, and a tooltip saying only its name asks a
person to open one to find out whether anything is happening — the single question the tray exists
to answer without opening it. Three short lines, from the status bar's own strings so the two cannot
disagree, capped at the 127 characters Windows truncates at silently.

Not covered, as filed: a notification when a torrent finishes. Found afterwards and filed as
[B-91](B-91-the-tray-menu-is-not-hdpi.md): the right-click menu is AWT's and is not scaled on a
HiDPI display.

**Automated:** `ui/src/desktopTest/.../settings/SettingsScreenTest.kt` — the row asks for the
change, and with no tray it says so and reads `off` rather than claiming to be on ·
`ui/src/desktopTest/.../session/StoredPreferencesTest.kt` for the two settings that persist ·
the `settings_screen` golden. Not automated: the tray icon itself, its menu, and the notification —
`java.awt.SystemTray` needs a desktop session, and a headless runner has none.
- Anchors: [`ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/App.kt`](../../ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/App.kt),
  [`ui/src/desktopMain/resources/icon/`](../../ui/src/desktopMain/resources/icon).
