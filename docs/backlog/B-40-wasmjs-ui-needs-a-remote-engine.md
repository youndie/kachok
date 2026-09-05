---
id: B-40
title: "Phase 2: what does a wasmJs UI talk to?"
status: question
priority: P3
size: L
stage: phase-2-ui
blocked_by: [B-39]
---

# B-40 — Phase 2: what does a wasmJs UI talk to?

Research Risk 4 and Open question 4: a browser has no TCP or UDP sockets, so a wasmJs build of
the *engine* cannot exist; a wasmJs build of the *UI* needs an engine somewhere else.

- **The question.** Remote engine over a local WebSocket (the desktop client exposing its
  `StateFlow` and command channel), or WebRTC peers (a WebTorrent-compatible swarm that is a
  different protocol with different peers). The research's hypothesis is the former.
- What phase 1 does about it: keeps the engine's API to a state flow and a command channel, so
  that putting it behind a socket is a serialisation task, not a redesign.
- Not covered: any code.

- AC: a decision recorded in the research at the start of phase 2, with the alternative and its
  reason.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/session/`.
