---
id: B-14
title: "force() on a timer and at close, not per piece"
status: done
priority: P1
size: S
stage: m3-storage
epic: feature-download
blocked_by: [B-11]
---

# B-14 — force() on a timer and at close, not per piece

`FileChannel.force(false)` per piece turns every piece into a synchronous flush; the operating
system's page cache is better at deciding when to write than the client is.

- **The decision and its reason.** One `force()` pass over open channels every N seconds from
  the session timer, and one at close. The price — data in the page cache at a crash — is covered
  by the resume design ([B-23](B-23-atomic-resume-file.md)), which vouches only for pieces that
  were hashed, not for pieces that were flushed.
- Rejected: `force()` per piece. Measured elsewhere as the dominant cost of naive clients; not
  measured here, and it does not need to be — the resume design makes it unnecessary.
- Not covered: the interval's default value (measured in [B-26](B-26-jfr-baseline-of-the-hot-path.md)).

- AC **met 2026-09-05** (`SessionTest#theTimerFlushesOnItsIntervalAndNotPerPiece`): four pieces written within the interval produce one `force`; close
  produces one more.
- Anchors: `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/storage/`, `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/`.

**Closed 2026-09-05.** The mechanism arrived with the session ([B-17](B-17-session-orchestrator.md))
and this item is the guard around it: four pieces written inside the interval cost **no** flush, the
interval costs one, and the clean stop costs one more after the peers are closed.

The ordering in the shutdown is the part worth protecting. `Command.Stop` announces `stopped`,
closes the peers, closes the writer's queue and *then* flushes — so nothing is still arriving when
the data is made durable. A flush before the peers are closed would be a flush of a moving target.

Nothing here measured the cost of the alternative, and nothing needs to: a flush per piece is a
synchronous disk round trip per piece by construction. What the deferral costs is bounded by the
resume record, which vouches only for pieces that were hashed
([B-23](B-23-atomic-resume-file.md)).
