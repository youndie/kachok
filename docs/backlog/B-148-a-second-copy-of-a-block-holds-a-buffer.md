---
id: B-148
title: "A second copy of a block that arrived before its piece's verdict held a pool buffer for good"
status: done
priority: P2
size: S
stage: m3-storage
blocked_by: []
---

# B-148 — A second copy of a block holds a buffer

Seen with B-147's new status line: two finished torrents seeding with no peers connected still had
32 and 4 pool buffers on loan, where nothing should be. Both had finished through endgame, which
asks several peers for the same block on purpose.

- **The cause.** B-129 made the session drop a block for a piece already in `picker.completed`.
  That bit is set when the session reads the writer's verdict, and the second copy of a block can be
  in hand before then: the first copy completes the piece in the writer, which removes the piece's
  entry, and the second reaches the writer afterwards and starts a fresh entry for a piece nobody
  sends again. Its buffer stays out for the life of the torrent. With one-block pieces the fresh
  entry completes at once and the piece is written twice, which is how the test shows it.
- **The decision and its reason.** The picker already knows: it marked the block received when the
  first copy came. `PiecePicker.alreadyReceived` asks that before `blockReceived`, and the session
  drops a copy for which it says yes, as it drops one for a verified piece, and counts it as a
  duplicate. The session rather than the writer, for B-129's reason: after a re-check the writer's
  memory of what it finished is wrong, and the picker's is reset.
- Not covered: a block for a piece the picker has not started and does not have — unasked, or late
  after a bad hash — still goes to the writer. The picker begins that piece again and asks for every
  block, the writer drops the copy it already holds, and the piece completes, so nothing is held.

- AC: a second copy of a block arriving before its piece is verified is dropped and released, and
  the piece is written once. **Met.** **Automated:**
  `engine/src/commonTest/.../session/SessionTest.kt` —
  `aSecondCopyOfABlockArrivingBeforeItsPieceIsVerifiedIsDropped` (fails without the check: the
  piece was written twice, measured).
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/picker/PiecePicker.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`.
