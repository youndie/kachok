---
id: B-23
title: "A resume record written atomically and rarely"
status: done
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

- AC **met 2026-09-05** (`ResumeRecordTest` 6 tests, `FileResumeStoreTest` 5, `SessionTest` ×1): killing the process during a write leaves either the old or the new file, never a
  truncated one (a test that interrupts the writer between the temp write and the move); the
  record round-trips through the bencode codec; a record whose info hash differs from the torrent
  is refused.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/resume/`, `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/storage/`.

**Closed 2026-09-05.** Writing the record is done; *reading* it back into a session — seeding the
picker and re-hashing what it does not vouch for — is [B-24](B-24-startup-verification-of-existing-data.md).

Four decisions:

* **The record is written after the flush, never before.** It vouches for pieces that are hashed
  *and* on the disk, so the shutdown order is: tracker, peers, writer queue, `force()`, record.
  Written first it would claim a piece the page cache still held.
* **Verified, never merely written.** Under-claiming costs a re-hash; over-claiming sends this
  client back to a swarm announcing a piece it does not have.
* **A temporary sibling, then `ATOMIC_MOVE`.** A sibling rather than a temporary directory, because
  a move across filesystems is not atomic and on some is not possible at all. Where the filesystem
  cannot promise atomicity the store says so and falls back, rather than pretending.
* **A record for another torrent is ignored and kept.** Not ours to use and not ours to delete —
  a stray file in the user's directory is the user's business.

Failing to save is reported, not thrown: a client that stops downloading because it cannot record
its progress is worse than one that re-hashes on the next start. The CLI prints the reason.
