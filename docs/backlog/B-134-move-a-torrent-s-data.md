---
id: B-134
title: "A torrent's data can be moved to another directory, keeping its progress"
status: done
priority: P3
size: L
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-133]
---

# B-134 — Move a torrent's data

Where a torrent saves was decided once, when it was added, and nothing changed it afterwards. The
only way to move one was to pause it, remove it without its data, move the folder and its
`<name>.<hash8>.resume` by hand, add it again with the new directory and set every file's tier
again — which needs the magnet or the `.torrent` at hand, and gives no warning when the target
already holds a copy of the same torrent: files are preallocated to their full length, so their
sizes say nothing about which copy is further along. Found doing exactly that through MCP, and
reported as [youndie/kachok#53](https://github.com/youndie/kachok/issues/53).

- **The decision and its reason.** `TorrentSet.move(runtime, directory)` is a restart somewhere
  else: stop — which flushes and writes the resume record — move every file and the record, open the
  torrent again at the new place with its tiers and order as they stand now. The record travels with
  the files, so the start-up check trusts it exactly as it does after a restart and hashes nothing it
  vouches for. No new persistence, no new verification path: the move reuses the two that already
  exist and are tested.
- **Refuse first, then act.** A target file that exists, a path another torrent in the set owns, or a
  volume with less room than the files take is refused with a sentence before the torrent is even
  stopped. Those are the cases where half a move is worse than none — the case that started this
  was a second copy already sitting at the target.
- **One file at a time with `Files.move`**, which is a rename on one volume and a copy and a delete
  across two. A failure part-way moves back what was moved and opens the torrent where it was, and
  says why; the caller gets a runtime to start either way. The folders the move emptied are removed,
  and only empty ones.
- **The row does not disappear.** The old runtime stays in `torrents`, stopped, until the new one
  replaces it, so a list drawn from the set keeps the torrent through a long cross-volume copy.
- **Surfaces.** `move_torrent(info_hash, directory)` over MCP, told to the `McpKeeper` so the window's
  list records the new directory ([B-133](B-133-sequential-over-mcp-and-the-wire.md)); `Request.Move`
  on the socket; and a folder button beside *Save to* in the details panel, which asks where and
  sends the move, with a refusal or failure drawn as a `MOVE` complaint in that torrent's *Overview*.
  The button is drawn only when the panel is given a handler, so the goldens — rendered with none —
  are the pictures they were.
- **The alternative that was rejected.** Moving inside the session, with the peers kept. The session
  owns open `FileChannel`s, the writer and the pool's buffers in flight; reopening those under a
  running session is a second lifecycle for the storage, where stop-and-open is the one that exists.
  The peers are lost and come back with the next announce, which is what a restart costs too.
- Found on the way: `runtime.paths + path` added the path's *name elements* to the list — a `Path`
  is an `Iterable<Path>` — so the record was silently left behind. `+ listOf(path)`.
- Not covered: a progress indicator for a long cross-volume copy. The row reads as stopped until
  the move ends; the copy is as fast as the disks and is not counted.
- Not covered: a menu entry on the torrent row. The folder button is on the panel the torrent is
  already selected in.

- AC: a torrent is moved and goes on from the new directory with its progress (no recheck from
  zero), its tiers and its order; a target that already holds its files is refused and nothing is
  touched; the move is reachable from the window, over MCP and over the socket. **Met.**
  **Automated:** `engine/src/jvmTest/.../runtime/TorrentSetMoveTest.kt` —
  `theFilesAndTheRecordMoveAndTheProgressComesWithThem`,
  `aTargetThatAlreadyHoldsTheFilesIsRefusedAndNothingIsTouched`, `aMoveToWhereItAlreadyIsIsRefused`;
  `control/src/test/.../mcp/McpServerTest.kt` — `aDownloadedTorrentIsMovedAndGoesOnFromThere`;
  `cli/src/test/.../serve/BackendTest.kt` — `aMoveSentOverTheSocketTakesTheFilesAndKeepsTheProgress`;
  `ui/src/desktopTest/.../details/MoveDataTest.kt` — `withNoHandlerThereIsNoButton`,
  `theFolderButtonBesideSaveToAsksTheCaller`, `aMoveThatFailedIsAComplaint`;
  `ui/src/desktopTest/.../session/StoredTorrentsTest.kt` — `whatAnAgentChangesIsInTheListOnTheNextStart`.
- Anchors: `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/runtime/TorrentSet.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/runtime/TorrentRuntime.kt`,
  `control/src/main/kotlin/io/github/youndie/kachok/control/mcp/McpServer.kt`,
  `wire/src/commonMain/kotlin/io/github/youndie/kachok/wire/Protocol.kt`,
  `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/details/DetailsPanel.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/session/ClientModel.kt`.
