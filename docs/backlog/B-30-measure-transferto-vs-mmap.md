---
id: B-30
title: "Does seeding need mmap? Measure transferTo against a mapped file"
status: done
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
- Anchors: `engine/src/jvmTest/kotlin/ru/workinprogress/kachok/engine/storage/UploadPathBench.kt`,
  `docs/research/research-architecture.md` §1.3c.

**Done, and D5 stands — but not for the reason D5 gave.** `transferTo` moves 3157–3298 MB/s for
1.84–1.92 CPU-seconds per gigabyte; the mapped path moves 2603–2870 MB/s for 2.10–2.30. Neither
range overlaps, over three runs of eight seconds at the choker's operating point of five peers.

The correction is in the reasoning. D5 argued that an mmap read ends in the same copy the kernel
would have made anyway, so the two should cost the same and `transferTo` should win on machinery
alone. They do not cost the same: the mapping is 15 % slower and 15 % more CPU. What a count of
copies does not see is the `asByteBuffer()` view and the faults behind it. The carrier price D5
warned about did not appear at all — seventeen platform threads on both paths.

The mmap path was written for the measurement and is not shipped, which is what the item asked for.

**A hazard came out of it that the item did not ask about.** Above about ten concurrent peers the
harness could not tear itself down: every thread still alive after its socket was closed, and
`FileChannel.close()` blocked for ever waiting for the ones inside `transferTo0`. It happens on the
mapped path too, so it is not about `transferTo`.
[B-44](B-44-does-closing-a-peer-end-a-write-in-flight.md) asks what it is.
