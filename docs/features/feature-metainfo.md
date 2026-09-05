---
id: feature-metainfo
title: Loading a torrent — metainfo, magnet links and the info hash
type: feature
status: active
owner: unassigned
involved_services:
  - engine
client_entries: []
api: []
tags: [phase-1, bencode, bep-3, bep-9]
---

# Loading a torrent

> **Implemented on 2026-09-05** by [B-03](../backlog/B-03-bencode-codec.md),
> [B-04](../backlog/B-04-metainfo-parser-and-info-hash.md) and
> [B-05](../backlog/B-05-magnet-link-parsing.md), with 25 tests behind the scenarios below. The one
> scenario still labelled *target* is the metadata exchange: it needs the extension protocol and
> belongs to [B-36](../backlog/B-36-ut-metadata-and-magnets.md) in M8.

## 1. Overview

A user hands the client a `.torrent` file or a magnet link. From the file the engine learns what
the torrent *is* — its files, the piece length, one SHA-1 per piece — and computes the identity
everything else is keyed on: the 20-byte **info hash**. From a magnet link it learns only the
identity and, optionally, where to look for peers; the rest arrives later from peers over the
metadata extension.

Everything here is common Kotlin with no I/O: bytes in, a `Metainfo` out. It is the first code in
the engine and the code every test fixture is built with.

## 2. Business rules

* The info hash is the SHA-1 of the **original bytes** of the `info` dictionary as they appear in
  the file — not of a re-encoding. BEP 3: clients "must not perform a decode-encode roundtrip on
  invalid data". The decoder therefore returns the byte range of `info`, and the hash is taken over
  that range.
* Bencode is strict: integers have no leading zeros and no negative zero, strings are
  length-prefixed, dictionary keys are byte strings. Malformed input is an error naming the byte
  offset; there is no lenient mode.
* A single-file torrent and a multi-file torrent are one representation: a list of files, of
  length one in the single-file case, with cumulative offsets.
* `pieces` is a byte string whose length is a multiple of 20; every 20 bytes is one piece's hash,
  by index. The last piece may be shorter than `piece length`; its length is computed from the
  total size, never read from the file.
* A magnet link needs `xt=urn:btih:<hash>` with the hash in 40-character hex or 32-character
  base32; `tr=` entries are trackers in order; `dn=` is a display name and nothing else.
* Metadata fetched from peers (BEP 9) is accepted only if its SHA-1 equals the magnet's info hash.

## 3. Flow

```
.torrent bytes ──▶ bencode decode ──▶ tree + byte range of `info`
                                          │
                                          ├──▶ SHA-1(range) = InfoHash
                                          └──▶ Metainfo(announce list, piece length, files, piece hashes)

magnet:?xt=urn:btih:… ──▶ InfoHash + trackers ──▶ (later) ut_metadata blocks from peers ──▶ same path as above
```

## 4. Code anchors

| Service | Code |
|---|---|
| engine | `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/Ids.kt` — `InfoHash`, the 20-byte check (exists) |
| engine | `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/bencode/` — decoder with byte ranges, encoder (target, B-03) |
| engine | `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/metainfo/` — `Metainfo`, the parser, magnet parsing (target, B-04, B-05) |
| engine | `engine/src/commonTest/kotlin/ru/workinprogress/kachok/engine/` — fixtures: the BEP 3 examples and one real `.torrent` with its hash recorded from an independent tool |

## 5. Scenarios (BDD / test cases)

### Scenario: The BEP 3 bencode examples round-trip
* **Given:** the byte strings `i-3e`, `4:spam`, `l4:spami42ee`, `d3:cow3:moo4:spam4:eggse`.
* **When:** each is decoded and re-encoded.
* **Then:** the output bytes equal the input bytes, and the decoded values are `-3`, `"spam"`,
  `["spam", 42]`, `{"cow": "moo", "spam": "eggs"}`.
* **Automated:** `BencodeTest#bep3ExamplesRoundTrip`

### Scenario: Malformed bencode is refused with an offset
* **Given:** `i03e` (leading zero), `i-0e` (negative zero), `5:spam` (truncated string).
* **When:** each is decoded.
* **Then:** the decoder throws an error whose message contains the byte offset of the defect, and
  no partial value is returned.
* **Automated:** `BencodeTest#malformedInputIsRefusedWithItsOffset`

### Scenario: The info hash is computed over the original bytes
* **Given:** a `.torrent` whose `info` dictionary keys are deliberately written out of sorted
  order, so that a re-encoding would differ from the file.
* **When:** the metainfo is loaded.
* **Then:** the `InfoHash` equals the SHA-1 of the file's `info` byte range, and equals the hash
  an independent tool reports for the same file (the tool and its output are recorded in the test).
* **Automated:** `MetainfoParserTest#infoHashIsComputedOverTheOriginalBytes`

### Scenario: A multi-file torrent maps to one file list with offsets
* **Given:** a torrent with three files of lengths 1000, 1, and 999 bytes and `piece length` 512.
* **When:** the metainfo is loaded.
* **Then:** the total length is 2000, there are 4 pieces, the last piece is 464 bytes long, and the
  cumulative offsets are 0, 1000, 1001.
* **Automated:** `MetainfoParserTest#multiFileTorrentBecomesOneListWithCumulativeOffsets`

### Scenario: A `pieces` string of the wrong length is refused
* **Given:** a `.torrent` whose `pieces` value is 30 bytes long.
* **When:** the metainfo is loaded.
* **Then:** loading fails with an error that names `pieces` and the length 30.
* **Automated:** `MetainfoParserTest#piecesOfTheWrongLengthAreRefused`

### Scenario: Both magnet hash encodings decode to the same identity
* **Given:** the same 20 bytes written as 40 hex characters and as 32 base32 characters in two
  magnet links, each with two `tr=` entries.
* **When:** both links are parsed.
* **Then:** both produce the same `InfoHash`, and the tracker lists are the URL-decoded `tr`
  values in link order.
* **Automated:** `MagnetParserTest#bothHashEncodingsDecodeToTheSameIdentity`

### Scenario: A magnet link without `xt` is refused
* **Given:** `magnet:?dn=only-a-name`.
* **When:** it is parsed.
* **Then:** parsing fails with an error naming `xt`.
* **Automated:** `MagnetParserTest#aLinkWithoutXtIsRefused`

### Scenario: Metadata from a peer must hash to the magnet's identity *(target, B-36)*
* **Given:** a magnet link and a local peer advertising `ut_metadata` with `metadata_size` set.
* **When:** all 16 KiB blocks (16384 bytes each, the last one shorter) are fetched and assembled.
* **Then:** the assembled bytes are accepted only if their SHA-1 equals the link's info hash; if
  not, the peer is dropped and the blocks are discarded.

## 6. Out of scope

* v2 and hybrid torrents — SHA-256 piece layers, the `file tree` — are
  [B-37](../backlog/B-37-v2-and-hybrid-torrents.md), a question, not a plan. A hybrid torrent is
  loaded through its v1 `info` dictionary.
* Web seeds (`url-list`, BEP 19) are parsed and ignored.
* Editing or creating torrents.

## 7. Quirks

* **The info hash is not the hash of "the torrent".** Two `.torrent` files with different tracker
  lists and the same `info` dictionary are the same torrent to every peer and every tracker.
  Documented here because it surprises people who deduplicate by file hash.
* **`left` in the tracker announce is not `total − downloaded`.** BEP 3 says so explicitly: after a
  resume with failed hash checks the two differ. The metainfo provides the total; the session
  provides `left`.
