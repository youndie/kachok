---
id: B-57
title: "A paused torrent, which the engine does not have"
status: done
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

## What it turned out to be

- **`shutDown` minus the last line.** `Command.Pause` announces `stopped`, closes the peers, flushes
  and records — and does not cancel the session's job. `Command.Resume` announces `started` and
  dials. The bitfield, the picker and the scope are untouched, which is what makes a resume cost one
  announce instead of a start-up verify pass.
- **The state is published before the announce, not after.** A `stopped` announce is a request to
  somebody else's server and can take seconds; a row that went on saying *Downloading* for that long
  after the press is the defect the button was being fixed for.
- **`failed` is cleared after the announce, not before.** Pausing fills it — every peer this session
  hangs up on is recorded as a failure by `serve`'s `finally` — and those `finally` blocks are still
  draining while the resume's announce suspends. Clearing at the top of `resume` was undone by them,
  and the symptom was a resume that announced, reported itself un-paused and dialled nobody.
- **Four guards, not one flag.** `paused` is read where a paused session must not start work:
  dialling, requesting, answering an incoming handshake, and telling a tracker or the DHT that this
  client is here. Each is also guarded by `stopping`, and the two are different conditions.
- **The bar is built from the selection.** *Pause* and *Resume* are enabled by what the selected row
  is doing, and each disabled case says which it is — nothing selected, already paused, not paused.
  The toolbar's guard had to learn the difference between "off because the engine cannot" (names an
  item) and "off because of what is selected" (must not).
- **The toolbar's icons had no `contentDescription` at all.** They were unreadable to a screen
  reader and unaddressable by a test, which is why the bar's own guard could only ever click the one
  control that happens to carry text. Nine controls now carry their label.

- AC: a running torrent pauses and resumes without re-verifying a piece; the row is *Paused* while
  it is, `PAUSED_IS_PLANNED` is deleted, and the test that asserts nothing maps to *Paused* fails
  and is replaced by one that asserts something does.
  **Automated:** `engine/src/commonTest/.../session/SessionTest.kt` —
  `pausingGivesUpThePeersAndKeepsTheSession`, `resumingAnnouncesAgainAndVerifiesNothing`;
  `ui/src/desktopTest/.../session/SessionRowTest.kt` —
  `aPausedTorrentIsPausedWhateverElseItWasDoing`; `ui/src/desktopTest/.../main/ToolbarStateTest.kt`
  and `WiringTest.everyEnabledToolbarControlLeavesTheWindow`, which now presses every enabled
  control in every selection.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/session/Session.kt`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/session/SessionRow.kt`.
