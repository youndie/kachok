---
id: B-23
title: "A resume record written atomically and rarely"
status: open
priority: P1
size: M
stage: m6-resume
epic: feature-resume
blocked_by: [B-17]
---

# B-23 — A resume record written atomically and rarely

Restarting the client must not mean re-downloading. The resume record is the bitfield of verified
pieces plus the metainfo hash, written to a temporary file and `ATOMIC_MOVE`d — [../research/research-architecture.md](../research/research-architecture.md) D4.

- **The decision and its reason.** Written from the session timer every N minutes and at clean
  shutdown; **only hashed pieces** are recorded, never "written" ones, so a crash before `force()`
  can lose data but cannot produce a resume file that vouches for it (research Risk 5). Bencoded,
  because the codec already exists.
- Rejected: a database. It is one small file per torrent.
- Not covered: verifying that the data on disk still matches the record
  ([B-24](B-24-startup-verification-of-existing-data.md)).

- AC: killing the process during a write leaves either the old or the new file, never a
  truncated one (a test that interrupts the writer between the temp write and the move); the
  record round-trips through the bencode codec; a record whose info hash differs from the torrent
  is refused.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/resume/`, `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/storage/`.
