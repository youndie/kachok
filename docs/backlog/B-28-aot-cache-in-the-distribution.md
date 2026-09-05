---
id: B-28
title: "Build the AOT cache with the launcher's flags and prove it maps"
status: done
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
- Anchors: `cli/build.gradle.kts`, `scripts/verify_runtime_image.sh`.

**Done.** `./gradlew :cli:aotCache` starts a seed, downloads 8 MB through the launcher with
`-XX:AOTCacheOutput`, then runs the launcher again with `-Xlog:aot=info` and **fails the build**
unless the VM says `Opened AOT cache`. That failure mode is the whole point of the item: a cache
the VM refuses is a warning line and a completely normal start, so a build that only checked the
cache exists would ship one nobody benefits from.

Training is a whole download and not a start-and-exit, because what a cache is worth depends on
which classes were loaded while it was recorded — the wire, the picker, the hasher and the writer
are only loaded by a download that happens.

Measured (research §1.2a2): start-up 51 ms against 103 ms with no arguments, 459 ms against 674 ms
through the session. The cache is 27.9 MB and the distribution goes from 35 to 63 MB — a large
trade in both directions, recorded rather than assumed away.

The launcher adds `-XX:AOTCache` only if the file is there, so `:cli:runtimeImage` alone still
produces a working distribution; and it reads `KACHOK_JVM_OPTS`, which is how the training and
smoke runs reach the VM *through* the launcher rather than around it. The class path is an explicit
sorted list rather than `lib/*`: a cache is refused unless the class path matches, and a wildcard's
expansion order is the JVM's business rather than a promise.

**The verification script was checking an image the build does not produce.** It wrote its own
copy of the launcher instead of copying the build's, so the training run inside the container had
no `KACHOK_JVM_OPTS` to read and trained nothing — while the script had a mechanism against exactly
this drift, reading the module set and flags out of the image, two lines above. It copies the
launcher now.
