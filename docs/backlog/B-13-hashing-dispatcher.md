---
id: B-13
title: "Whole-piece SHA-1 on a bounded dispatcher with one MessageDigest per thread"
status: done
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

- AC **met 2026-09-05** (`MessageDigestPieceHasherTest`, 4 tests): the hash of a fixture piece equals the metainfo's; hashing 64 pieces concurrently never
  runs more than `cores` at once (asserted with a counter); the digest is thread-confined
  (a test with a `ThreadLocal` sentinel).
- Anchors: `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/hash/`.

**Closed 2026-09-05.** The item's own words were wrong about the mechanism, and that is the finding:

* **"One `MessageDigest` per thread, reused" reuses nothing here.** A thread-local digest is per
  *virtual* thread, and a virtual thread is created per task, so sixty-four pieces would mean
  sixty-four digests — the allocation the reuse was meant to avoid. What the design actually wants
  is one digest per unit of *parallelism*, and since `limitedParallelism(cores)` already bounds
  that, a channel holding exactly that many digests is the whole pool. Asserted:
  `digestsAreReusedRatherThanCreatedPerPiece` puts 64 pieces through 4 digests.
* **Hashing must not consume the blocks.** The writer still has to write those very bytes, so the
  hasher updates from a `duplicate()` of each buffer. Without it the first write after a hash would
  write nothing and the piece would be silently lost — the kind of bug that looks like a network
  problem.
* **The JVM half of a `Block` is named.** `JvmBlock` exposes the `ByteBuffer` that `Block`
  deliberately does not, so common code still cannot copy a block and a test can supply one
  without a buffer pool.

The bound is checked by counting concurrent `engineUpdate` calls rather than by trusting
`limitedParallelism`, because the claim being made is about this hasher, not about the library.
