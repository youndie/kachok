---
id: B-24
title: "Re-hash what the resume file does not vouch for"
status: done
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

- AC **met 2026-09-05** (`StartupVerifierTest`, 6 tests): a directory with a complete file and no resume record is recognised as complete without a
  download; a record missing three pieces re-hashes exactly three.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/resume/`, `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/hash/`.

**Closed 2026-09-05.** The check runs before a single peer is dialled, and it has to: a client that
announced itself and then discovered it already held half the torrent would have asked the swarm
for it first.

* **The record is an optimisation, never the source of truth.** A directory somebody copied in has
  no record and is still recognised as complete; every piece the record does not vouch for is read
  back and hashed.
* **File sizes are not evidence.** A sparse file has its full length from the first write
  (research §1.3a), so "the file is the right size" says nothing about what is in it. Only the hash
  answers the question, which is why there is no size short cut here and will not be one.
* **The trust in the record is bounded by the write ordering, not by a check.** A record can only
  over-claim if it was written for pieces that were then lost, and [B-23](B-23-atomic-resume-file.md)
  writes it after the flush precisely so that cannot happen. `aRecordThatOverclaimsIsStillTrusted…`
  states the trade-off as a test rather than leaving it implied.
* **Every block read for checking is released.** A verification pass costs the memory of a few
  blocks, not of a torrent, because it borrows from the same pool the download uses.

The pass reports progress. A full check of a large torrent takes minutes, and a client that appears
frozen during it is a client somebody kills.
