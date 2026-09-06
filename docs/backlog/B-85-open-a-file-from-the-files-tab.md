---
id: B-85
title: "Double-clicking a file in the Files tab opens it"
status: done
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
- Anchors: [`ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/session/OpenFile.kt`](../../ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/session/OpenFile.kt),
  [`ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/details/DetailsPanel.kt`](../../ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/details/DetailsPanel.kt),
  [`engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/runtime/TorrentRuntime.kt`](../../engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/runtime/TorrentRuntime.kt).

## Done

**The decision: an unfinished file is refused, and the refusal says how far it got.** The three
candidates were opening it anyway, offering the folder instead, and refusing. Opening it hands a
player a file that stops in the middle, and this client cannot know which formats survive that — the
ones that do are what [B-65](B-65-sequential-download.md) is for, and somebody who has turned that
on knows something a double-click cannot express.

**Every branch answers with a sentence, and that is the shape of `openFile`.** It returns a string
or null rather than throwing or succeeding quietly: a skipped file, a half-fetched one, a session
with no desktop environment, a type nothing is registered for, a file that is no longer on the disk
— each is a thing the person who double-clicked can act on, and a gesture that does nothing at all
is indistinguishable from a window that is broken. The panel draws the answer under the list, only
while there is one.

**Completeness is decided on bytes, not on the printed percentage.** 99.6% prints as `100%`, and a
check on that number would open exactly the truncated file this refuses to hand over.

### A defect found on the way: *Save to* showed the wrong folder

Resolving a file's path needs the torrent's own directory, and the panel was being given
`preferences.directory` — the settings' default. Since [B-81](B-81-the-torrent-list-survives-a-restart.md)
a restored torrent keeps the folder it was added with, and the add dialog could always send one
elsewhere, so the *Overview* tab has been printing the wrong folder for every torrent that is not in
the default one. `TorrentRuntime` now exposes its `directory`, and both the field and the paths come
from the torrent rather than from the settings. The paths themselves come from `runtime.paths` — the
`FileSet`'s own answer — because a second implementation of a multi-file torrent's layout here would
be a second chance to open the wrong file.

**A deliberate addition to the design.** The design draws the *Files* tab without a foot note, and
this adds one — a single line, hairline above it, only after a double-click, in the same style as
the *Peers* tab's legend. The alternative is a gesture that refuses in silence, which the acceptance
criterion rules out in as many words.

Not covered, as filed: a context menu, *show in folder*, and opening the torrent's directory.

**Automated:** `ui/src/desktopTest/.../session/OpenFileTest.kt` — nine branches, with `open` and the
desktop probe as parameters so it runs on a machine with no display ·
`ui/src/desktopTest/.../details/FilesTabTest.kt`, which asserts the gesture reaches the handler at
all and that a single click does not: verified by removing the `combinedClickable`, at which point
two of its tests fail and the rest do not notice.
