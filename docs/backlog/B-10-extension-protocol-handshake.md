---
id: B-10
title: "Extension protocol (BEP 10): reserved bit and the handshake dictionary"
status: open
priority: P2
size: S
stage: m2-wire
epic: feature-download
blocked_by: [B-06]
---

# B-10 — Extension protocol (BEP 10): reserved bit and the handshake dictionary

PEX, metadata exchange and every modern extension ride on BEP 10: `reserved[5] & 0x10` in the
handshake and message id `20` carrying a bencoded dictionary.

- **The decision and its reason.** Advertise the bit, exchange the `m` dictionary, and expose the
  peer's extension ids to the session; nothing else. The extensions themselves are their own items.
- Rejected: skipping BEP 10 in phase 1. Magnets ([B-36](B-36-ut-metadata-and-magnets.md)) are
  impossible without it and PEX ([B-34](B-34-peer-exchange.md)) is the cheapest peer source.
- Not covered: `ut_metadata`, `ut_pex` payloads.

- AC: a peer without the bit never receives id `20`; the handshake dictionary round-trips through
  the bencode codec; an unknown extension name is ignored, not an error.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/wire/`.
