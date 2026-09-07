---
id: B-45
title: "Serve the info dictionary to peers that ask (BEP 9)"
status: done
priority: P3
size: S
stage: m8-extensions
epic: feature-metainfo
blocked_by: [B-36]
---

# B-45 — Serve the info dictionary to peers that ask (BEP 9)

[B-36](B-36-ut-metadata-and-magnets.md) made magnets downloadable and left the other half out, as
it said it would: this client asks peers for the info dictionary and offers nobody its own. A peer
that added the same magnet and reached this client gets no help from it — and a client that took a
torrent from the swarm and gives none back is the free rider BEP 9 exists to avoid.

- **The decision and its reason.** Offer `ut_metadata` in the handshake with `metadata_size`, and
  answer a `request` with the block or with `reject`. The bytes are already in the session: the
  info dictionary is the range `MetainfoParser` recorded when it computed the info hash, so serving
  it is a slice and not a re-encode — re-encoding would produce a *different* dictionary for any
  torrent whose keys were not canonically sorted, and therefore a different hash.
- Rejected: serving only after the download completes. The dictionary is complete from the moment
  the torrent is opened; the pieces are what is missing.
- Not covered: rate-limiting metadata requests separately from the upload budget.

- AC: a fake peer sends `request` for each block of a torrent this client has, reassembles them,
  and the SHA-1 equals this client's info hash; a request for a block past the end is `reject`ed.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/metainfo/Metainfo.kt`.

**Done.** `ut_metadata` is offered with `metadata_size` — without the size a peer knows the
extension exists and not how much to ask for, which is the same as it not being offered — and a
`request` is answered with the block or with `reject`. `Metainfo` now keeps `infoBytes`, the slice
the parser hashed, so serving is a copy of the original and never a re-encode.

**It goes through the upload budget**, which the item left open by saying only that a *separate*
limit was not covered. Not metering it at all would let a peer ask for the same block a thousand
times and walk around the rate limit; BEP 9's `reject` is exactly the refusal for that, so a block
the budget cannot pay for is refused in the protocol's own words.

Offered for private torrents too. BEP 27 names PEX, DHT and local discovery, and metadata exchange
is not on that list — every peer of a private torrent got the file from the same tracker this
client did, so there is nothing to withhold.

Beside the item: the ids this client publishes for `ut_pex` and `ut_metadata` now live in one place
(`ExtensionHandshake.ID_UT_*`). Two halves of this codebase publish them — the session, and the
metadata fetch that runs before a session exists — and they have to agree.

The end-to-end test of [B-10](B-10-extension-protocol-handshake.md) asserts the *exact* set this
client offers. That assertion has now caught both extensions being added; "contains `ut_pex`"
would have caught neither.
