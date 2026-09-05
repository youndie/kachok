---
id: B-08
title: "A capped pool of 16 KiB direct ByteBuffers"
status: open
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

- AC: `acquire`/`release` round-trip returns the same buffer cleared; acquiring past the cap
  suspends until a release; the pool reports outstanding buffers for the session state.
- Anchors: `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/io/`.
