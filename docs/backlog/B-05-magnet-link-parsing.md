---
id: B-05
title: "Parse magnet links into an info hash and tracker list"
status: done
priority: P2
size: S
stage: m1-metainfo
epic: feature-metainfo
---

# B-05 — Parse magnet links into an info hash and tracker list

`magnet:?xt=urn:btih:<hex or base32>&dn=…&tr=…` carries an info hash and, optionally, trackers.
Parsing it is small and pure; **using** it needs the metadata exchange
([B-36](B-36-ut-metadata-and-magnets.md)), which is why this item is the parser only.

- **The decision and its reason.** Parse into the same `InfoHash` plus a tracker list; both the
  40-character hex and the 32-character base32 forms, because both are in circulation.
- Rejected: folding this into the metainfo parser. A magnet has no `info` dictionary; a `Metainfo`
  built from one would be a `Metainfo` with every field but the hash unknown.
- Not covered: `xs`/`as` web seeds, `x.pe` peer hints.

- AC **met 2026-09-05** (`MagnetParserTest`, 7 tests): both hash encodings decode to the same 20 bytes; a link with no `xt` is rejected; `tr`
  values are URL-decoded in order.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/metainfo/`.

**Closed 2026-09-05.** Two rules the item did not state, decided in the code and asserted in tests:

* **An unknown parameter is ignored, not refused.** A magnet link is a URI that other tools append
  their own keys to (`xl`, `ws`, `x.pe`); refusing one would break links that work everywhere else.
* **`+` is left alone when percent-decoding.** Reading it as a space is an HTML form convention,
  and a `+` inside a tracker URL is a literal plus far more often than it is a space.
