---
id: B-04
title: "Metainfo parser and the v1 info hash"
status: open
priority: P0
size: M
stage: m1-metainfo
epic: feature-metainfo
blocked_by: [B-03]
---

# B-04 — Metainfo parser and the v1 info hash

A `.torrent` becomes a `Metainfo`: announce URL(s), piece length, the list of files with their
lengths and paths, the concatenated SHA-1 piece hashes, and the `InfoHash` over the raw `info`
bytes. Every other milestone consumes this type and nothing else from the file.

- **The decision and its reason.** Piece hashes stay one `ByteArray` of `20 × pieces` with an
  accessor by `PieceIndex`, not a `List<ByteArray>` — a 100 000-piece torrent is two megabytes as
  one array and two megabytes plus 100 000 object headers as a list ([../research/research-architecture.md](../research/research-architecture.md), the brief's
  "primitive arrays" rule). Single-file and multi-file torrents are one representation: a list of
  files, of length one in the single-file case.
- Rejected: eager validation of every announce URL. A torrent with one dead tracker in a list of
  ten must still load; the tracker layer reports failures per tracker.
- Not covered: v2 and hybrid metainfo ([B-37](B-37-v2-and-hybrid-torrents.md)); magnet links
  ([B-05](B-05-magnet-link-parsing.md)).

- AC: the info hash of a fixture `.torrent` equals the one printed by an independent tool
  (recorded in the test, with the tool named); `pieces` length not a multiple of 20 is an error;
  a multi-file torrent's total length equals the sum of its files.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/metainfo/`, `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/Ids.kt`.
