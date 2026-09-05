---
id: B-10
title: "Extension protocol (BEP 10): reserved bit and the handshake dictionary"
status: done
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
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/wire/ExtensionHandshake.kt`,
  `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/session/Session.kt`.

**Done.** The bit is advertised in every handshake the CLI sends and accepts, the dictionary is
exchanged, and the peer's ids are on its `PeerLink` — which is what `ut_pex` and `ut_metadata` will
read. `SessionState.extendedPeers` counts the peers whose handshake arrived, so the seam is
observable rather than only present.

The decoder forgives everything BEP 10 says to forgive and refuses only payload that is not a
dictionary at all: an unknown name, an id that is not a number, a `reqq` that is a string. A
handshake that will not parse costs the peer its extensions and not its connection.

An id of `0` in `m` means the extension is **disabled**, not "send it as message 0" — a reading
that would turn every one of that extension's messages into another handshake.

Beside the item: `ShutdownTest` was a race and this item's end-to-end test exposed it. It waited for
the client's first printed progress line before sending `SIGINT`, and progress is printed on a
timer — with the JIT warmed by the test that now runs before it, the download finished before the
first line appeared and there was nothing left to interrupt. It now waits on the seed's own request
log, which knows blocks are moving before any timer does.
