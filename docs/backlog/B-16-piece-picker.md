---
id: B-16
title: "Rarest-first piece picker with strict priority and endgame"
status: open
priority: P0
size: M
stage: m4-download
epic: feature-download
---

# B-16 — Rarest-first piece picker with strict priority and endgame

Which block to request from which peer is the download's throughput and its memory footprint at
once: a picker that starts too many pieces holds too many pool buffers.

- **The decision and its reason.** Availability as an `IntArray` indexed by piece, our bitfield
  as a `BitSet`/`LongArray`; rarest-first among pieces the peer has, with **strict priority** for
  pieces already started (BEP 3) so that pool buffers are released quickly; random first piece;
  endgame — request outstanding blocks from every peer that has them and `cancel` on arrival —
  when every piece has been requested at least once. Requests per peer are capped by a pipeline
  depth in `SessionConfig`.
- Rejected: sequential order as the default. It is a feature for streaming, not the baseline;
  it can be a picker strategy later.
- Not covered: per-file priorities and selective download (phase 2).

- AC: with three fake peers whose bitfields make piece 7 rarest, the first request goes to piece
  7; a started piece is finished before a new one is begun; in endgame a block arriving from one
  peer sends `cancel` to the others; the number of distinct started pieces never exceeds the
  configured bound.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/picker/`.
