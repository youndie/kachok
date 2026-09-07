---
id: B-03
title: "Bencode encoder and decoder in common code"
status: done
priority: P0
size: S/M
stage: m1-metainfo
epic: feature-metainfo
---

# B-03 — Bencode encoder and decoder in common code

Everything the client reads from the outside world — `.torrent` files, tracker responses, the
extension handshake, DHT — is bencode (BEP 3). The decoder is the first piece of common code and
the one every later test fixture is built with.

- **The decision and its reason.** A decoder over a byte source that returns the **byte range** of
  the `info` dictionary alongside the decoded tree. BEP 3 requires the info hash to be computed
  over the original bytes ("clients must … extract the substring directly. They must not perform a
  decode-encode roundtrip"), so an encoder-based hash is wrong by specification on any file whose
  encoding is not canonical.
- Rejected: a general-purpose serialization plugin. Bencode has four types and one ordering rule;
  a library would add a dependency to the run-time image for sixty lines of code.
- Not covered: streaming decode of multi-megabyte metainfo — a `.torrent` is read fully into
  memory; the largest ones are a few megabytes.

- AC **met 2026-09-05** (`BencodeTest`, 8 tests): round-trips the BEP 3 examples (`i-3e`, `4:spam`, `l4:spami42ee`, `d3:cow3:moo4:spam4:eggse`);
  rejects a leading zero, a negative zero and a truncated string with an error naming the offset;
  returns the exact byte range of `info` for a fixture whose dictionary keys are deliberately not
  sorted.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/bencode/`.

**Closed 2026-09-05.** Two findings worth keeping:

* **Unsorted dictionary keys are decoded, not refused.** BEP 3 says keys should be sorted; files
  that break the rule circulate and every other client reads them. Refusing them would buy
  nothing, and what protects the info hash from their existence is the byte range, not strictness.
  `BDictionary.valueRanges` records the inclusive source range of *every* value in *every*
  dictionary, so B-04 needs no special case for `info`.
* **The fixture asserts its own premise.** `unsortedKeysDecodeAndKeepTheirSourceRange` also asserts
  that the canonical re-encoding *differs* from the source bytes. Without that line the test would
  keep passing if the encoder stopped sorting keys, and would then be proving nothing.
