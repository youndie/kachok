---
id: B-74
title: "Dragging the details panel's edge"
status: done
priority: P3
size: S
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-49]
---

# B-74 — Dragging the details panel's edge

`Details` declares a 280–520 dp range and draws at 340. Nothing moves it: the hairline between the
list and the panel is a `Box` with a background.

- **The decision this needs.** Whether the width outlives the session, which makes it a setting
  rather than a piece of window state ([B-71](B-71-settings-that-survive-a-restart.md)).
- Rejected in advance: a `SplitPane`. It brings a Swing interop layer into a window that has none,
  for a drag that is a `draggable` and a clamp.
- Not covered: hiding the panel by dragging it to zero — the toolbar's toggle is how it closes.

## The decision, taken

**The width outlives the session, so it is a setting.** A panel somebody widened once and finds back
at 340 every launch is a panel they widen every launch, and
[B-71](B-71-settings-that-survive-a-restart.md) had already built the file to put it in. It is not
*on* the settings screen: the way to set it is to drag it.

**The clamp lives in `withDetailsWidth`, not at the drag.** A hand-edited file cannot then ask for a
panel the window cannot draw either.

**The hairline is the handle.** A separate grab strip would be either invisible or a second line the
design does not draw; the pointer's reach widens instead of the line, so what is drawn is the
design's one pixel.

- AC: dragging the divider resizes the panel within 280–520 dp, the cursor changes over it, and the
  list reflows rather than clipping.
  **Automated:** `ui/src/desktopTest/.../session/StoredPreferencesTest.kt` —
  `theDetailsPanelWidthComesBack`, `aWidthOutsideTheDesignsRangeIsClamped`. Checked by hand:
  dragging the divider left widened the panel to its 520 dp bound, the table reflowed rather than
  clipping, and `detailsWidth=479.875` was in the settings file two seconds later.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/details/DetailsPanel.kt`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/main/MainWindow.kt`.
