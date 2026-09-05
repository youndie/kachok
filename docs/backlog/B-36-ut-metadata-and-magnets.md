---
id: B-36
title: "Metadata exchange (BEP 9): make magnet links downloadable"
status: open
priority: P2
size: M
stage: m8-extensions
epic: feature-metainfo
blocked_by: [B-05, B-10]
---

# B-36 — Metadata exchange (BEP 9): make magnet links downloadable

A magnet link has an info hash and no `info` dictionary; BEP 9 fetches the dictionary from peers
in 16 KiB blocks, and the hash of the result must equal the link's.

- **The decision and its reason.** Request blocks from every peer advertising `ut_metadata`,
  assemble, hash, and only then construct the `Metainfo` — the same parser as a `.torrent`. Peers
  come from trackers in the link or from DHT ([B-35](B-35-dht.md)).
- Rejected: nothing; the protocol is fixed.
- Not covered: serving metadata to others in phase 1 (a seed of a magnet-added torrent should;
  it is a follow-up).

- AC: a magnet for the fixture torrent, with a local peer that has the metadata, produces the
  same `InfoHash` and `Metainfo` as the `.torrent` file.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/wire/`, `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/metainfo/`.
