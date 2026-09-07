---
id: B-91
title: "The tray's right-click menu is not scaled on a HiDPI display"
status: done
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

**Answered by the person who has one, 2026-09-07: small and sharp.** That settles which of the two
faults it is. The process *is* per-monitor DPI aware — Windows is not stretching anything, which is
why it is sharp — and AWT is asking for a twelve-point font on a screen where everything else is
drawn at twenty-four. Blurry would have meant the manifest and a different item entirely.

- AC: the tray menu is legible at 150 % and 200 % on Windows, and the fix does not depend on a
  private field of a library this project does not own.
- Anchors: [`ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/session/TrayMenuScale.kt`](../../ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/session/TrayMenuScale.kt),
  [`ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt`](../../ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt).

## Done — the font, not a new window

Since the fault is a font size and not a stretched bitmap, the smaller of the two answers is the
right one: derive the menu's font from the primary screen's transform — the same number everything
else in the window is drawn at — and set it on the menu and its items.

**Everything it touches is public JDK API.** `SystemTray.getTrayIcons` and `TrayIcon.getPopupMenu`
are documented methods on documented objects; nothing reflects into a private field, which was the
line this item drew. What it *does* do is reach around Compose's `Tray`, which creates the menu and
never hands it out — so a future Compose that stops using `java.awt.TrayIcon` will find no icon here
and do nothing, and the menu returns to the size it has today. A silent no-op rather than a crash is
the right way round for something cosmetic.

The icon is added inside Compose's own effect, which may not have run when this does, so it listens
on `trayIcons` — a documented bound property — instead of polling. Waiting for an event rather than
for a while is the difference between a fix that works and one that works when the machine is slow.

Not on macOS, where the window server scales the menu itself and this would double it. Below 125 %
it does nothing: the rounding would be worth more than the fix.

**What could not be verified here, and it is the fix itself.** Whether an AWT `PopupMenu` honours a
font set on it needs a tray, a display and a pair of eyes, and a build machine has none of the
three. So the tests nail down the two ways this could break the *other* platforms — it returns
quietly on macOS and it returns quietly with no display, where `SystemTray` and `GraphicsEnvironment`
throw rather than returning null — and the fix is confirmed by somebody looking at a scaled screen.
If it turns out AWT ignores the font, the fallback is the one this item rejected as the larger
answer: build the tray icon by hand and open a Compose window at the pointer.

**Automated:** `ui/src/desktopTest/.../session/TrayMenuScaleTest.kt`, for the two quiet returns.
