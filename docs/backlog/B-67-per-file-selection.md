---
id: B-67
title: "Per-file progress and choosing which files to fetch"
status: open
priority: P2
size: L
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-54]
---

# B-67 — Per-file progress and choosing which files to fetch

Two screens are waiting on the same engine change. The details panel's *Files* tab says so in
words; the add dialog draws the file list with checkboxes that do not respond and a `planned` badge
over them.

- **The decision this needs.** What an unwanted file does to the piece picker. A torrent's pieces
  do not respect file boundaries: the piece that straddles a wanted and an unwanted file has to be
  fetched anyway, and the design's own note says the panel must say so rather than showing a total
  that never completes.
- Rejected in advance: deleting the unwanted files after downloading them. It is simpler, it is what
  some clients do, and it spends the bandwidth the setting exists to save.
- Not covered: changing the selection while a torrent runs, which needs the picker to give back
  pieces it has already started.

- AC: a file unticked in the add dialog is not requested except where its pieces straddle a wanted
  one; the *Files* tab lists every file with its own progress and says which are skipped.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/picker/PiecePicker.kt`,
  `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/storage/PieceLayout.kt`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/details/DetailsPanel.kt`.
