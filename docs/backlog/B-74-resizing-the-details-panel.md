---
id: B-74
title: "Dragging the details panel's edge"
status: open
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

- AC: dragging the divider resizes the panel within 280–520 dp, the cursor changes over it, and the
  list reflows rather than clipping.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/details/DetailsPanel.kt`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/main/MainWindow.kt`.
