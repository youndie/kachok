---
id: B-65
title: "Sequential download, which the add dialog offers and the picker does not do"
status: done
priority: P3
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-54]
---

# B-65 — Sequential download

The add dialog draws the checkbox, with the design's own explanation under it — *ask for pieces in
order rather than rarest first; slower overall, and it makes this client a worse swarm member* — and
the design's `planned` badge beside it. The engine picks rarest-first and has no other order.

- **The decision this needs.** Whether it is a session-wide mode or a window at the head of the
  file. Strict order is the simplest thing to implement and the worst thing for the swarm: every
  peer asks for piece 0 first, nobody has anything rare to trade, and the client that does it
  finishes last. The usual compromise is rarest-first with a sequential window of N pieces ahead of
  what has been played, which needs somebody to say what N is.
- Rejected in advance: making it the default. The picker's cost was measured rarest-first
  (research §1.2c) and every number in that section assumes it.
- Not covered: streaming — telling a player where the readable prefix ends — which is the reason
  anybody wants this and is a second interface on top of it.

## The decision, taken

**Strict order, bounded by `maxStartedPieces` and nothing else.** The compromise the item describes
— rarest-first with a sequential window of N pieces ahead of what has been played — exists to keep a
*player* fed, and streaming is explicitly not part of this. Choosing an N with no player to measure
it against would be inventing a number. What the design's checkbox promises is "ask for pieces in
order", and that is what it does; the concurrency stays the bound the picker already had.

Off by default and it stays off, for the reason the item gives: the picker's cost was measured
rarest-first and every number in the research assumes it. `theDefaultIsStillRarestFirst` asserts it.

## The measurement this AC asks for cannot be made here

**A swarm cost needs a swarm.** The local stand is one seeder that has everything, where no piece is
rarer than any other and rarest-first and sequential make the same requests in a different order —
so a figure taken from it would be a number that measured nothing, which is worse than an admitted
gap. What it would need is several peers holding different subsets, and the honest place to record
it is beside the rarest-first numbers when there is a stand that can produce it.

## Not verified by driving it

The order is asserted in the picker and the dialog's tick is asserted through the dialog; the seam
between them — tick to `RuntimeOptions.sequential` — has its own test. What was not driven is the
whole path through the *native* file chooser, which is the only way to reach the dialog for a
torrent on disk: the chooser's go-to-folder sheet does not take synthetic keystrokes, and a torrent
passed on the command line opens without a dialog.

- AC: a torrent added with the box ticked requests pieces in order; the box is no longer `planned`;
  the swarm cost of doing it is measured on the local swarm and written into the research beside
  the rarest-first numbers. **The third clause is not met and cannot be on this stand — see above.**
  **Automated:** `engine/src/commonTest/.../picker/PiecePickerTest.kt` —
  `sequentialAsksForTheLowestPieceThePeerHas`, `sequentialTakesTheLowestThatIsActuallyAvailable`,
  `sequentialStillSkipsUnwantedPieces`, `theDefaultIsStillRarestFirst`;
  `ui/src/desktopTest/.../add/AddTorrentTest.kt` — `theSequentialTickLeavesTheDialog` and the badge
  count; `.../session/SettingsFromTest.kt` — `thePerTorrentChoicesReachTheEnginesOptions`.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/picker/PiecePicker.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/add/AddTorrent.kt`.
