---
id: B-146
title: "On Windows a torrent's files are created sparse, so a write deep into a fresh file costs that write"
status: done
priority: P1
size: S
stage: m3-storage
blocked_by: []
---

# B-146 — Files are sparse on Windows

Seen on a Windows machine with an external USB SSD: three fresh single-file torrents of 16–19 GiB
showed 0 pieces for minutes — one of them with the same peers, requests and rate in every snapshot
for 40 s — while smaller torrents beside them downloaded at 3–6 MiB/s. The client was writing
~14 MB/s and the disk ~99 MB/s, `% Disk Time` 494, and the writer's threads were inside
`FileDispatcherImpl.writev0`. Reported as [youndie/kachok#91](https://github.com/youndie/kachok/issues/91).

- **The cause.** `FileSet.open` sized a fresh file with `RandomAccessFile.setLength` and then
  opened it with `CREATE` and `SPARSE`. The JDK sets the sparse flag on Windows only in an open that
  creates the file with `CREATE_NEW` (`sun/nio/fs/WindowsChannelFactory`), so the option never
  applied, and `fsutil sparse queryflag` said so of every file. An NTFS file that is sized but not
  sparse has a valid data length of zero; a write at offset X makes the filesystem write zeros up to
  X first, synchronously. Rarest first puts the first pieces anywhere, so one of the first writes
  into a fresh large file zero-fills most of it.
- **The decision and its reason.** A fresh file is created empty with `CREATE_NEW`, `WRITE` and
  `SPARSE`, then sized. A file that appears between the check and the create is kept as an existing
  one would be. On Linux and macOS nothing changes: the option is dropped there and `setLength`
  already leaves a hole.
- **Measured** (`SparseProbe`, a one-off outside the repository): a 16 KiB write at the end of a
  fresh 4 GiB file, NVMe disk — 1 872 ms the old way, 6 ms the new.
- The research said `SPARSE` "matters on Windows only"; it is amended at that point (§1.3a, second
  correction) rather than rewritten.

- Not covered: files created before this stay non-sparse; their first deep writes still zero-fill,
  once per file.

- AC: on Windows a fresh file carries `FILE_ATTRIBUTE_SPARSE_FILE` and its full length. **Met.**
  **Automated:** `engine/src/jvmTest/.../storage/FileSetTest.kt` — `aFreshFileIsSparseOnWindows`
  (Windows only; it fails with the create removed, measured on this machine) beside the POSIX
  `aFreshFileIsSparseRatherThanPreallocated`.
- Anchors: `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/storage/FileSet.kt`.
