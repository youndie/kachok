---
id: B-60
title: "Two torrents saving to the same file, and nothing that notices"
status: done
priority: P1
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-54]
---

# B-60 — Two torrents saving to the same file, and nothing that notices

`TorrentSet.add` refuses a torrent this set already has, **by info hash**. Two *different* torrents
whose metainfo names the same file, added to the same directory, are accepted and both open a
`FileSet` on the same path — and both write a resume record to `<name>.resume`.

Found by driving the window, not by any test: two fixtures called `payload.bin`, added to one
folder. The run did no damage, and only because the two happened to share a prefix — the swarm's
generator writes `(i * 31 and 0xFF)` for both sizes, so the shorter one's pieces verified against
bytes the longer one had already written. A different pair would have interleaved two downloads
into one file and neither would have hashed.

The record is the sharper half: one `payload.bin.resume` for two torrents. Whichever writes last
wins, and the loser's record is refused on the next start for the piece count — which is the
resume path doing exactly the right thing with a file it should never have been handed.

- **The decision this needs.** What to do about it. Refusing the second is simplest and wrong for
  the case where somebody *wants* two versions side by side. Offering a different directory in the
  add dialog is what the dialog is for and needs a check to offer it from. Suffixing silently is
  the option that produces `payload (1).bin` and a resume record nobody can match to a torrent.
- Rejected in advance: comparing file *names*. Two torrents can name a hundred files each and
  collide on one of them; the check is over the paths a `FileSet` would open, which is what
  `TorrentSet` can compute before it opens anything.
- Not covered: the same torrent added twice to two directories, which is legal and is a different
  question about the resume record's name.

## The decision, taken

**Both branches of the AC, because they are one gesture.** `TorrentSet.add` refuses, naming the path
and the torrent that owns it — a caller that never asks is still safe. And `collisionWith` answers
the same question *without* adding anything, so the add dialog can refuse before the button: it
greys *Add* and says `payload.bin here already belongs to <name> — choose another folder`, with
*Browse…* two rows above it. That is the redirect the item wanted, and it costs nothing to somebody
who does want two versions side by side: they pick a different folder and it goes through.

The check is over the paths `FileSet` would open, not over names, and it asks `FileSet` for them —
`pathsIn` is now shared with `open` rather than repeated beside it, because a second copy of the
single-file rule is a second chance to answer about the wrong paths.

**The resume record carries the info hash.** `payload.bin.2b3a91c4.resume`, name and eight hex. The
name is kept because a directory of records nobody can read is its own problem; the hash is what
makes a record one torrent's. This was the sharper half of the defect: one file for two torrents,
whichever saved last winning, and the loser's record refused on the next start for the piece
count — the resume path doing exactly the right thing with a file it should never have been handed.

Existing records are not migrated: they are simply not found, and the torrent re-verifies once.

- AC: adding a torrent whose files would land on a path another running torrent already owns is
  refused or redirected, and never silently accepted; the resume record of one torrent can never be
  read by another.
  **Automated:** `engine/src/jvmTest/.../runtime/TorrentSetPathsTest.kt` — refused with both names
  in the message, askable before adding, not a collision in another directory, two records for two
  torrents of one name, and the paths judged being the ones `FileSet` opens. The CLI's
  `ShutdownTest` matches the record by shape now, which is what caught the rename.
- Anchors: `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/runtime/TorrentSet.kt`,
  `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/storage/FileSet.kt`,
  `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/resume/`.
