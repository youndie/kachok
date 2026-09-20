---
id: B-80
title: "The UI moves to commonMain"
status: wip
priority: P2
size: L
stage: phase-3-mobile
epic: feature-ui
blocked_by: [B-79]
---

> **This is what [B-40](B-40-wasmjs-ui-is-a-client-of-the-headless-engine.md) and
> [B-87](B-87-a-server-with-a-web-face.md) both wait on**, which was not known when either was
> filed. B-40's transport half is built and verified against a real
> browser; what is missing is a UI that can be compiled for one, and every screen is in `desktopMain`
> today. A phase-3 item is therefore blocking a phase-2 one — recorded here rather than resolved,
> because moving it is the owner's call and not a consequence of the work.

# B-80 — The UI moves to commonMain

The engine has been multiplatform since it was written: 42 files in `commonMain` against 16 in
`jvmMain`, and the module comment says why — later targets add `actual`s, not rewrites. The UI never
was. All 6875 lines of it sit in `desktopMain`, and [B-41](B-41-android-and-ios-targets.md) cannot
start until they do not.

Five files reach for the JVM, and they are the whole of the problem:

| File | What it uses | What it is |
|---|---|---|
| `App.kt` | `FileDialog`, `Toolkit.systemClipboard`, `DataFlavor`, `Runtime.totalMemory` | the shell |
| `session/ChooseDirectory.kt` | `FileDialog`, `JFileChooser`, `apple.awt.*` | already two implementations behind one function |
| `session/StoredPreferences.kt` | `java.util.Properties`, `Files`, `ATOMIC_MOVE` | where the settings file lives |
| `session/SettingsFrom.kt` | `java.nio.file.Path` | one call, to build a `RuntimeOptions` |
| `details/DetailsPanel.kt` | `java.awt.Cursor` | the resize cursor, one line |

- **The decision this needs.** How many `expect`s there are. `ChooseDirectory` is the shape to copy:
  one function, two implementations, and a comment saying that the *wrong* half is invisible —
  on Windows the macOS path opens a file chooser instead of failing. The candidates are choosing a
  directory, choosing a file, reading and writing the clipboard, where preferences are stored, and
  the resize cursor. Whether the drop target is one of them depends on whether Compose's
  `dragAndDropTarget` carries anything usable off the desktop.
- Rejected: a `commonMain` that keeps `java.nio.file.Path` through `kotlinx-io` or a typealias. The
  paths in question are *the platform's* — where a settings file goes on Android is not a path
  problem, it is a different question with a different answer.
- Rejected: moving the UI and leaving the shell behind. `App.kt` is where the platform actually is;
  a move that left it in `desktopMain` would move the easy 5800 lines and none of the work.
- Not covered: the Android and iOS targets themselves, which are B-41 — this is what has to be true
  before that item can begin. Also not covered: `viddik`, whose goldens are recorded on one
  rasteriser and would need an answer of their own for a second platform.

## The survey, re-taken 2026-09-20 — the surface has more than doubled

The table above was written when five files reached for the JVM. Today it is **twelve of the
forty-one**, and the difference is not drift: every one of the new ones arrived with an item that
had a reason.

| File | What it reaches for | Arrived with |
|---|---|---|
| `App.kt` | `FileDialog`, `Toolkit.systemClipboard`, `DataFlavor`, `Runtime.totalMemory` | the shell |
| `Preflight.kt` | `java.net.http`, `Files` | B-78, the only way to ask the shipped image anything |
| `session/ChooseDirectory.kt` | `FileDialog`, `JFileChooser`, `apple.awt.*` | already two implementations behind one function |
| `session/StoredPreferences.kt` | `Properties`, `Files`, `ATOMIC_MOVE` | where the settings file lives |
| `session/StoredTorrents.kt` | `Files`, `Path` | B-81, the list that survives a restart |
| `session/Autostart.kt` | a launch agent, a `Run` key | B-83, starting with the computer |
| `session/OpenFile.kt` | `java.awt.Desktop` | B-85, opening a file from the Files tab |
| `session/TrayMenuScale.kt` | AWT's own font scaling | B-91, a tray menu AWT draws itself |
| `session/WideArguments.kt` | `java.lang.foreign`, `Kernel32`, `Shell32` | B-116, the command line Windows really passed |
| `session/ClientModel.kt` | `Path`, the engine's dispatchers | B-79, the holder this move needed |
| `add/DroppedFiles.kt` | `Transferable`, `DataFlavor` | B-107, a `.torrent` dropped on the window |
| `details/DetailsPanel.kt` | `java.awt.Cursor` | the resize cursor, one line |

