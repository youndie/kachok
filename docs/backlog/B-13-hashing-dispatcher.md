---
id: B-13
title: "Whole-piece SHA-1 on a bounded dispatcher with one MessageDigest per thread"
status: open
priority: P0
size: S
stage: m3-storage
epic: feature-download
blocked_by: [B-08]
---

# B-13 — Whole-piece SHA-1 on a bounded dispatcher with one MessageDigest per thread

Hashing is the only CPU-bound work in the engine and it must neither block a peer's reader nor
take every carrier.

- **The decision and its reason.** `limitedParallelism(cores)` on the root dispatcher
  ([../research/research-architecture.md](../research/research-architecture.md) D1); `MessageDigest.getInstance("SHA-1")` is created per hashing thread and reused,
  because the JDK's SHA-1 is intrinsified on this hardware (research §1.1) and the object is the
  only allocation left. Hashing takes the piece's buffers, so no copy is made.
- Rejected: hashing on the writer coroutine. It serialises hashing behind disk writes.
- Not covered: SHA-256 for v2 ([B-37](B-37-v2-and-hybrid-torrents.md)); start-up verification
  ([B-24](B-24-startup-verification-of-existing-data.md)).

- AC: the hash of a fixture piece equals the metainfo's; hashing 64 pieces concurrently never
  runs more than `cores` at once (asserted with a counter); the digest is thread-confined
  (a test with a `ThreadLocal` sentinel).
- Anchors: `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/hash/`.
