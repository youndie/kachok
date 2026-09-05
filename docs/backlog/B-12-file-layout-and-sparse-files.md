---
id: B-12
title: "Piece-to-file mapping and sparse file creation"
status: done
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

- AC **met 2026-09-05** (`PieceLayoutTest` 8 tests, `FileSetTest` 4 tests): for a fixture with three files of odd lengths, every piece maps to spans whose lengths sum
  to the piece length and whose positions are contiguous per file; the last piece is short; a
  freshly created file reports its full size and near-zero allocated blocks on APFS/ext4
  (`du` versus `ls -l`, asserted in the test on Unix only).
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/storage/`, `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/storage/`.

**Closed 2026-09-05.** The item said "files are opened with `CREATE, READ, WRITE, SPARSE` and never
preallocated", and that turned out to be two claims, one of which was false:

* **Writing one byte past the end of a file is preallocation.** Measured on APFS: a 4 MB file made
  that way occupies 3 908 KB — the same as one written out in full — while `RandomAccessFile
  .setLength` on the same file occupies zero. The first implementation here used the positional
  write and would have written every torrent to disk twice. The table is in the research at §1.3a.
* **The JDK exposes no portable allocated-block count**, so the guard shells out to `du -k`. The
  `unix` attribute view on macOS has `size` and no `blocks`, which is how the first version of the
  test failed with `'blocks' not recognized` rather than with a wrong number.
* **A zero-length file is legal and contributes no span.** It is stepped over by the mapping, and
  a fixture with one in the middle keeps that true.

Two of the mapping tests were wrong before the code was: the arithmetic in the expectations, not
in `PieceLayout`. Worth recording because a test written from the same head as the code is a
second opinion only when it is derived independently — here, from byte offsets counted by hand.
