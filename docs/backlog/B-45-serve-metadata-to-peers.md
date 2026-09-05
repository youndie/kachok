---
id: B-45
title: "Serve the info dictionary to peers that ask (BEP 9)"
status: open
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
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/session/Session.kt`,
  `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/metainfo/Metainfo.kt`.
