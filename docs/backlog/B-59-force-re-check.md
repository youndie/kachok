---
id: B-59
title: "Force re-check: verifying a torrent that is already running"
status: open
priority: P3
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

- AC: a running torrent is re-checked without stopping; a piece corrupted on disk between the
  first check and the second is found and re-requested.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/session/Session.kt`,
  `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/hash/`.