**And most of the new ones are not `expect`s.** A tray menu's font scaling, a Windows command line,
a launch agent and a `Run` key are desktop concerns that have no meaning on a phone: they do not
need a second implementation, they need to stay in `desktopMain` and not be called from common code.
The item's question — *how many `expect`s there are* — therefore has a smaller answer than this
table looks like, and a sharper one: **choosing a directory, choosing a file, the clipboard, where
preferences and the torrent list are stored, opening a file, and the resize cursor.** Six.

The rest is the shell, and the shell is what B-41 replaces on each platform rather than abstracts.

## What has to happen first

[B-79](B-79-the-windows-state-outlives-its-composition.md) is the cut this move needs. Separating
the state and the engine's lifetime from the file chooser, the clipboard, the drop target and the
heap reading is what leaves the rest of `App.kt` platform-free; done in the other order, the biggest
file moves last and drags four platform concerns with it.

## Iteration 1, 2026-09-20 — the screens are common; two seams, both found by tooling

**32 files and 6 132 lines now live in `commonMain`** against 11 files and 3 013 lines left in
`desktopMain`. The diff is 33 files for 22 insertions and 15 deletions: almost pure `git mv`, which
is what makes a move of this size reviewable at all. `./gradlew build` is green and `viddikVerify`
passes against the **unchanged** goldens — the third clause of the AC, and the one that says the
move changed nothing a person can see.

**Two `expect`s, and neither was in the item's table, because neither is a `java.*` import.**

* `variableFont` — the three families are loaded through `androidx.compose.ui.text.platform.Font`,
  which exists on the desktop and nowhere else. **The compiler found it** the moment the type scale
  moved.
* `horizontalResizeCursor` — `java.awt.Cursor`, one line, for the handle that widens the details
  panel. It compiled perfectly well in `commonMain` and **this repository's own lint caught it**:
  *"java. code in commonMain — it cannot compile for every target this module declares, and the
  target that finds out is whichever one compiles last."* A phone needs no cursor at all, which is
  what its `actual` will say.

A third thing moved without needing a seam: `UNNAMED_DROP`, the marker for a file whose name the
platform will not give before the drop. The screen that draws it is common; the AWT that produces it
is not.

## What the AC cannot mean any more, and why

* **The state holder cannot be common yet, and nothing in the UI can change that.** `ClientModel`
  drives a `TorrentSet`, and the engine's runtime is in *its* `jvmMain` — the engine is a
  multiplatform module with a single `jvm()` target. **The UI cannot be more common than the engine
  it drives**, so this clause of the AC is [B-41](B-41-android-and-ios-targets.md)'s to meet, not
  this item's.
* **"`desktopMain` holds only `actual`s and the entry point" is the wrong shape**, as the survey
  above already argued: a tray menu AWT draws itself, a launch agent, a `Run` key and a Windows
  command line are not implementations of a common idea. They are the desktop's own, and on another
  platform they are absent rather than different.

- AC: `ui/src/commonMain` holds the composables, the mapping functions and the state holder;
  `desktopMain` holds only `actual`s and the entry point; `./gradlew build` and the goldens are
  unchanged on desktop. **Two of three met.** The composables and the mapping functions are common
  and the build and goldens are untouched; the holder waits on the engine having a target to be
  common *for*, and the second clause is restated above rather than met.
- Anchors: `ui/build.gradle.kts`, `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/`,
  `engine/build.gradle.kts` (the comment that says why the engine was written this way).
