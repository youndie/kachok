---
id: B-85
title: "Double-clicking a file in the Files tab opens it"
status: open
priority: P3
size: S
stage: phase-2-ui
epic: feature-ui
blocked_by: []
---

# B-85 — Double-clicking a file in the Files tab opens it

The *Files* tab lists every file with its size and its share ([B-67](B-67-per-file-selection.md))
and none of the rows respond. The obvious gesture on a finished file is to open it, and the obvious
one on any row is to show it in the file manager.

**One call does the first part on all three platforms**: `java.awt.Desktop.getDesktop().open(file)`
hands the path to the OS handler — `LaunchServices` on macOS, `ShellExecute` on Windows,
`xdg-open` on Linux. It is the same class the drop target already comes from, so nothing new is
pulled in on desktop. Two things it does *not* do: `Desktop.isDesktopSupported()` is false on a
Linux session without a desktop environment, and `Desktop.Action.BROWSE_FILE_DIR` — "show it in the
folder" — exists only on some platforms and has to be asked for before it is offered.

- **The decision this needs.** What a double-click on an *unfinished* file does. Opening a video
  that is 40% fetched hands a player a truncated file, and the row already knows the percentage —
  so the choice is between refusing with a reason, offering "show in folder" instead, or opening it
  anyway because some formats are watchable from the front and
  [B-65](B-65-sequential-download.md) exists for exactly that.
- Rejected in advance: opening the *torrent's* directory on a single-file torrent. `FileSet`
  already treats the two shapes differently (BEP 3's `name` is the file in one case and the
  directory in the other), and a menu item that means different things in the two cases is worse
  than two items.
- Not covered: a context menu. This item is one gesture; the menu that would hold "show in folder",
  "copy path" and the rest is a screen the design does not draw.

- AC: double-clicking a completed file opens it in whatever the system uses for that type; a file
  that is not finished does not silently hand a truncated one to a player; a session with no desktop
  environment says why rather than doing nothing.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/details/DetailsPanel.kt`,
  `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/runtime/TorrentRuntime.kt` (`paths`).
