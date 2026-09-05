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

- AC: not applicable until phase 2 starts; the item is reopened with the transport, the
  serialisation format and a security model as real acceptance criteria.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/session/`,
  `cli/src/main/kotlin/ru/workinprogress/kachok/cli/`.
