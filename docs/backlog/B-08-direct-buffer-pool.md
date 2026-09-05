---
id: B-08
title: "A capped pool of 16 KiB direct ByteBuffers"
status: done
priority: P0
size: S
stage: m2-wire
epic: feature-download
---

# B-08 — A capped pool of 16 KiB direct ByteBuffers

The single most important structure on the hot path ([../research/research-architecture.md](../research/research-architecture.md) D3): every block in flight is one
direct buffer from this pool, from the socket read to the gathering write.

- **The decision and its reason.** One size (16 KiB, the protocol's block), a hard cap, and
  **blocking acquisition** — a peer that cannot get a buffer waits, which turns the cap into TCP
  back-pressure without any other flow control. Buffers are never freed; the pool grows to its cap
  and stays.
- Rejected: size classes. There is exactly one size; the last block of a torrent is a slice.
- Not covered: the cap's default value — measured in
  [B-26](B-26-jfr-baseline-of-the-hot-path.md); until then it is a constant with a comment.

- AC **met 2026-09-05** (`BufferPoolTest`, 7 tests): `acquire`/`release` round-trip returns the same buffer cleared; acquiring past the cap
  suspends until a release; the pool reports outstanding buffers for the session state.
- Anchors: `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/io/`.

**Closed 2026-09-05.** Two additions to the plan, both about mistakes rather than performance:

* **A buffer is handed out as a `PooledBuffer` handle, not as a bare `ByteBuffer`.** Releasing the
  same buffer twice would put it in two peers' hands, and the second peer would overwrite the
  first one's block on its way to the disk — corruption with no stack trace and no reproduction.
  The handle carries a flag, so the second release throws where the mistake is.
* **Allocation is lazy and the cap is a ceiling, not a reservation.** A session with three peers
  commits no off-heap memory it is not using; once allocated, a buffer is never freed, because
  direct buffers are expensive to allocate and this pool's whole point is that the hot path does
  neither.

`tryAcquire` exists for the picker (research Risk 2): it needs to know how many blocks it may have
in flight without suspending to find out.
