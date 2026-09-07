---
id: B-79
title: "The window's state outlives its composition"
status: open
priority: P2
size: L
stage: phase-3-mobile
epic: feature-ui
blocked_by: []
---

# B-79 — The window's state outlives its composition

`Client` is 510 lines holding twenty-nine `mutableStateOf` values, nine `LaunchedEffect`s, the
engine's whole lifetime — `EngineDispatchers`, `TorrentSet`, the one-second sampling loop, the
command channel — and the calls into the platform: the file chooser, the clipboard, the drop
target, `Runtime.totalMemory`. All of it lives in the composition.

On desktop that is correct and costs nothing: a window's lifetime *is* the process's, so `remember`
is exactly the right lifetime and a holder would be ceremony. On Android it is not: a configuration
change and process death destroy the composition, and with it the selected row, the open panel, the
filter, the sort, and the `TorrentSet` itself.

- **The decision this needs.** What the holder is. `androidx.lifecycle:lifecycle-viewmodel` 2.11.0
  is already on the classpath transitively through Compose and is multiplatform, so `ViewModel` is
  available without a new dependency; a plain class held by `remember` is the alternative and is
  the same thing without the lifecycle that is the entire point.
- **And what does *not* move.** The mapping layer stays as it is. `windowOf`, `rowOf`, `detailsOf`,
  `settingsOf`, `addFrom`, `inOrder`, `matches`, `ratesOf` are about 1200 lines of pure functions
  carrying most of the window's logic, and they are pure *on purpose*: they are tested without a
  holder, a dispatcher or a lifecycle, and most of the 209 UI tests rest on that. Turning them into
  methods would buy nothing and cost it.
- Rejected: a `UseCase` layer between the holder and the engine. The engine already has an explicit
  command interface — `Command.Pause`, `Resume`, `Recheck`, `Announce`, `Reconfigure`,
  `TorrentSet.add`/`remove` — so a `PauseTorrentUseCase` wrapping `runtime.pause()` would carry no
  decision. Where a decision does exist it already has a home that is neither the holder nor the
  engine: `refusedIfOccupied`, `unwantedPieces`, `NarrowTable.columnsFor`,
  `TorrentSet.collisionWith`.
- Not covered: which of the twenty-nine values is worth *restoring* rather than merely surviving.
  A panel that was open should reopen; a half-typed filter probably should not.

## Why this is worth doing before [B-80](B-80-the-ui-moves-to-commonmain.md)

The recommendation this item came from said the opposite — move the UI first, extract the holder as
part of it. Reading the five files that touch `java.*` says otherwise: `App.kt` holds nearly all of
them, and separating the state and the engine's lifetime from the file chooser, the clipboard, the
drop target and the heap reading **is** most of the work of making the rest common. The holder is
the cut; the move is what the cut makes possible.

## The present-day argument, which is smaller but real

Nothing in `Client` can be tested except end to end, against a real swarm and real files. Covering
it this session meant making `refusedIfOccupied`, `deleteQuietly`, `shortcutFor` and `Pending`
`internal` one at a time; everything still inside the composable — the command channel, the
selection, the settings effect, the shortcut effect — is reachable only by running a download.

- AC: the selected row, the open panel, the filter, the sort and the running `TorrentSet` survive
  the composition being thrown away and rebuilt; `Client` holds no engine state of its own; the
  mapping functions are untouched and their tests do not change.
- Anchors: `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/App.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/session/`.
