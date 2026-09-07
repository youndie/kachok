---
id: B-16
title: "Rarest-first piece picker with strict priority and endgame"
status: done
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

- AC **met 2026-09-05** (`PiecePickerTest`, 12 tests): with three fake peers whose bitfields make piece 7 rarest, the first request goes to piece
  7; a started piece is finished before a new one is begun; in endgame a block arriving from one
  peer sends `cancel` to the others; the number of distinct started pieces never exceeds the
  configured bound.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/picker/`.

**Closed 2026-09-05.** One distinction the item did not name, and it took two failing tests to find:

* **Being at the started-piece bound is not endgame.** The first version defined endgame as "every
  piece is had or fully requested", which is never true of a swarm that does not hold every piece:
  a piece nobody has can never be requested. Availability is part of the condition — endgame waits
  for the last *reachable* blocks. Without that, a client whose peers between them hold half the
  torrent would never enter endgame at all.
* **The opposite mistake is worse.** With the bound reached and eight pieces still unstarted,
  duplicating a request would spend bandwidth on bytes already on their way while whole pieces
  wait. The two conditions are tested against each other:
  `beingAtTheStartedPieceBoundIsNotEndgame` and `endgameAsksSeveralPeersAndNamesTheOnesToCancel`
  differ only in whether anything is left to ask for.
* **The picker decides and does not send.** `blockReceived` returns the peers to cancel with rather
  than sending cancels, so the whole class is a pure function of its own state and every rule above
  is testable without a socket.

`Bitfield` came out of this item: a `LongArray` with BEP 3's bit order, refusing a wrong length or
a set spare bit — a peer that disagrees about the spare bits is claiming a piece that does not
exist, which would put an out-of-range index into the availability array.
