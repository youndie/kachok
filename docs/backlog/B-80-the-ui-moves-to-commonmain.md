---
id: B-80
title: "The UI moves to commonMain"
status: open
priority: P2
size: L
stage: phase-3-mobile
epic: feature-ui
blocked_by: [B-79]
---

> **This is what [B-40](B-40-wasmjs-ui-is-a-client-of-the-headless-engine.md) waits on**, which was
> not known when either item was filed. B-40's transport half is built and verified against a real
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

## What has to happen first

[B-79](B-79-the-windows-state-outlives-its-composition.md) is the cut this move needs. Separating
the state and the engine's lifetime from the file chooser, the clipboard, the drop target and the
heap reading is what leaves the rest of `App.kt` platform-free; done in the other order, the biggest
file moves last and drags four platform concerns with it.

- AC: `ui/src/commonMain` holds the composables, the mapping functions and the state holder;
  `desktopMain` holds only `actual`s and the entry point; `./gradlew build` and the goldens are
  unchanged on desktop.
- Anchors: `ui/build.gradle.kts`, `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/`,
  `engine/build.gradle.kts` (the comment that says why the engine was written this way).
