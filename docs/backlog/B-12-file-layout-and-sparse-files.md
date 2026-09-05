---
id: B-12
title: "Piece-to-file mapping and sparse file creation"
status: open
priority: P0
size: S/M
stage: m3-storage
epic: feature-download
blocked_by: [B-04]
---

# B-12 — Piece-to-file mapping and sparse file creation

A piece is an offset into the concatenation of the torrent's files; the storage has to map a
`(piece, begin, length)` to one or more `(file, position, length)` spans, and create the files
before the first write.

- **The decision and its reason.** Files are opened with `CREATE, READ, WRITE, SPARSE` and never
  preallocated. `SPARSE` is a no-op on Unix and matters on Windows (research §1.1); preallocating
  zeros would write the whole torrent once before downloading it. The mapping is computed from
  `Metainfo` once and kept as primitive arrays of cumulative offsets.
- Rejected: one `FileChannel` per piece write. Channels are opened once per file and kept.
- Not covered: files the user chooses not to download (selective download) — a phase-2 feature.

- AC: for a fixture with three files of odd lengths, every piece maps to spans whose lengths sum
  to the piece length and whose positions are contiguous per file; the last piece is short; a
  freshly created file reports its full size and near-zero allocated blocks on APFS/ext4
  (`du` versus `ls -l`, asserted in the test on Unix only).
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/storage/`, `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/storage/`.
