---
id: B-54
title: "More than one torrent in one process"
status: open
priority: P1
size: L
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-52]
---

# B-54 — More than one torrent in one process

The engine is one `Session` per torrent and nothing above it holds several. Every phase-2 screen is
built for many and is given one: the list draws sixteen rows in its golden and one at run time, the
status bar counts `1 torrent, 1 seeding, 0 paused`, and the add dialog's *Add* is greyed with the
reason written on it ([B-50](B-50-add-torrent.md)).

Found closing [B-52](B-52-ui-on-the-real-engine.md), where it was named as that item's first
finding rather than a surprise.

- **The decision this needs.** What owns several sessions, and what it shares between them. A
  `BufferPool` is sized per torrent today (`STARTED_PIECES × blocks + maxPeers`); sixteen torrents
  with a pool each is sixteen times a number chosen to fit 128 MiB. The same question for
  `EngineDispatchers` — one executor for the process, certainly — the DHT socket, and the listener,
  which is one port and has to route an incoming handshake to the session whose info hash it names.
- Rejected in advance: a `Session` that holds many torrents. The engine's confinement — one writer,
  one timer, `limitedParallelism(1)` — is per session and is what makes its state race-free; making
  it multi-tenant would relitigate every one of those.
- Not covered: persistence of the list across restarts, which is a separate question and probably
  the same file the resume records live beside.

- AC: two torrents download at once in one window; the status bar's counts and the buffer pool's
  peak are both measured with two running and written into the research.
- Anchors: `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/runtime/TorrentRuntime.kt`,
  `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/session/Session.kt`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt`.
