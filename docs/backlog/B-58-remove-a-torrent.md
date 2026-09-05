---
id: B-58
title: "Removing a torrent, and the dialog the ellipsis promises"
status: done
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

## The decision, taken

**A confirmation with a checkbox, not two buttons.** *Remove* and *Remove and delete* side by side
would put the irreversible one a mis-click from the safe one; a box that is off until somebody ticks
it makes deleting a decision rather than an aim. The primary button then changes its wording and its
colour with the box — a button that says *Remove* in the accent and deletes 3 GiB is the shape of
every accidental deletion — and the dialog adds *This cannot be undone* only when it is true.

The checkbox names the directory under it, because "delete the data" is unanswerable without
knowing which data and where.

## Deviations, and why

- **The design has no reference for this dialog**, which is the item's own premise. It is built in
  the design's language — the add dialog's frame, card, buttons and 6 dp radius — and the golden
  `remove_dialog.png` draws both answers to the checkbox one above the other, since the difference
  between them is the whole point and it is a difference in wording and colour.
- **`DialogButton` moved out of `AddTorrent.kt` into `theme/`.** Two dialogs whose buttons are two
  private functions are two dialogs that drift, and the drift is invisible because they are never on
  screen together. `destructive` is a third appearance rather than a colour argument, so a caller
  cannot forget it.
- **The paths come from the engine.** `TorrentRuntime.paths` exposes what the `FileSet` actually
  opened. Reconstructing a multi-file torrent's layout in the window would be a second
  implementation of it, and a second chance to delete the wrong thing. They are read *before*
  `set.remove`, which closes the files.
- **An empty directory goes; a non-empty one stays.** Deleting a directory that still holds
  something this torrent did not write would take somebody else's files with it.

- AC: *Remove…* is enabled, asks, and on yes the torrent leaves the list and its session stops
  cleanly; whether the data goes with it is whatever the dialog said.
  **Automated:** `ui/src/desktopTest/.../remove/RemoveTorrentTest.kt`,
  `ui/src/desktopTest/.../DeleteQuietlyTest.kt` — which checks the two cases where it must *not*
  delete, against real files — `WiringTest.theRemoveDialogsAnswersLeaveTheWindow`, and the golden
  `remove_dialog.png`. Checked by hand as well: removing with the box unticked left
  `~/Downloads/payload.bin` where it was.
- Anchors: `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/runtime/TorrentSet.kt`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/main/Toolbar.kt`.
