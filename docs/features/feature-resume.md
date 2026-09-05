---
id: feature-resume
title: Resume — surviving a restart without re-downloading
type: feature
status: active
owner: unassigned
involved_services:
  - engine
  - cli
client_entries: []
api: []
tags: [phase-1, atomic-move, verification]
---

# Resume

> **Implemented on 2026-09-05** by [B-23](../backlog/B-23-atomic-resume-file.md),
> [B-24](../backlog/B-24-startup-verification-of-existing-data.md) and
> [B-25](../backlog/B-25-graceful-shutdown.md); every scenario below names the test that covers it.
> The reasoning is research D4 and Risk 5.

## 1. Overview

A client that is stopped — cleanly or by a crash — and started again should continue from what is
verified on disk. The resume record is small and honest: it lists the pieces whose hash was
checked, and nothing else. Anything it does not vouch for is re-hashed at start-up.

## 2. Business rules

* The resume record contains the info hash, the bitfield of **verified** pieces, and the
  cumulative `uploaded`/`downloaded` counters for the tracker. It is bencoded.
* It is written to a temporary file in the same directory and moved into place with
  `ATOMIC_MOVE`; a reader sees the old record or the new one, never a partial one.
* It is written from the session timer at a configured interval and at clean shutdown, after
  `force()` has run — never before, so the record can never claim a piece the page cache still
  holds.
* A record whose info hash does not match the torrent is refused.
* At start-up, every piece the record does not mark verified is read from disk and hashed; a
  matching hash marks it verified, a mismatch leaves it unrequested. Adding a torrent whose files
  already exist without a record is the same path over every piece.
* Clean shutdown order: stop announcing (`event=stopped`), close peers, `force()` every file,
  write the record, exit. `SIGINT` starts that sequence with a bounded timeout.

## 3. Flow

```
session timer ── every N min ──▶ force() files ──▶ write <name>.resume.tmp ──▶ ATOMIC_MOVE → <name>.resume

start ── read <name>.resume ──▶ hash check of unverified pieces on the hashing dispatcher ──▶ bitfield ──▶ session
```

## 4. Code anchors

| Service | Code |
|---|---|
| engine | `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/resume/` — the record, its bencoding, the verification plan (target, B-23, B-24) |
| engine | `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/storage/` — temp file + `ATOMIC_MOVE`, `force()` (target, B-23, B-14) |
| engine | `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/hash/` — the start-up hash pass (target, B-24) |
| cli | `cli/src/main/kotlin/ru/workinprogress/kachok/cli/Main.kt` — the shutdown hook (target, B-25) |

## 5. Scenarios (BDD / test cases)

### Scenario: An interrupted write leaves a whole record
* **Given:** a session with a record on disk listing pieces 0–9 verified.
* **When:** the writer is interrupted between writing the temporary file and the move (a fake
  storage that throws at the move).
* **Then:** `<name>.resume` still lists pieces 0–9, and the temporary file is removed on the next
  successful write.
* **Automated:** `FileResumeStoreTest#anInterruptedSaveLeavesTheOldRecordIntact`
  The crash is stood in for by a move that cannot complete, which is the same window.

### Scenario: The record vouches only for hashed pieces
* **Given:** piece 4 has been written but its `force()` has not run and the timer fires.
* **When:** the record is written.
* **Then:** piece 4 is marked verified only if it was hashed before the write — which it always is
  (verify-then-write) — and the write happens after the `force()` pass in the same timer tick.
* **Automated:** `SessionTest#progressIsRecordedOnTheIntervalAndAfterTheFinalFlush`

### Scenario: Start-up re-hashes exactly the unverified pieces
* **Given:** a directory with all data present and a record marking every piece verified except
  3, 5 and 8.
* **When:** the torrent is added.
* **Then:** the hasher is invoked exactly three times, for pieces 3, 5 and 8; all three verify; no
  request is ever sent to a peer.
* **Automated:** `StartupVerifierTest#aRecordMissingThreePiecesRehashesExactlyThree`

### Scenario: A complete file with no record is recognised as complete
* **Given:** the fixture torrent's file already present, no record.
* **When:** the torrent is added.
* **Then:** every piece is hashed once, the session reports 100 %, and the first announce carries
  `left=0` and no `event=completed` (BEP 3: none is sent if the file was complete when started).
* **Automated:** `StartupVerifierTest#aFinishedDownloadWithNoRecordIsRecognisedAsFinished`
  The announce's `event` handling is the session's and is not covered here.

### Scenario: A record for another torrent is refused
* **Given:** a record whose info hash differs from the torrent's.
* **When:** the torrent is added.
* **Then:** the record is ignored with a logged warning naming both hashes, and every piece is
  hashed.
* **Automated:** `ResumeRecordTest#aRecordForAnotherTorrentIsRefused`
  and `FileResumeStoreTest#aRecordForAnotherTorrentIsIgnoredAndKept` — ignored, and kept: a stray
  file in the user's directory is not this client's to delete.

### Scenario: SIGINT during a download stops cleanly
* **Given:** a download in progress against a local tracker and fake peers.
* **When:** the process receives `SIGINT`.
* **Then:** the tracker receives `event=stopped`, every peer connection is closed, the record on
  disk marks exactly the pieces that had been hashed, and the exit code is `0`.
* **Automated:** `ShutdownTest#anInterruptedDownloadTellsTheTrackerAndLeavesAUsableRecord`
  In its own JVM, with a real signal: a shutdown sequence is worth nothing if it does not survive
  the way it is actually triggered. The test also re-hashes every piece the record claims.

## 6. Out of scope

* Moving a torrent's files while it is loaded.
* Recovering from a corrupt record beyond "ignore it and re-hash".
* Persisting peer lists between runs.

## 7. Quirks

* **File sizes prove nothing.** Every file has its full length from the moment it is created, so
  the only question a start-up check can ask is the hash. There is no size short cut and there will
  not be one.
* **The record is trusted, and that trust is bounded by the write ordering.** It can only
  over-claim if it was written for pieces that were later lost, which is what writing it after the
  flush prevents.

* **Re-hashing after a crash is the design, not a bug.** Deferred `force()` (research D4) means a
  crash can lose page-cache data; the record's honesty is what makes that safe, and the visible
  symptom is a hash pass at the next start.
* **`left` after a resume is not `total − downloaded`.** The counter in the record is what was
  received over the wire, including pieces that later failed verification (BEP 3 makes the point);
  `left` is computed from the bitfield.
