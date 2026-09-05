---
id: B-58
title: "Removing a torrent, and the dialog the ellipsis promises"
status: open
priority: P2
size: S
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-54]
---

# B-58 — Removing a torrent, and the dialog the ellipsis promises

`TorrentSet.remove` exists and works: it stops the session and closes the files. What is missing is
the question a person has to be asked first — the design names the control *Remove…* with an
ellipsis and then does not draw the dialog behind it.

- **The decision this needs.** Whether removing offers to delete the data. The ellipsis says a
  dialog; a dialog with one button is a confirmation and a dialog with two is a choice, and the
  second one deletes a file the person may have spent an hour fetching.
- Rejected in advance: removing without asking. `TorrentSet.remove` alone is not destructive, but a
  control that sometimes deletes and sometimes does not, depending on a checkbox nobody drew, is
  the shape of every accidental deletion.
- Not covered: removing several at once, which needs multiple selection the list does not have.

- AC: *Remove…* is enabled, asks, and on yes the torrent leaves the list and its session stops
  cleanly; whether the data goes with it is whatever the dialog said.
- Anchors: `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/runtime/TorrentSet.kt`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/main/Toolbar.kt`.
