---
id: B-115
title: "The window draws its title bar and nothing else: the start-up check reads the disk on the AWT event thread"
status: done
priority: P0
size: S
stage: phase-2-ui
epic: feature-ui
---

# B-115 — The window draws its title bar and nothing else: the start-up check reads the disk on the AWT event thread

Reported from Windows: the application opens, the `AppFrame` title bar is there, and everything
below it is an empty rectangle. Nothing is written to `startup-error.txt`, nothing is on stderr,
and the same build on macOS is fine — which reads like a rendering fault, and is why the first
guess was the remote desktop the machine is used through.

It is not the renderer. Skia drew the title bar; it would have drawn the rest.

**What the window is doing instead.** A thread dump taken while the window was blank, from the
machine that has the defect:

```
"AWT-EventQueue-0" #46 prio=6 cpu=3046.88ms elapsed=24.86s runnable
	at sun.nio.ch.FileDispatcherImpl.pread0(java.base@25.0.1/Native Method)
	…
	at io.github.youndie.kachok.engine.storage.FileSet.readSpan(FileSet.kt:80)
	at io.github.youndie.kachok.engine.storage.FileStorage.readPiece(FileStorage.kt:137)
	at io.github.youndie.kachok.engine.resume.StartupVerifier.hashMatches(StartupVerifier.kt:55)
	at io.github.youndie.kachok.engine.resume.StartupVerifier.verify(StartupVerifier.kt:45)
	…
	at androidx.compose.ui.platform.FlushCoroutineDispatcher.performRun(FlushCoroutineDispatcher.skiko.kt:111)
	at java.awt.EventQueue.dispatchEventImpl(java.desktop@25.0.1/EventQueue.java:723)
```

The start-up check — *what is already on the disk, before a peer is dialled* — is reading and
hashing the torrents **on the thread that draws the window**. `App.kt`'s engine effect awaits
`open(...)` for every remembered torrent in turn, and a `LaunchedEffect` runs on the composition's
dispatcher, which on the desktop is the AWT event thread. `TorrentRuntime.restore` is a suspend
function, but the reads inside it are blocking and it never leaves the caller's thread, so the
composition cannot run: no content, no clicks, no repaint — for as long as the check takes.

**Why Windows and not macOS.** Nothing platform-specific: that machine's four remembered torrents
point at 156 GB and 402 GB of files, and any piece a resume record does not vouch for is read back
and hashed. The Mac's list is short. The same build freezes on either machine given the same list,
and the same window freezes when somebody *adds* a large torrent that is already on disk — the add
path awaits the same check on the same thread.

- **The decision and its reason.** Two lines, one in each half:
  - `TorrentRuntime.restore` runs its body on the engine's own I/O dispatcher. A suspend function
    that reads a hundred gigabytes on whatever thread called it is a suspend function in its
    signature only, and the engine's own rule already says nothing that blocks may run on a
    dispatcher that is not for blocking.
  - The window *starts* the remembered torrents rather than awaiting them, so the sampling loop —
    and with it the first frame — happens at once. The rows then appear as the torrents open, each
    showing its own check, which is what `StartupVerifier`'s progress callback was always for.
- Rejected: drawing a "checking" screen while the effect blocks. The thread is held; nothing can
  draw anything.
- Rejected: skipping the check when a resume record exists. The record is an optimisation and
  never the source of truth (that is `StartupVerifier`'s whole comment), and a file the record
  vouches for can still have been changed under it.
- Not covered: `Client` draws nothing until the first sample arrives — with the fix that is one
  tick (300 ms) rather than minutes, and an empty window for 300 ms is not what anybody reported.
- Not covered: the rest of `open` — creating the `FileSet` — which is still on the composition's
  thread and is milliseconds, not minutes.

- AC: with the window's torrents on a machine that has this defect, the toolbar, the header and the
  first rows are drawn within seconds of start instead of after the whole check; a test gives the
  caller one thread, starts the check on it, takes that thread away, and shows the check still
  gets through the file.
- Anchors: `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/App.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/runtime/TorrentRuntime.kt`,
  `engine/src/jvmTest/kotlin/io/github/youndie/kachok/engine/runtime/RestoreLeavesItsCallerAloneTest.kt`.

## Iteration 1 — 2026-09-18: fixed, and photographed on the machine that had it

`TorrentRuntime.restore` is `withContext(io) { session.restore(hasher) }`; the window's effect
launches the remembered torrents on the engine's scope instead of awaiting them.

`RestoreLeavesItsCallerAloneTest` states the property without measuring how long anything takes:
one thread for the caller, the check started on it, and then that thread taken away by a sleeper
queued behind it. It waits on the *session's* count of verified pieces rather than on the calling
coroutine, because that coroutine ends by resuming on the held thread either way — a test that
waited for it could not tell a fixed client from a broken one. Reverted to
`session.restore(hasher)`, the test fails; with the fix it passes.

**On the machine that reported it**, the installed app image's jars with this branch's `ui` and
`engine` jars in front of them, same session, same options as the packaged launcher:

| | before | after |
|---|---|---|
| after 25 s | title bar, empty rectangle below | toolbar, column header, first torrent's row at 92 % |
| stderr | nothing | nothing |
| AWT thread | in `pread0` under `StartupVerifier` | idle |

The screenshots are the acceptance: the window that drew nothing now draws its list while the
remaining torrents are still being checked.
