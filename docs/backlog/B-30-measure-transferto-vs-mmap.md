---
id: B-30
title: "Does seeding need mmap? Measure transferTo against a mapped file"
status: open
priority: P2
size: M
stage: m7-measure
epic: feature-seeding
blocked_by: [B-20]
---

# B-30 — Does seeding need mmap? Measure transferTo against a mapped file

Research Open question 1 and D5: `transferTo` was chosen over the brief's FFM mmap on a copy-count
argument. The argument is sound; the measurement is missing.

- **The decision and its reason.** Seed the fixture to five local fake peers pulling flat out;
  measure upload throughput, carrier occupancy and CPU with `transferTo`, then with
  `FileChannel.map(READ_ONLY, …, Arena)` + `socket.write(segment.asByteBuffer())`. The mmap path is
  written for the measurement only; it ships only if it wins by more than noise.
- Rejected: trusting either argument. Both are plausible; that is why it is a measurement.
- Not covered: mmap for start-up hashing — a separate, smaller question raised in
  [B-24](B-24-startup-verification-of-existing-data.md).

- AC: a table in the research; D5 gains a "correction found while implementing" paragraph if
  the hypothesis is refuted.
- Anchors: `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/storage/`, `docs/research/research-architecture.md`.
