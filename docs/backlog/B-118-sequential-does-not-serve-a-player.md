---
id: B-118
title: "Sequential downloads a file front to back, and a player needs its end too"
status: open
priority: P2
size: S
stage: phase-2-ui
epic: feature-ui
blocked_by: []
---

# B-118 — Sequential does not serve a player

The reason anybody ticks *sequential* is to watch the file while it arrives, and this client's
sequential order cannot do that for the container everybody actually has. An MP4 written by most
tools puts its `moov` atom — the index that says where every frame is — **at the end of the file**,
and a player that cannot read it will not start; AVI's index (`idx1`) is at the end too, and an MKV's
`Cues` element usually is. Strict lowest-piece-first
([B-65](B-65-sequential-download.md)) reaches that index last, so the tick that exists for playback
delivers a file that plays only once it is whole — which is the case the tick was meant to avoid.

The owner hit it directly: a torrent set to sequential, the first file apparently finished and the
second under way, and the file would not open in a player.

- **The decision and its reason.** Under `sequential`, the **first and last piece of every wanted
  file are offered before anything else**, in ascending order, and the strict lowest-first order
  decides the rest. The pieces at a file's two ends are what a demuxer reads before it can play a
  byte — header at the front, index at the back — so they are the smallest set that turns "in order"
  into "playable while it downloads". No new number is invented: the pool is the file boundaries the
  torrent already declares, not a window of N pieces that nobody here has a player to measure.
- **The alternative that was rejected.** A second checkbox, as qBittorrent has ("download first and
  last pieces first" beside "sequential download"). Two ticks for one intention, and the second one
  is only ever useful with the first: somebody who wants the head and the tail out of order and the
  middle rarest-first is asking for a worse download with no gain. Sequential is the tick that means
  "I am going to watch this", and this is what that means.
- **The cost, and why it is bounded.** At most two pieces per file arrive before the body they
  belong to. In a torrent with many files they are usually the *same* pieces — several small files
  to a piece — so the deduplicated set is far smaller than twice the file count; in one with a few
  large files, two pieces each is exactly the price of being able to play them.
- Not covered: telling a player *where the readable prefix ends*, which B-65 also left out. A
  progress bar over the piece map is a second interface and a separate item.
- Not covered: a sequential window ahead of the play head. It still needs a player to measure N
  against, and there still is not one.

- AC: with sequential on, the first and last piece of each wanted file are requested before the
  pieces between them, so an MP4 whose `moov` sits at the end opens in a player while the middle is
  still arriving.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/picker/PiecePicker.kt`,
  `engine/src/commonTest/kotlin/io/github/youndie/kachok/engine/picker/PiecePickerTest.kt`.
