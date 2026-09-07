---
id: B-59
title: "Force re-check: verifying a torrent that is already running"
status: done
priority: P2
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-54]
---

# B-59 — Force re-check: verifying a torrent that is already running

The engine verifies on start-up — every piece the resume record does not vouch for is read and
hashed — and has no way to be asked to do it again. The window's *Force re-check* is greyed and
says so ([B-56](B-56-dead-toolbar-controls.md)).

- **The decision this needs.** What happens to the download while it runs. A re-check of a live
  torrent is a hash pass over data the writer is still appending to; either the session stops
  requesting for the duration, or the pass has to be told which pieces are moving.
- Rejected in advance: stop, re-check, start as three commands from the UI. That is three chances
  to leave a torrent stopped, and it announces `stopped` and `started` to a tracker for something
  the swarm has no interest in.
- Not covered: re-checking a torrent whose files were replaced under it, which is the case that
  makes this worth having and also the case where "the writer is still appending" is false.

## The decision, taken

**Transfers stop; the session does not, and the tracker is never told.** The pass reads the same
files the writer appends to, so the peers go for its duration — but a `stopped` followed by a
`started` for a disk check is announce churn about something the swarm cannot act on, which is what
the item rejected stop-recheck-start for. A torrent that was paused before is still paused after: a
re-check is a question, not a decision to start.

**Off the confined dispatcher.** Hashing a large torrent is minutes of blocking reads, and under the
session's `limitedParallelism(1)` that would stop the timer, the tracker and every peer coroutine
with it — the same escape a dial already uses.

## What it cost, and what nearly shipped

- **`PiecePicker.restore` refuses to seed a picker that is in use**, and rightly — at start-up that
  guard catches a check racing the first request. A re-check is the one caller that legitimately
  empties it first, so the picker gained `forget()` and `Bitfield` gained `clear()`.
- **The first version of the test passed while the application was visibly broken.** Pressing the
  button put *payload.bin is degraded — commands: the picker is already in use* on the banner; the
  test asserted the announce count, the pass's coverage and the paused flag, and every one of those
  held on a session that had already failed. `sessionError == null` is now asserted, and the
  comment saying why is in the test.

- AC: a running torrent is re-checked without stopping; a piece corrupted on disk between the
  first check and the second is found and re-requested.
  **Automated:** `engine/src/commonTest/.../session/SessionTest.kt` —
  `aRecheckHashesEveryPieceAndTellsTheTrackerNothing`, `aRecheckFindsAPieceThatWentBadOnTheDisk`,
  `aRecheckLeavesAPausedTorrentPaused`. Checked by hand as well: twenty-one bytes overwritten in a
  seeded 96 MiB file, *Force re-check* pressed, and the bytes on disk were the torrent's own again
  eight seconds later — the whole round trip is faster than the window's one-second sample, which is
  why the row never visibly leaves 100%.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/hash/`.
