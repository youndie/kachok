---
id: B-43
title: "The picker allocates a candidate list on every request"
status: open
priority: P2
size: S
stage: m7-measure
epic: feature-download
---

# B-43 — The picker allocates a candidate list on every request

`PiecePicker.rarestUnstarted` builds a `List` of every piece the peer has, filters it and takes the
minimum — over all pieces, every time a block is chosen. The JFR profile of the Debian download
(research §1.2b) puts it at the top of the allocation samples, with `next` second; the block path
itself contributes none, which is the design working.

- **The decision and its reason.** Keep an availability structure the picker can query instead of
  rebuilding: pieces bucketed by availability, updated when a `have` or a `bitfield` moves one. The
  cost then follows the *number of candidate buckets* rather than the number of pieces.
- **The baseline named the mechanism** ([B-26](B-26-jfr-baseline-of-the-hot-path.md), research
  §1.2c): the allocated type is `java.lang.Integer`, so what costs is the boxing in the candidate
  `List<Int>`, not the scan itself. On a 3 020-piece torrent it cost nothing measurable — no GC
  pressure, six megabytes live — which is why this stays P2. At a hundred thousand pieces it is a
  hundred thousand boxed integers per request.
- Not covered: the allocation in `next` itself, which is the returned list and is the point.

- AC: a torrent with 100 000 pieces chooses a block without allocating proportionally to the piece
  count, and the existing picker tests still pass unchanged — the rules are not what is changing.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/picker/PiecePicker.kt`.
