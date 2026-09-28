---
id: B-132
title: "left goes negative when files are skipped"
status: done
priority: P2
size: S
stage: phase-2-ui
epic: feature-ui
blocked_by: []
---

# B-132 — `left` goes negative when files are skipped

A 42.3 GiB torrent of ten files, eight of them set to *skip* and the two wanted ones at 100 %,
reported over MCP (`torrent_status`) as `seeding … downloaded 7.2 GiB, left -172519321 B` — and
the same number is BEP 3's `left` in the announce. Reported as
[youndie/kachok#55](https://github.com/youndie/kachok/issues/55).

`left` was the wanted **files'** length (`wantedBytes`) minus the verified **pieces'** length, two
different units. A piece on a file boundary also holds bytes of the neighbouring file, a skipped one
included, so every boundary piece of a wanted file took more off than that file owed. Start-up hid
it behind `coerceAtLeast(0)`; the per-piece decrement in `consumeOutcomes` had no clamp.

Found on the way: `Session.applyPriorities` returned early when nothing was skipped and nothing
raised. That is harmless at start-up and wrong on a running torrent: taking the last file off
*skip* never reached the picker, which kept the old skip set — the file read as wanted in the Files
tab and was still never asked for.

- **The decision and its reason.** `left` is the length of the wanted pieces not yet verified,
  counted by `PiecePicker` beside `wantedHave`. It is the unit everything subtracted from it is
  measured in, and "wanted piece" is the same straddling rule the skip set already uses, so `left`
  reaches 0 exactly when `isComplete` does and never goes below. A priority change recounts it, so a
  file taken off *skip* is owed again and one put on it stops being owed, without subtracting a
  running `downloaded` that includes pieces of files nobody wants any more.
- **The alternative that was rejected.** Keeping file bytes and clamping every assignment at zero.
  It hides the sign and keeps the error: with a boundary piece verified, `left` is short by the
  neighbour's bytes long before it would go negative, and the tracker is told the torrent is
  further along than it is.
- `applyPriorities` hands both sets to the picker every time, empty ones included.
- `wantedBytes` had no caller left and is gone.
- Not covered: `downloaded` still counts whole verified pieces, including bytes of skipped
  neighbours. That is what the client did download, which is what the field means.

- AC: a torrent with skipped files finishes its wanted files at `left == 0`, not below; a file
  taken off *skip* is counted in `left` again and asked for. **Met.**
  **Automated:** `engine/src/commonTest/.../picker/PiecePickerTest.kt` —
  `leftCountsTheWantedPiecesAndNotTheWantedFiles`, `leftFollowsTheSkipSetBothWays`;
  `engine/src/commonTest/.../session/SessionTest.kt` — `aFileTakenOffSkipIsOwedAgain`.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/picker/PiecePicker.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/storage/UnwantedPieces.kt`.
