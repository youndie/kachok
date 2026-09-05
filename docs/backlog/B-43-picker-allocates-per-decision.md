---
id: B-43
title: "The picker allocates a candidate list on every request"
status: open
priority: P2
size: S
stage: m7-measure
epic: feature-download
blocked_by: [B-26]
---

# B-43 — The picker allocates a candidate list on every request

`PiecePicker.rarestUnstarted` builds a `List` of every piece the peer has, filters it and takes the
minimum — over all pieces, every time a block is chosen. The JFR profile of the Debian download
(research §1.2b) puts it at the top of the allocation samples, with `next` second; the block path
itself contributes none, which is the design working.

- **The decision and its reason.** Keep an availability structure the picker can query instead of
  rebuilding: pieces bucketed by availability, updated when a `have` or a `bitfield` moves one. The
  cost then follows the *number of candidate buckets* rather than the number of pieces.
- Rejected for now: doing it before there is a number. On a 3 020-piece torrent it cost nothing
  measurable, and a data structure added for a profile nobody took is how a picker becomes
  unreadable. That is why this is blocked on [B-26](B-26-jfr-baseline-of-the-hot-path.md) rather
  than open: the baseline decides whether it matters at a hundred thousand pieces.
- Not covered: the allocation in `next` itself, which is the returned list and is the point.

- AC: a torrent with 100 000 pieces chooses a block without allocating proportionally to the piece
  count, and the existing picker tests still pass unchanged — the rules are not what is changing.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/picker/PiecePicker.kt`.
