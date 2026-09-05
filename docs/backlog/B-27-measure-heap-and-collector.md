---
id: B-27
title: "Measure the heap the engine needs, with G1 and with ZGC"
status: done
priority: P1
size: S/M
stage: m7-measure
blocked_by: [B-26]
---

# B-27 — Measure the heap the engine needs, with G1 and with ZGC

Research Open question 2 and decision D6: the `-Xmx256m` and the choice of G1 are hypotheses.

- **The decision and its reason.** Same download, four runs: G1 and ZGC, each with and without
  compact object headers; `-Xlog:gc` for pauses and live set. The winner's flags go into
  `applicationDefaultJvmArgs` and the launcher; the loser's numbers stay in the research so the
  question is not reopened without new data.
- Rejected: choosing by reputation. The heap here is small and off-heap data dominates, which is
  not the case either collector's published numbers describe.
- Not covered: heap sizing for the phase-2 UI.

- AC: a table in the research with live set, max pause and RSS per configuration; the flags in
  `cli/build.gradle.kts` reference it.
- Anchors: `cli/build.gradle.kts`, `cli/src/test/kotlin/ru/workinprogress/kachok/cli/CollectorBench.kt`,
  `docs/research/research-architecture.md` §1.2d.

**Done.** Research §1.2d has the table — six configurations, 1 GB three times each, round robin.
G1 keeps its place: ZGC's pauses are a hundred times shorter and cost 90 MB of resident memory,
which is the wrong trade for a client with no frame to miss. Two things the item did not ask for
came out of it. Compact object headers are **not measurable** at a live set of 8 MB, so the flag
stays for a different reason than D6 gave. And `-Xmx`, which the item treated as the constant of
the experiment, turned out to matter more than the collector: 128m holds the same live set, pauses
less in total than 256m, and is 80 MB smaller resident, so the launcher now sets it.
