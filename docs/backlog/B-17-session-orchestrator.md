---
id: B-17
title: "Session: the StateFlow, the command channel and the one timer"
status: done
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

- AC **met 2026-09-05** (`SessionTest`, 9 tests, all on fakes): `runTest` with fakes: adding a torrent announces, connects to the returned peers, requests
  blocks, and the `StateFlow` reports the downloaded byte count monotonic; cancelling the session
  cancels every peer coroutine (asserted through the fake transport's close count) and sends
  `stopped` to the tracker.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/`.

**Closed 2026-09-05.** Three defects the fakes found, none of which is about the session's logic:

* **Iterating the peer table across a suspension point is a race, single-threaded or not.**
  `connected.values.forEach { it.connection.send(…) }` suspends inside its own loop, and a
  suspension is exactly where another coroutine runs — including a peer coroutine adding or
  removing its entry. The symptom was an intermittent `ConcurrentModificationException` from a loop
  that "cannot" race. Four such loops; all now iterate a snapshot.
* **A connection ending is news, not a failure.** `SocketPeerConnection` reported
  `PeerEvent.Closed` *and* rethrew, so closing a connection ourselves produced an
  `AsynchronousCloseException` with nowhere to go: under a `SupervisorJob` it reaches whatever the
  platform does with an uncaught coroutine exception, which in this suite meant a failure in
  whichever test happened to run next. Five consecutive clean runs is what closed this.
* **`catch (Exception)` around a suspending call swallows cancellation.** A peer that catches its
  own `CancellationException` cannot be cancelled and outlives its session. Every such catch now
  rethrows it first.

Two things the item did not ask for and the code needed anyway: `lastPeerError` and `sessionError`
in the state. "No peers and no reason" is a state nobody can act on, and a session loop that dies
quietly is worse — both are now fields a CLI can print rather than log lines nobody attributes.

The timer runs keep-alives and `force()`; the ten-second choke pass joins it in
[B-21](B-21-choking-algorithm.md), which is what the one-timer design was for.
