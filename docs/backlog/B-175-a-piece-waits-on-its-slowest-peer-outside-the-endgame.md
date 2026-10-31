---
id: B-175
title: "An idle peer takes over a block a slow peer has sat on, before the endgame"
status: wip
priority: P1
size: S
stage: m3-storage
epic: feature-download
blocked_by: [B-174]
---

# B-175 — A piece waits on its slowest peer outside the endgame

Seen in the window on 2026-10-31 (`kachok-0.1.226`, B-174 in), right after the client's limits were
taken off with three films in 8 MiB pieces running on slow swarms: 1.3 MiB/s on average over eight
minutes, sampled through the MCP server every 20 s, against 11 MiB/s the same window had kept up at
a 100 Mbit/s limit on faster swarms an hour before. Reserved by started pieces was **120 MiB of 140
in every sample**, and pieces closed at a few a minute per torrent.

- **The cause.** B-174 made the open pieces few: a torrent at its share of the client's budget
  begins nothing. Its peers then ask for nothing new — and every block of the pieces it does hold
  was already on some peer's queue, so they ask for nothing at all. A piece closes at the speed of
  the slowest peer holding any of its blocks, and with a handful of pieces open, each waiting on
  one slow peer, that is the whole download. The endgame would have fixed it, but it waits for the
  last *reachable* block of the torrent, which was a thousand pieces away.
- **The decision.** A peer the picker would otherwise give nothing to may take a block of a started
  piece whose every request is older than `PiecePicker.STRAGGLER_MILLIS` (10 s); the oldest
  pieces go first, because closing one is what frees the room, and the slow peer is cancelled when
  the first copy arrives, as in the endgame. **Bounded by age, not a count:** taking a block makes it
  young again, so one block gains one more peer per threshold at most. The threshold is a guess and
  says so: a pipeline of 64 blocks behind a peer giving 100 KiB/s is ten seconds deep. A peer that
  came away short is asked again on the session's tick (`PiecePicker.takeShortOfWork`), because a
  block becomes a straggler with time and nothing else.
- **Found on the way.** A cancelled request stayed in the slow peer's `outstanding` until its copy
  came, and a peer that honours the cancel sends none: each one held a slot of that peer's pipeline
  for the life of the connection. Rare while only the endgame cancelled; this item makes it common.
  The session now takes it out of the count when it cancels and remembers it, so the copy or BEP 6's
  reject that may still follow is not counted a second time.

- AC: a block left on a slow peer is asked of an idle one, not before the threshold and not twice
  of the same peer. **Automated:** `PiecePickerTest.aBlockLeftOnASlowPeerIsAskedOfAnIdleOne`,
  `aStragglerGainsOnePeerPerThresholdAndNoMore`, `aStragglerIsOnlyAskedOfAPeerThatHasItsPiece`.
- AC: the session asks for the straggler on its clock, and the cancel frees the slow peer's
  pipeline. **Automated:** `SessionTest.aBlockLeftOnASlowPeerIsTakenOverAndTheCancelFreesItsPipeline`
  (fails without the cancel counted: `expected: <1> but was: <2>`).
- AC: with the limits off on the same torrents, the window downloads faster than the 1.3 MiB/s it
  did without this. **Live, to be measured.**
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/picker/PiecePicker.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`.
