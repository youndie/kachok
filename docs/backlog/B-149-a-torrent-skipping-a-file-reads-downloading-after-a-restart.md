---
id: B-149
title: "A torrent with every wanted file on the disk read 'downloading' after a restart when it skipped a file"
status: done
priority: P3
size: S
stage: m3-storage
blocked_by: []
---

# B-149 — A torrent skipping a file reads *downloading* after a restart

Seen in the MCP engine's list: a torrent of one video and two skipped audio tracks, its video at
100 %, read `downloading 76%` with `left 0 B` in `torrent_status`, no peers and nothing asked for —
for as long as the process ran. It had read *seeding* before the restart.

- **The cause.** The start-up pass published `isComplete` from the verified bitfield, which is
  complete only when every piece is, while `left` beside it came from the picker, which counts the
  wanted pieces only. During a download the picker's answer is the one published as each piece
  lands, so the torrent turned *seeding* when its last wanted piece came; after a restart no piece
  comes, and the bitfield's answer stood.
- **The decision and its reason.** Start-up publishes `picker.isComplete`, the same answer every
  other path publishes, after `picker.restore` has applied the verified pieces and the priorities.
  Done means having every wanted piece; the percentage still counts the whole torrent, which is what
  it says it counts.

- AC: a torrent whose wanted files are all on the disk is complete when it starts, with a file
  skipped. **Met.** **Automated:** `engine/src/commonTest/.../session/SessionTest.kt` —
  `aTorrentWithEveryWantedFileIsCompleteAtStartUp` (fails without the change, measured).
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`.
