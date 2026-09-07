---
id: B-91
title: "The tray's right-click menu is not scaled on a HiDPI display"
status: open
priority: P2
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: []
---

# B-91 — The tray's right-click menu is not scaled on a HiDPI display

Reported from a real Windows machine on 2026-09-07: the menu that opens on a right click in the tray
is drawn at the wrong size on a scaled display, while everything else in the application is not.

## Where it comes from

Compose's `Tray` is `java.awt.TrayIcon` with a `java.awt.PopupMenu` — read in the toolkit's own
source, `Tray.desktop.kt`. That is a **heavyweight AWT menu**: it is not drawn by Compose, it does
not use the application's density, and none of the window's scaling reaches it. The icon beside it
*is* scaled — `toAwtImage(GlobalDensity, …, 16×16)` renders at sixteen points times the density, so
32 physical pixels at 200 % — which is why the icon looks right and the menu does not.

`PopupMenu` is also not exposed by Compose's `Tray`: it is created inside the composable and never
handed out, so there is nothing to set a font on without reaching into another library's internals
through `SystemTray.getSystemTray().trayIcons`.

- **The decision this needs.** Whether to keep AWT's menu. Two answers: set a scaled font on the
  menu AWT made — which means reaching around the toolkit's API for an object it deliberately keeps,
  and a fix that a Compose upgrade can silently undo — or stop using `Tray`'s menu and open a small
  undecorated Compose window at the pointer, which is drawn by the same renderer as everything else
  and scales because the rest of the application does.
- Rejected in advance: telling people to run at 100 %. The tray is where this client lives once the
  window is closed ([B-88](B-88-closing-to-a-tray.md)), and its menu is the only way to quit.
- Not covered: the notification balloon, which is also AWT's and also unscaled, and which nobody has
  complained about because it appears for a second.

**What is not yet known and has to be measured on the machine that has one:** whether the menu is
*blurry* — Windows bitmap-stretching a process that is not per-monitor DPI aware — or *small and
sharp*, which is AWT drawing at 100 % on a 200 % screen. Those are different faults with different
fixes, and the second is the one the design above assumes. A screenshot answers it.

- AC: the tray menu is legible at 150 % and 200 % on Windows, and the fix does not depend on a
  private field of a library this project does not own.
- Anchors: [`ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt`](../../ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt).
