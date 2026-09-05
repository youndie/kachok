---
id: B-75
title: "The window below 800 dp"
status: open
priority: P3
size: S
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-49]
---

# B-75 — The window below 800 dp

The design's own note says the details panel folds away and the table drops its lesser columns on a
narrow window. The window has one layout: nine columns and a 340 dp panel at every width, so at
600 dp the columns overlap and the panel takes half the list.

- **The decision this needs.** Which columns go, and in what order. The design says *lesser*; the
  ones that are derived from others — ratio, ETA — are the candidates, and the ones a person sorts
  by are not.
- Rejected in advance: a horizontal scrollbar under the table. It keeps every column and makes the
  window useless at the width where it appears.
- Not covered: a minimum window size, which is a different decision from what happens above it.

- AC: below 800 dp the panel becomes an overlay rather than a column and the table drops columns in
  a stated order; a golden at 600 dp shows it.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/main/MainWindow.kt`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/list/TorrentTable.kt`.
