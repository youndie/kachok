---
id: B-26
title: "A JFR baseline: allocations on the hot path, pinned threads, carrier count"
status: open
priority: P1
size: M
stage: m7-measure
blocked_by: [B-19]
---

# B-26 — A JFR baseline: allocations on the hot path, pinned threads, carrier count

The brief promises "zero garbage on the hot path". That is a claim about a profile, and the
profile has to exist. This item also settles the buffer-pool cap and the `force()` interval defaults.

- **The decision and its reason.** `-XX:StartFlightRecording` on the
  [B-19](B-19-end-to-end-download-acceptance.md) download; the allocation profile by stack, the
  `jdk.VirtualThreadPinned` events, the platform thread count over time. Any allocation attributed
  to the `piece` path is a bug; any pinned event in the transport is a bug. The numbers go into
  the research with the torrent and machine named ([../research/research-architecture.md](../research/research-architecture.md) Risk 1, Risk 2).
- Rejected: an external profiler. JFR is in the run-time image and in every CI run.
- Not covered: micro-benchmarks. A JMH suite is worth having once there is a number to protect.

- AC: the research §1 gains a "measured" subsection with the allocation rate, the pinned event
  count and the carrier peak; the pool cap and `force()` interval constants cite it.
- Anchors: `docs/research/research-architecture.md`, `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/io/`.
