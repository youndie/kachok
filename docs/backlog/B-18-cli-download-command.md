---
id: B-18
title: "kachok download <file.torrent> [--dir …]: progress on stderr, exit 0 on completion"
status: done
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

- AC **met 2026-09-05** (`DownloadTest`, 5 tests): `kachok download fixture.torrent --dir out` against a local tracker + seed completes with
  exit `0` and the file's hash matches; a missing file argument prints usage and exits `2`; an
  unreachable tracker exits `1` with the tracker's error on stderr.
- Anchors: `cli/src/main/kotlin/ru/workinprogress/kachok/cli/Main.kt`, `cli/src/main/kotlin/ru/workinprogress/kachok/cli/`.

**Closed 2026-09-05.** The end-to-end test downloads 40 000 bytes from a local swarm — an HTTP
tracker and a peer that speaks BEP 3 over a socket — in 367 ms, and compares every byte. It found
a defect no unit test had:

* **A single-file torrent's `name` is the file, not a directory.** BEP 3 says so in one sentence
  and the first `FileSet` read it the other way, creating `out/payload.bin/` as a directory and
  writing `out/payload.bin/payload.bin` inside it. Every storage test until then had used a
  multi-file fixture, where the behaviour is correct — so the untested case was the common one.
  `Metainfo.isSingleFile` now records which key the parser found rather than inferring it from the
  shape, because a multi-file torrent holding one file named after its directory would be misread
  by any inference.
* **One peer identity, not two.** The first wiring generated a peer id for the tracker announce and
  another for the handshake, so the swarm would have been told about a peer nobody ever meets.

The test also asserts that the seed served exactly `content.size` bytes: every byte requested once,
no duplicates outside endgame. That is the picker's contract, verified through a socket rather than
against its own state.
