---
id: B-60
title: "Two torrents saving to the same file, and nothing that notices"
status: open
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

- AC: adding a torrent whose files would land on a path another running torrent already owns is
  refused or redirected, and never silently accepted; the resume record of one torrent can never be
  read by another.
- Anchors: `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/runtime/TorrentSet.kt`,
  `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/storage/FileSet.kt`,
  `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/resume/`.
