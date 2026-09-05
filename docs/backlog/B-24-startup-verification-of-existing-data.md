---
id: B-24
title: "Re-hash what the resume file does not vouch for"
status: open
priority: P1
size: M
stage: m6-resume
epic: feature-resume
blocked_by: [B-23, B-13]
---

# B-24 — Re-hash what the resume file does not vouch for

A resume file says which pieces were verified; pieces it does not mention may be partially on
disk. Start-up hashes them and repairs the bitfield.

- **The decision and its reason.** Read each unverified piece through `Storage` into pool buffers
  and hash it on the hashing dispatcher; also the path for "add a torrent whose files already
  exist" (a full check). Progress is part of the session state so the CLI can show it.
- Rejected: trusting file sizes. A sparse file has its full size from the first write.
- Not covered: mmap-based scanning of very large files — [B-30](B-30-measure-transferto-vs-mmap.md)
  decides whether it is needed.

- AC: a directory with a complete file and no resume record is recognised as complete without a
  download; a record missing three pieces re-hashes exactly three.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/resume/`, `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/hash/`.
