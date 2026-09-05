---
id: B-57
title: "A paused torrent, which the engine does not have"
status: open
priority: P2
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-54]
---

# B-57 — A paused torrent, which the engine does not have

The engine has `Command.Stop` — announce `stopped`, close the peers, flush, record, finish — and
nothing between running and stopped. The design draws a *Paused* row and marks it `planned`; the
window's *Pause* and *Resume* are greyed and say so ([B-56](B-56-dead-toolbar-controls.md)).

- **The decision this needs.** What a pause keeps. Stopping and starting again is already possible
  and is not a pause: it re-announces, re-dials and re-verifies nothing. A pause that is worth the
  name keeps the bitfield, the resume record and the torrent's place in the list, and gives up the
  peers — which is most of `Command.Stop` without the last step and without leaving the session.
- Rejected in advance: pausing by cancelling the session's scope. Every loop the session owns —
  writer, timer, tracker — is structured under it, and a paused torrent that has to be rebuilt to
  resume is a stopped one with a different name.
- Not covered: pausing *all*, and whether a paused torrent survives a restart (it is the resume
  record's business either way).

- AC: a running torrent pauses and resumes without re-verifying a piece; the row is *Paused* while
  it is, `PAUSED_IS_PLANNED` is deleted, and the test that asserts nothing maps to *Paused* fails
  and is replaced by one that asserts something does.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/session/Session.kt`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/session/SessionRow.kt`.
