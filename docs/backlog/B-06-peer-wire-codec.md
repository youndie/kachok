---
id: B-06
title: "Peer wire codec: handshake, message ids, in-place piece and request"
status: done
priority: P0
size: M
stage: m2-wire
epic: feature-download
---

# B-06 — Peer wire codec: handshake, message ids, in-place piece and request

The codec is the part of the engine whose correctness is decidable from BEP 3 alone, and every
feature scenario is phrased in its terms. It has to be written before the transport so that the
transport is tested against a codec rather than the other way round.

- **The decision and its reason.** Two shapes, on purpose. `piece` (`7`) and `request` (`6`) —
  the hot messages — are parsed **in place**: index, begin and length are read from the buffer and
  the payload stays where it is; no object is allocated per block. The rare messages (`0`–`5`, `8`,
  `20`) are a sealed `Message` hierarchy, because an object per `have` costs nothing and reads
  well. Dispatch is a `when` over the id byte with constant branches ([../research/research-architecture.md](../research/research-architecture.md) §1.4).
- Rejected: one sealed hierarchy for everything. At 16 KiB per block a 50 MB/s download is 3 200
  `Piece` objects a second — small, but the whole point of the buffer pool is that the hot path
  allocates nothing.
- Not covered: the extension protocol's payloads ([B-10](B-10-extension-protocol-handshake.md)),
  the fast extension ([B-33](B-33-fast-extension.md)).

- AC **met 2026-09-05** (`PeerWireTest`, 17 tests): the 68-byte handshake is produced and parsed byte-exactly (`19`, `BitTorrent protocol`,
  8 reserved, 20 + 20); a zero-length frame is a keep-alive; every id `0`–`8` round-trips; a
  `request` for more than 16 KiB is rejected before it is sent; a truncated frame is an error and
  not a partial message.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/wire/`.

**Closed 2026-09-05.** Three decisions the item left open, and one it got slightly wrong:

* **`Message.Piece` carries offsets, not bytes.** It is `(piece, begin, blockFrom, blockLength)`
  pointing into the caller's frame, so decoding a block copies nothing. A `ByteArray` field there
  would have defeated the buffer pool before the transport had a chance at it.
* **There is no `encode` for a piece, on purpose.** `encodePieceHeader` writes the thirteen bytes
  and the block follows straight from the file (B-20). An `encode(Piece, block)` overload would
  have invited a copy of every byte this client uploads; calling `encode` on a `Piece` throws and
  says so.
* **The request size is refused at the point of writing.** BEP 3 says peers close connections over
  a request larger than 16 KiB, so `encode(Request)` rejects it: a request that cannot be built
  cannot be sent, which is stronger than a check at the point of sending.
* **The item said the sealed hierarchy covers ids `0`–`5`, `8` and `20`.** It covers `0`–`8` and
  `20`: `request` and `cancel` are ordinary objects too. Only `piece` is special, because only
  `piece` is on the hot path — the asymmetry is about block size, not about how often a message
  arrives.

An unknown identifier is an error rather than a message to ignore. A peer should not send what the
handshake did not negotiate, and ignoring unknown frames would turn a framing bug of ours into
"some messages are quietly dropped". [B-33](B-33-fast-extension.md) adds the fast extension's
identifiers when this client starts advertising them.
