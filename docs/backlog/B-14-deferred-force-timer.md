---
id: B-14
title: "force() on a timer and at close, not per piece"
status: open
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

- AC: with a fake channel, ten pieces written within the interval produce one `force`; close
  produces one more.
- Anchors: `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/storage/`, `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/session/`.
