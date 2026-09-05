---
id: B-40
title: "Phase 2: the wasmJs UI is a client of the JVM headless engine"
status: open
priority: P3
size: L
stage: phase-2-ui
blocked_by: [B-39]
---

# B-40 — Phase 2: the wasmJs UI is a client of the JVM headless engine

A browser has no TCP or UDP sockets, so a wasmJs build of the *engine* cannot exist (research
Risk 4). This item was opened as a question — remote engine or WebRTC peers — and answered by the
owner on 2026-09-05: **the JVM headless client is the backend; the browser build of the Compose UI
is its client.** The desktop build runs the engine in-process; the browser build talks to the same
engine over a socket.

- **The decision and its reason.** One engine, two front ends, one UI codebase: the UI reads a
  session state and sends commands, and whether those cross a process boundary is a transport
  detail. WebRTC peers would have been a different protocol with a different swarm, a second
  engine to maintain, and no access to the user's disk.
- Rejected: a browser-side engine over WebRTC (WebTorrent-compatible). It downloads from a
  different, smaller swarm, cannot write files, and duplicates everything phase 1 builds.
- What phase 1 owes this item: the engine's `StateFlow<SessionState>` and `Channel<Command>`
  ([B-17](B-17-session-orchestrator.md)) are a wire contract in waiting — plain data, no platform
  types, no callbacks — so that putting them behind a socket is serialisation, not redesign. The
  CLI ([B-18](B-18-cli-download-command.md)) is the process that grows into the backend, not a
  throwaway.
- Not covered until phase 2: the transport (a local WebSocket is the obvious candidate), the
  serialisation (kotlinx.serialization is in the shared catalog), authentication for a socket
  that is not only local, and how the browser build is served.

## What the desktop stage settled, and what is still a question (2026-09-05)

The desktop half closed — [B-46](B-46-ui-theme-and-calibration.md) through
[B-55](B-55-magnets-in-the-window.md) — and three of this item's assumptions are now facts rather
than intentions:

* **The wire contract held.** `SessionState` is still plain data with no platform types, no
  callbacks and no connections in it, and the desktop window reads nothing else. Putting it behind
  a socket is a serialisation task, which is what phase 1 was asked to leave possible.
* **The UI's own layer is transport-agnostic already.** `rowOf`, `detailsOf`, `statusOf`,
  `settingsOf` and `addFrom` are pure functions of a `SessionState`, a `Metainfo` or a
  `MagnetLink`, with no engine types beyond those and no suspension. A browser build that received
  those three over a socket would call the same functions unchanged; what is desktop-only is
  `App.kt`, `TorrentSet` and the file chooser.
* **The backend process exists.** `TorrentSet` is what would hold the sessions behind the socket,
  and it already routes an incoming peer by info hash — which a multi-torrent backend needs and a
  single-torrent CLI did not.

**Still the owner's to decide, and the reason this item stays open:** the transport, the
serialisation format, and what happens when the socket is not only local. Nothing in the desktop
work forces any of the three, and guessing one would put a security model in the repository that
nobody chose. Recorded here rather than started.

- AC: the item is reopened with the transport, the serialisation format and a security model as
  real acceptance criteria. Until those three are answered there is nothing here a test could
  fail.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/session/`,
  `cli/src/main/kotlin/ru/workinprogress/kachok/cli/`.
