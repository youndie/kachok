---
id: B-05
title: "Parse magnet links into an info hash and tracker list"
status: open
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

- AC: both hash encodings decode to the same 20 bytes; a link with no `xt` is rejected; `tr`
  values are URL-decoded in order.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/metainfo/`.
