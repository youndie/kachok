---
id: B-52
title: "The UI on the real engine, not on a fixture"
status: open
priority: P1
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-48]
---

# B-52 — The UI on the real engine, not on a fixture

Every screen before this one is drawn from a fixture, which is what makes goldens possible. This is
the item that connects them to `Session` and finds out what the design assumed and the engine does
not do.

- **The decision and its reason.** The same factory the CLI uses (`Download.kt`), lifted so both
  surfaces build the engine the same way; one `StateFlow` per torrent into the list, commands out
  through the one channel. A window that runs a real download.
- Rejected: a second wiring for the UI. Two factories mean two clients, and the second one is
  always the one that is wrong.
- Not covered: multiple torrents in one process — the engine is one `Session` per torrent today,
  and the list is built for many. That gap is this item's first finding, not a surprise.

- AC: the desktop app downloads the fixture torrent from a local swarm and the list shows it
  progressing; every field the design marked `planned` is still marked in the running app.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt`, `cli/src/main/.../Download.kt`.
