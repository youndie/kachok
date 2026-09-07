---
id: B-11
title: "One writer coroutine, one gathering positional write per piece"
status: done
priority: P0
size: M
stage: m3-storage
epic: feature-download
blocked_by: [B-08, B-12]
---

# B-11 — One writer coroutine, one gathering positional write per piece

Blocks arrive from many peers in any order; the disk should see whole pieces, written once. This
is the item [../research/research-architecture.md](../research/research-architecture.md) D4 describes, and it exists as one writer because file I/O blocks a carrier
thread (research §1.1) and one blocked carrier is the budget.

- **The decision and its reason.** A `Channel<Block>` consumed by one coroutine; blocks are
  grouped per piece in a small map; a complete piece is hashed ([B-13](B-13-hashing-dispatcher.md))
  and, if it matches, written with `FileChannel.write(ByteBuffer[], …)` at the piece's position —
  one call per file span the piece covers — and its buffers returned to the pool. A mismatch drops
  the buffers, marks the piece for re-download and records which peers contributed.
- Rejected: writing each block as it arrives. Sixteen-plus system calls per piece, and a corrupt
  piece on disk that has to be overwritten.
- Not covered: `force()` policy ([B-14](B-14-deferred-force-timer.md)); the upload read path
  ([B-20](B-20-upload-read-path.md)).

- AC **met 2026-09-05** (`BlockWriterTest`, 5 tests): a piece spanning two files produces exactly two `write` calls (counted through a fake
  channel); a piece with a wrong hash never reaches the channel; after the write the pool's
  outstanding count returns to what it was before the piece's first block.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/storage/`, `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/storage/`.

**Closed 2026-09-05.** The measurement the item asked for, and one thing the plan got wrong:

* **A piece costs one write per file span, not one per block.** Asserted through a counting sink:
  four blocks inside one file are a single call; the piece that spans all three files of the
  fixture is three calls at the three right positions with the three right lengths.
* **There is no positional gathering write in the JDK**, so the write is aimed by moving the
  channel's position. That is channel state, so it is correct only because there is exactly one
  writer — which makes the single-writer decision load-bearing for a second reason beyond the
  carrier budget. Correction written into the research at D4.
* **A duplicate block is released, not counted.** Endgame asks several peers for the same block on
  purpose; counting the second copy towards the piece would complete a piece with a hole in it.
* **A writer that stops mid-piece releases what it was holding.** A session that ends with pieces
  in flight would otherwise leave their buffers out on loan, which looks exactly like a stall.

The `SpanSink` seam exists because the claim worth asserting is *how many system calls a piece
costs*, and a `FileChannel` cannot be asked that.
