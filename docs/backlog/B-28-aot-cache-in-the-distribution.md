---
id: B-28
title: "Build the AOT cache with the launcher's flags and prove it maps"
status: open
priority: P2
size: M
stage: m7-measure
epic: feature-cli
blocked_by: [B-29]
---

# B-28 — Build the AOT cache with the launcher's flags and prove it maps

Research §1.2 verified that the cache works on 25.0.2 and that a flag mismatch fails **silently**
into a normal, slow start. Shipping a cache therefore means shipping the proof that it is used.

- **The decision and its reason.** The distribution build runs a training download against a
  local seed through the launcher with `-XX:AOTCacheOutput`, then starts the launcher again with
  `-Xlog:aot=info` and fails the build unless the log says `Opened AOT cache` — research Risk 3.
  Class path is jar-only and fixed by the image layout (JEP 483's requirement).
- Rejected: `-XX:+AutoCreateSharedArchive` at first run on the user's machine. It works, and it
  means the first run is slow on every machine, which is the run people judge.
- Not covered: refreshing the cache when the JDK in the image changes — the build makes a new one.

- AC: the distribution's smoke test asserts the `Opened AOT cache` line; start-up time with and
  without the cache is in the research.
- Anchors: `cli/build.gradle.kts`, `.github/workflows/ci.yml`.
