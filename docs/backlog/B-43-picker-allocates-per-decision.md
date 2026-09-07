---
id: B-43
title: "The picker allocates a candidate list on every request"
status: done
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
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/picker/PiecePicker.kt`,
  `engine/src/jvmTest/kotlin/io/github/youndie/kachok/engine/picker/PickerAllocationTest.kt`.

**Done, and measured both ways.** On a 100 000-piece torrent one choice allocated **4 477 304
bytes** before and **448 bytes** after — `getThreadAllocatedBytes` over a hundred choices, with the
old implementation put back to check the test could fail at all. Every existing picker test passes
unchanged, which was the other half of the criterion: the rules are not what changed.

**The profile named the type and there were two sources of it, not one.** The candidate `List<Int>`
was the obvious one. The other was `index in started`, a `Map<Int, PieceProgress>` lookup that
boxes the index — asked once per piece per request, so it produced as many `Integer`s as the list
did. A `BooleanArray` beside the map answers it for nothing, and both mutations of `started` now go
through one pair of methods, because a second copy of a key set is worth nothing if it can drift.

**Not the structure the item proposed.** It suggested bucketing pieces by availability so the cost
would follow the number of buckets rather than the number of pieces. That would make the *scan*
sub-linear; what the measurement said was that the scan was never the cost — the boxing was. A
single pass with primitives and a reservoir sample for the random first piece is allocation-free
and keeps every rule where a reader can see it. The bucket structure remains available if a
measurement ever says the scan itself is what costs; nothing here says that.
