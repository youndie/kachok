---
id: B-18
title: "kachok download <file.torrent> [--dir …]: progress on stderr, exit 0 on completion"
status: open
priority: P0
size: M
stage: m4-download
epic: feature-cli
blocked_by: [B-17]
---

# B-18 — kachok download <file.torrent> [--dir …]: progress on stderr, exit 0 on completion

The first thing a user can run. Replaces the skeleton `main` that exits with code 2.

- **The decision and its reason.** Hand-written argument parsing (no library; the surface is
  small), a factory that builds the JVM implementations and hands them to the engine, a render loop
  reading the `StateFlow` every second, `SIGINT` → graceful stop ([B-25](B-25-graceful-shutdown.md)).
  Exit codes: `0` complete, `1` failed, `2` usage — the last one is already in `Main.kt`.
- Rejected: a TUI. Progress is a line per second; a UI is phase 2.
- Not covered: seeding after completion (`--seed`, [B-21](B-21-choking-algorithm.md)); resume
  (`--resume`, [B-23](B-23-atomic-resume-file.md)).

- AC: `kachok download fixture.torrent --dir out` against a local tracker + seed completes with
  exit `0` and the file's hash matches; a missing file argument prints usage and exits `2`; an
  unreachable tracker exits `1` with the tracker's error on stderr.
- Anchors: `cli/src/main/kotlin/ru/workinprogress/kachok/cli/Main.kt`, `cli/src/main/kotlin/ru/workinprogress/kachok/cli/`.
