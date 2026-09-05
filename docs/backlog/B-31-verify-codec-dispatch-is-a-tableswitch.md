---
id: B-31
title: "Check with javap that the message dispatch compiles to a tableswitch"
status: open
priority: P3
size: XS
stage: m7-measure
epic: feature-download
blocked_by: [B-06]
---

# B-31 — Check with javap that the message dispatch compiles to a tableswitch

The brief says `when` "compiles to `tableswitch`"; the research says that is true of constant
branches and not of guards. Neither is a fact about this codec until `javap` says so.

- **The decision and its reason.** `javap -c` on the compiled dispatcher, once; the instruction
  goes into the research §1.4 as verified or refuted, and if refuted the codec's `when` is
  rewritten to the shape that does.
- Rejected: `-Xwhen-expressions=indy`. It is for sealed-type switches; the id byte is an integer.
- Not covered: micro-benchmarking the difference; the point is the fact.

- AC: research §1.4 cites the `javap` output.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/wire/`, `docs/research/research-architecture.md`.
