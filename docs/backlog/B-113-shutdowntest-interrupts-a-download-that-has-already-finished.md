---
id: B-113
title: "`ShutdownTest` can interrupt a download that has already finished, and then finds no record"
status: open
priority: P3
size: S
stage: m9-swarm
epic: feature-download
---

# B-113 — `ShutdownTest` can interrupt a download that has already finished, and then finds no record

Seen once, in a full `./gradlew build` on the build machine on 2026-09-18, and not in three
reruns of the test alone:

```
ShutdownTest > anInterruptedDownloadTellsTheTrackerAndLeavesAUsableRecord() FAILED
  no resume record was written; output was
    57/245 pieces (23%) … 122/245 … 186/245 … 245/245 pieces (100%) …
    payload.bin: complete
```

The test waits until the seed has served twenty blocks, then sends `SIGINT`; the seed delays
each block by 15 ms, so 245 one-block pieces take about four seconds, and the signal is meant to
land in the middle. Here the client printed every progress line and `complete` before the signal
took effect, and a download that finished has nothing to record — the assertion is right about
what it saw. What made four seconds not enough is not known: the run was inside a full build,
with the JIT warm from the end-to-end test before it and the machine busy with the rest of the
build, and either is enough to move a 15 ms budget.

- **The decision.** Make the interrupt's timing a fact and not a race: the seed should be told to
  stop serving after N blocks — `SeedingPeer` already counts them — so the client is provably
  mid-download when the signal arrives, however fast the machine. The 15 ms delay then becomes
  what it should have been, a courtesy, not the mechanism.
- Rejected: a longer delay per block. It moves the race; it does not remove it.
- Not covered: the same shape in `DownloadTest`, if it has it — check before closing.

- AC: the test passes under `./gradlew build` on the build machine ten times in a row with the
  seed frozen after the twentieth block, and fails if the freeze is removed and the delay set to
  zero — which is the mutation that proves the freeze is what the test rests on.
- Anchors: `cli/src/test/kotlin/io/github/youndie/kachok/cli/ShutdownTest.kt`,
  `swarm/src/main/kotlin/io/github/youndie/kachok/swarm/SeedingPeer.kt`.
