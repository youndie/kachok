---
id: B-27
title: "Measure the heap the engine needs, with G1 and with ZGC"
status: open
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
- Anchors: `cli/build.gradle.kts`, `docs/research/research-architecture.md`.
