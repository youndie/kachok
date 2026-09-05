---
id: B-20
title: "Serve requests with FileChannel.transferTo"
status: open
priority: P1
size: M
stage: m5-seeding
epic: feature-seeding
blocked_by: [B-17]
---

# B-20 — Serve requests with FileChannel.transferTo

A `request` from an unchoked peer is answered with a `piece` whose payload is 16 KiB of a file.
The research chose the kernel's zero-copy path over mmap for phase 1 — [../research/research-architecture.md](../research/research-architecture.md) D5.

- **The decision and its reason.** Write the 13-byte `piece` header from a small buffer, then
  `FileChannel.transferTo(position, length, socketChannel)` for the payload — no heap copy, no
  direct buffer, no mapping to unmap. Requests are queued per peer and served in order; a request
  is dropped when the peer is choked or sends `cancel`.
- Rejected (for now): `FileChannel.map(…, Arena)`. Same copy count into the socket, plus
  lifetime management; it is [B-30](B-30-measure-transferto-vs-mmap.md)'s job to say whether it
  wins anything.
- Not covered: the choking decision ([B-21](B-21-choking-algorithm.md)); upload rate limiting
  ([B-22](B-22-rate-limits.md)).

- AC: a fake peer requesting the last block of the last piece receives exactly the remaining
  bytes; a request for more than 16 KiB closes the connection (the behaviour BEP 3 documents as
  universal); `transferTo` is called with the file position derived from the piece mapping.
- Anchors: `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/storage/`, `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/peer/`.
