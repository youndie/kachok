---
id: B-67
title: "Per-file progress and choosing which files to fetch"
status: done
priority: P2
size: L
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-54]
---

# B-67 — Per-file progress and choosing which files to fetch

Two screens are waiting on the same engine change. The details panel's *Files* tab says so in
words; the add dialog draws the file list with checkboxes that do not respond and a `planned` badge
over them.

- **The decision this needs.** What an unwanted file does to the piece picker. A torrent's pieces
  do not respect file boundaries: the piece that straddles a wanted and an unwanted file has to be
  fetched anyway, and the design's own note says the panel must say so rather than showing a total
  that never completes.
- Rejected in advance: deleting the unwanted files after downloading them. It is simpler, it is what
  some clients do, and it spends the bandwidth the setting exists to save.
- Not covered: changing the selection while a torrent runs, which needs the picker to give back
  pieces it has already started.

## The decision, taken

**A piece is skipped only when *every* byte of it belongs to an unwanted file.** The swarm serves
pieces, not files: a piece straddling a wanted and an unwanted file has to be fetched to get the
wanted half. `unwantedPieces` is written as "start from all skipped, clear every piece a wanted file
touches", which is the straddling rule stated the one way it cannot be got wrong by a byte. The
item's rejected alternative — fetch everything and delete the rest — arrives at the same place from
the other side: some of those bytes are paid for either way, and this pays for the fewest.

**Progress is derived from the pieces, never counted separately**, and it is a percentage *of the
file*. Counting whole pieces makes a 700-byte file complete the moment its neighbour's piece lands;
every row would read 100% while the torrent was a third done, which no golden catches because a
picture full of hundreds matches itself.

**Completeness is counted, not derived.** `have.cardinality + skipped >= size` is true after a
restore that found skipped pieces already on the disk, while wanted pieces are still missing —
`wantedHave` against `wantedPieces` is not.

## What was found on the way

- **The add dialog's file list showed four rows of nine and could not be scrolled.** The box is
  `heightIn(max = …)` and the fifth file was drawn outside it with nothing to reach it by. Found by
  the test that ticks all nine and heard from four.
- **The design's own two numbers disagree.** Its file list sums to 3.61 GiB under a header saying
  3.70 GiB. The summary line adds up the rows the dialog is showing, because that is the number
  that has to change when one is unticked.

## Not covered, and why it matters

**The choice does not survive a restart.** There is nowhere to put it: the resume record vouches for
pieces, and there is no list of torrents to reopen either
([B-71](B-71-settings-that-survive-a-restart.md) says so). A restarted client re-fetches what it
skipped — consistent with a client that does not yet reopen its torrents at all, and a thing to fix
when it does. Changing the selection while a torrent runs is the item's own not-covered case and
needs the picker to give back pieces it has started.

- AC: a file unticked in the add dialog is not requested except where its pieces straddle a wanted
  one; the *Files* tab lists every file with its own progress and says which are skipped.
  **Automated:** `engine/src/commonTest/.../storage/UnwantedPiecesTest.kt` (the straddle, the file
  smaller than a piece, the zero-length file), `.../storage/FileProgressTest.kt` (the straddling
  piece split by bytes, the short last piece, the parts adding up to the whole),
  `.../picker/PiecePickerTest.kt` (never asked for by any route, complete means every *wanted*
  piece, skipped pieces already on the disk do not finish the torrent),
  `SessionTest.anUnwantedFileIsNeverRequested` for the seam,
  `ui/src/desktopTest/.../details/FilesTabTest.kt`, `.../session/AddFromTest.kt` and
  `WiringTest.everyFileTickInTheAddDialogLeavesTheWindow`. Checked by hand: the live window's
  *Files* tab against a running torrent.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/picker/PiecePicker.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/storage/PieceLayout.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/details/DetailsPanel.kt`.
