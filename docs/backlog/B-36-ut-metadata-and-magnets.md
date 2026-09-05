---
id: B-36
title: "Metadata exchange (BEP 9): make magnet links downloadable"
status: done
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
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/wire/MetadataMessage.kt`,
  `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/metainfo/MetadataAssembly.kt`,
  `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/metainfo/MetadataFetcher.kt`.

**Done.** `kachok download magnet:?xt=urn:btih:…` works: the link's trackers give peers, BEP 10
gives the ids, BEP 9 gives the dictionary in 16 KiB blocks, and the assembled bytes go through the
*same* `MetainfoParser` a `.torrent` file does — so a magnet download and a file download differ in
where the bytes came from and in nothing after that. The test asserts the fetched `Metainfo` equals
the file's field by field, on a fixture whose metadata is deliberately more than one block: a
one-block fixture would let a fetcher that asked for block 0 and stopped pass.

**The info hash is the only thing that makes this safe**, and the code says so where it matters:
the bytes are hashed before anything looks at them, and a mismatch discards the whole assembly
rather than the blocks that "look wrong" — a single SHA-1 over the whole cannot name the peer that
lied. `metadata_size` gets the same treatment: it arrives from a stranger and is what gets
allocated, so it is refused above four megabytes.

A separate `MetadataFetcher` and not a mode of `Session`, because the session needs a `Metainfo` to
build a picker, a storage layout and a hasher, and the whole point here is that none of that exists
yet. Its state is confined by a channel rather than by `limitedParallelism(1)` — the same guarantee,
said more plainly at this size.

Two things the item did not mention. `Bencode.decodePrefix`: BEP 9 puts the dictionary and the
block in one message with no length and no separator between them, so the decoder's own position is
the only thing that knows where one ends. And the announce sends a **non-zero** `left` — the
torrent's length is in the metadata being fetched, and zero would announce this client as a seed.

**Not covered, as the item said:** serving metadata to others. This client asks for `ut_metadata`
and does not offer it, so a peer that wants the dictionary from it gets nothing — which is what
[B-45](B-45-serve-metadata-to-peers.md) is for.
