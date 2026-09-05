---
id: B-17
title: "Session: the StateFlow, the command channel and the one timer"
status: open
priority: P0
size: L
stage: m4-download
epic: feature-download
blocked_by: [B-07, B-11, B-15, B-16]
---

# B-17 — Session: the StateFlow, the command channel and the one timer

The piece that makes the others a client: owns the torrents, their peers, the tracker loop, the
picker and the writer, under one `SupervisorJob`; publishes state; accepts commands.

- **The decision and its reason.** One `StateFlow<SessionState>` — conflated, so a UI or the CLI
  sees the latest state and never a queue of updates — and one `Channel<Command>` as the only
  mutation path; these two are the API a phase-2 UI, and a phase-2 remote UI (research Risk 4),
  talks to. **One timer coroutine per session** drives the 10 s choke pass, the 30 s optimistic
  rotation, the 120 s keep-alives, the `force()` pass and the resume write; per-peer timers are
  forbidden ([../research/research-architecture.md](../research/research-architecture.md) §1.5, D1).
- Rejected: an actor per peer with its own ticker. Thousands of tickers are thousands of
  wake-ups; one timer is one.
- Not covered: seeding logic ([B-20](B-20-upload-read-path.md), [B-21](B-21-choking-algorithm.md)),
  resume ([B-23](B-23-atomic-resume-file.md)).

- AC: `runTest` with fakes: adding a torrent announces, connects to the returned peers, requests
  blocks, and the `StateFlow` reports the downloaded byte count monotonic; cancelling the session
  cancels every peer coroutine (asserted through the fake transport's close count) and sends
  `stopped` to the tracker.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/session/`.
