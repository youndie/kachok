---
id: B-129
title: "Four clients recorded taking 40.9 MiB of a 40.0 MiB torrent"
status: open
priority: P2
size: S
stage: m9-swarm
blocked_by: []
---

# B-129 — A piece can be verified twice, and the counter says so

Noticed in the experimental runs of [B-127](B-127-trading-barely-starts-before-a-download-ends.md):
with heavy peer-to-peer trading, four clients downloading a 10 MiB torrent recorded taking **40.9
MiB** between them — about 2 % more than the torrent has. In the runs where they traded almost
nothing the figure was exactly 40.0.

`downloaded` rises by a piece's length in one place: when the writer reports `Verified`. A counter
that can exceed the torrent therefore says a piece was verified — hashed *and written to the disk* —
more than once. The suspect is the writer's `pending` map: it gathers blocks per piece index,
completes the piece when they add up, and **removes the entry**; blocks for that index that arrive
afterwards, which is exactly what endgame's duplicate requests produce and what trading with several
peers makes common, start a fresh entry that can fill up and complete again. Nothing is corrupted —
the same bytes are hashed and written a second time — but the disk is written twice and every
counter derived from `downloaded` is wrong by however often it happens.

- **The decision this needs.** Whether the writer should drop blocks for a piece it has already
  completed, or the session should stop routing them there. The writer knows what it finished; the
  session knows what the picker still wants, and `blockReceived` already returns nothing for a piece
  that is not started. One of the two is the right place and this item is to find out which by
  looking at what each knows at the moment the late block arrives.
- Not covered: whether the extra write is worth preventing on its own. It is a duplicate write of
  bytes that are already correct; the reason to care is the counter, the pool buffers it holds, and
  that a `have` may be announced twice.

- AC: four clients on the `swarm-order` stand record taking exactly one copy each, and a test drives
  a late duplicate block into a completed piece and shows it is dropped rather than re-verified.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/storage/BlockWriter.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`,
  `engine/src/commonTest/kotlin/io/github/youndie/kachok/engine/storage/BlockWriterTest.kt`.
