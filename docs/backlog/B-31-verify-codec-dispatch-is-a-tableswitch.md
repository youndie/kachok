---
id: B-31
title: "Check with javap that the message dispatch compiles to a tableswitch"
status: done
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
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/wire/PeerWire.kt`,
  `docs/research/research-architecture.md` §1.4a.

**Confirmed.** `decode` compiles to `tableswitch { // 0 to 20 }` — one jump table, 21 entries, the
gaps at 9–12 and 18–19 sharing the `default` with an unknown id. The output is quoted in §1.4a, so
the claim is re-checkable rather than believable.

Two things the item did not ask for and the bytecode gave anyway. The table is dense **because the
ids happen to be** — BEP 3's 0–8, BEP 6's 13–17 and BEP 10's 20 sit inside one range; an extension
choosing a far-away id would turn it into a `lookupswitch`, a hash lookup instead of an index. And
`encode`, a `when` over the sealed `Message` hierarchy, is **not** a switch at all: sixteen
`instanceof` tests. That is the half of the brief's claim that is not true, it is on the send path
rather than the receive path, and `-Xwhen-expressions=indy` would change it — which this project
does not enable and now records why.
