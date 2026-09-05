---
id: B-11
title: "One writer coroutine, one gathering positional write per piece"
status: open
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

- AC: a piece spanning two files produces exactly two `write` calls (counted through a fake
  channel); a piece with a wrong hash never reaches the channel; after the write the pool's
  outstanding count returns to what it was before the piece's first block.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/storage/`, `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/storage/`.
