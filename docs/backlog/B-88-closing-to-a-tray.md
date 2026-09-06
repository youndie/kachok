---
id: B-88
title: "Closing the window leaves the client running, in a tray"
status: open
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
- Anchors: [`ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt`](../../ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt),
  [`ui/src/desktopMain/resources/icon/`](../../ui/src/desktopMain/resources/icon).
