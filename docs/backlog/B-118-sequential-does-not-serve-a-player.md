---
id: B-118
title: "Sequential downloads a file front to back, and a player needs its end too"
status: done
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

- **The decision and its reason.** Under `sequential`, **a piece-length of bytes at each end of
  every wanted file is offered before anything else**, in ascending order, and the strict
  lowest-first order decides the rest. A file's two ends are what a demuxer reads before it can play
  a byte — header at the front, index at the back — so they are the smallest set that turns "in
  order" into "playable while it downloads". No new number is invented: the reach is the picker's
  own unit and the pool is the file layout the torrent already declares, not a window of N pieces
  that nobody here has a player to measure.
- **The alternative that was rejected.** A second checkbox, as qBittorrent has ("download first and
  last pieces first" beside "sequential download"). Two ticks for one intention, and the second one
  is only ever useful with the first: somebody who wants the head and the tail out of order and the
  middle rarest-first is asking for a worse download with no gain. Sequential is the tick that means
  "I am going to watch this", and this is what that means.
- **Why a piece-length of bytes and not "the first and last piece", which is what every other
  client does.** Because the first version *was* the last piece, and the stand refuted it. The
  fixture's file ended 19 KB into its last piece while its `moov` atom was 52 KB, so the atom began
  in the piece *before* the last: at 55 % of the torrent, with the last piece in hand, `ffprobe`
  still said `moov atom not found`. A file ends wherever it ends inside a piece, so "the piece
  holding the last byte" is a guarantee that bottoms out at one byte. Asking for a piece-length
  instead costs one piece where the boundary is aligned and two where it is not, and always
  delivers a whole piece of contiguous tail.
- **The cost, and why it is bounded.** At most four pieces per file arrive before the body they
  belong to, and two or three in the usual case. In a torrent with many files they are the *same*
  pieces — several small files to a piece — so the deduplicated set is far smaller than four times
  the file count; in one with a few large files it is exactly the price of being able to play them.
- Not covered: an index **larger than a piece**. Nothing short of parsing the container can know how
  big it is, and a picker that parsed MP4 would be a picker that knows what a file is for. A
  two-hour film whose `moov` runs to megabytes still waits for the sequential front to reach it.
- Not covered: telling a player *where the readable prefix ends*, which B-65 also left out. A
  progress bar over the piece map is a second interface and a separate item.
- Not covered: a sequential window ahead of the play head. It still needs a player to measure N
  against, and there still is not one.

## Driven, with a control

Two 1080p MP4s (15.0 MB and 0.4 MB, `moov` at the end of both, as `ffmpeg` writes them without
`+faststart`) in one multi-file torrent of 59 pieces at 256 KiB, a tracker on loopback, one `kachok`
seeding the complete directory and a second downloading into an empty one, killed after 22 seconds
at **17 of 59 pieces (28 %)** in both runs:

| Run | `ffprobe` on the partial `movie-one.mp4` |
|---|---|
| `--sequential` | `mpeg4 1920x1080`, `aac`, `duration=60.000000`; `ffmpeg -t 5` decodes five seconds of video |
| no flag (the control) | `moov atom not found`, exit 1 |

The control is what makes the first row mean anything: the same client, the same swarm, the same
number of pieces, and the file does not open.

- AC: with sequential on, a piece-length of bytes at each end of each wanted file is requested before
  the pieces between them, so an MP4 whose `moov` sits at the end opens in a player while the middle
  is still arriving. **Met, and driven — see above.**
  **Automated:** `engine/src/commonTest/.../picker/PiecePickerTest.kt` —
  `sequentialAsksForTheEndsOfTheFileAndThenTheOrder`, `sequentialTakesBothEndsOfEveryFileFirst`,
  `theTailCoversAWholePieceLengthWhereverTheFileEnds`, `theEndsOfASkippedFileAreNotFetched`,
  `theEndsAreOnlyRaisedUnderSequential`; `cli/src/test/.../DownloadTest.kt` —
  `theOrderIsOffUnlessTheCommandLineAsksForIt`.
- **`--sequential` on the command line came with this item**, because without it the acceptance
  criterion cannot be exercised at all: the order was reachable only from the window's tick, and a
  window is not something a check can drive.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/picker/PiecePicker.kt`,
  `engine/src/commonTest/kotlin/io/github/youndie/kachok/engine/picker/PiecePickerTest.kt`,
  `cli/src/main/kotlin/io/github/youndie/kachok/cli/Arguments.kt`.
