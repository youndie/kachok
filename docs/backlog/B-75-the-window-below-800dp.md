---
id: B-75
title: "The window below 800 dp"
status: done
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

## The decision, taken

**The order columns go in is derived-first.** ETA and RATIO are arithmetic on other columns, so a
reader who loses them can still work them out; PEERS · OUT and UP KIB/S are the swarm's side of the
download rather than the download's; SIZE goes last of the five, being neither derivable nor about
the swarm.

**Four are never given up at any width** — NAME, PROGRESS, DOWN KIB/S, STATE. Which torrent, how
far, how fast, whether anything is wrong. A table without them is a list of names with a scrollbar,
and a test walks every width from 200 to 1400 dp asserting they survive.

**The row is told which columns to draw, and does not measure.** Every row and the header have to
agree, and nine independent measurements of one width is nine chances to disagree by a pixel.

## Deviations, and why

- **The design says the panel *folds away*; it becomes an overlay instead.** Hiding it would make
  the details unreachable at exactly the width where the window is most crowded, and the toolbar's
  toggle already closes it in one press. It is right-aligned and full height, so the gesture that
  opens and closes it is the same at every width.
- **The toolbar's filter field shrinks to 110 dp below 800.** Not in the item and found by the
  golden: at 600 dp the bar's fixed contents — the button, eight controls and a 220 dp field — come
  to more than the window, and the last glyph was drawn half off the edge. The field is the only
  thing on that bar that can be smaller without becoming a different control.
- **`WiringTest.everyColumnHeadLeavesTheWindow` had to lose its details panel.** The harness's
  surface is 1024 dp and cannot be widened past it — a `requiredSize` overflow puts the clicks
  outside the root's bounds — so with the panel open, RATIO and ETA are correctly not drawn. The
  test is about the heads being wired, not about which of them fit.

- AC: below 800 dp the panel becomes an overlay rather than a column and the table drops columns in
  a stated order; a golden at 600 dp shows it.
  **Automated:** `ui/src/desktopTest/.../list/NarrowTableTest.kt` — the order, that a column never
  comes back while narrowing, that the four survive every width from 200 to 1400 dp — and the
  golden `main_narrow.png`.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/main/MainWindow.kt`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/list/TorrentTable.kt`.
