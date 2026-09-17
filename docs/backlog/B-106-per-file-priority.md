---
id: B-106
title: "Per-file priority: which file of a torrent the picker fetches first"
status: done
priority: P3
size: L
stage: phase-2-ui
epic: feature-ui
---

# B-106 — Per-file priority: which file of a torrent the picker fetches first

A multi-file torrent offers two knobs today and neither is the one a person reaches for. The add
dialog lets them untick files ([B-67](B-67-per-file-selection.md)): `unwantedFiles` goes to
`PiecePicker.skip` once, when the torrent is opened, and is fixed from then on. The Files tab lets
them switch on sequential download ([B-65](B-65-sequential-download.md),
[B-89](B-89-sequential-on-a-running-torrent.md)): the *whole* torrent, in piece order. What is
missing is the thing in between — *this file first* — which is what somebody with a season of
episodes and one evening actually wants, and which every mainstream client offers as a priority
per file (skip / normal / high). Without it the choice is "everything in order" or "rarest-first
across all nine files", and the file they wanted tonight finishes when it finishes.

- **The decision and its reason.** A priority tier per file — *skip*, *normal*, *high* — applied
  as a bias in `PiecePicker.next`: pieces of high-priority files are offered before the rest, and
  **rarest-first still decides within a tier**. Rarest-first is what the picker's cost was measured
  on (research §1.2c) and what keeps the swarm healthy; a tier only reorders which pool it draws
  from, so the measured behaviour survives inside each pool. *Skip* is `unwantedFiles` under its
  real name, so one mechanism and not two.
- **Changeable on a running torrent, from the Files tab**, the way sequential already is (B-89):
  a session `Command`, and the picker re-biases without giving back what it has started. Raising a
  file from skip to normal is the same command and is cheap — un-skip its pieces. Lowering a file
  *to* skip while its pieces are in flight is the half B-67 left out and this one leaves out too:
  the pieces already started finish, and the rest are skipped. Said in the panel, not hidden.
- **Persisted beside the torrent, where its two siblings already live.** This bullet first said
  "in the resume record", written without checking [B-81](B-81-the-torrent-list-survives-a-restart.md):
  the window already keeps `unwanted` and `sequential` per torrent in its own stored list, and a
  third decision of the same kind belongs in the same file — one mechanism, not two. The resume
  record stays what it is, a map of verified pieces. The command line, which has no list, takes
  `--high <n>` instead.
- **The boundary piece takes the higher tier.** A piece that straddles a high file and a normal
  one is high; the one that straddles a wanted and a skipped file is fetched anyway — B-67's rule,
  restated for three tiers instead of two.
- Rejected: strict per-file order. It is strict sequential with a smaller scope, and B-65 already
  paid for the argument against it: every peer asks for the same pieces, nobody has anything rare
  to trade, the client that does it finishes last.
- Rejected: leaving it to the person to untick and re-tick files as the download goes. That is a
  priority expressed as a chore, and the picker has no way to know the chore's intent.
- Not covered: priority at piece granularity, or "the readable prefix of this file", which is
  streaming and is B-65's own not-covered.
- Not covered: giving back started pieces when a file drops to skip mid-flight (above).

- AC: on a running multi-file torrent the owner marks one file *high* in the Files tab, and that
  file's own progress row (B-67's) moves ahead of the others from the next requests on — observed
  on a real swarm, not inferred from the picker's unit test. They quit and relaunch and the mark
  is still there. A piece shared between a high file and a normal one is requested with the high
  ones. The picker's test for the bias is mutation-checked: removing the tier order fails it and
  removing rarest-first *within* a tier fails a different one.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/picker/PiecePicker.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/SessionState.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/storage/UnwantedPieces.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/runtime/TorrentRuntime.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/details/DetailsPanel.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/session/StoredTorrents.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/App.kt`,
  `cli/src/main/kotlin/io/github/youndie/kachok/cli/Arguments.kt`.

## Iteration 1 — 2026-09-17: built, and the order read off the disk

**The picker.** `prioritise(skipped, raised)` hands it two pools at once, on a picker in any state.
`rarestUnstarted` became two passes over one function — the raised pool, then the rest — rather
than a fourth term in a comparison: the three rules the class header lists each do a different
job, and a score that blended them would have been the first one nobody could read. Inside a pool
nothing changed: rarest first, or lowest first under sequential. What is started is untouched by
either set, which is what makes the call safe on a running torrent and is the only honest thing to
do with blocks already on their way.

**The seam.** `Command.PrioritiseFile(file, tier)` moves one file; the session keeps two mutable
sets, re-derives the pools through the piece boundaries, republishes `left` and the file list, and
asks every peer for more at once — a raised file is a reason to ask now, not on the next block that
happens to land. The runtime's `prioritise` is its own call and not a field on `reconfigure`, for
the reason `sequential` is: a decision about one file of one torrent, never a setting.

**The tab.** The glyph B-67 drew as an indicator is the control: a click walks
normal → high → skip, the bolt is the third state, and the tick and the blank mean what they meant
— so a torrent nobody has touched draws exactly as before, and the tab's golden did not move.
Semantics carry what it is and what the next press does; the tests read those and not pixels.

**Persistence, and the item's own mistake.** The decision bullet said "the resume record". B-81
already keeps `unwanted` and `sequential` per torrent in the window's stored list, so the tier went
there — `high=` beside them, written from what the engine last reported *with the click applied*
rather than awaited from the next sample, so a window closed a second after the click still
remembers. The bullet above is corrected in place rather than quietly. The command line, which has
no list, gained `--high <n>`.

**Acceptance, on the lab seeder rather than a public swarm — and said so.** A two-file torrent of
4 MiB each, a libtorrent seeder as the only peer, the CLI with `--high 1 --down 400`, and `du -k`
on both files once a second:

```
t   a.bin  b.bin      (KiB allocated)
 6      0      0
 7      0    768
12      0   2752
15      0   3968
16    256   4096      ← b.bin full; a.bin begins
21   2304   4096
26   4096   4096
```

The raised file filled from the seventh second to the sixteenth with the other at zero, and the
other began the second the raised one was done. That is the whole claim, read off the disk through
the real path — the shipped CLI, a real TCP peer, the real writer — and not inferred from the
picker's unit test. What it is not is a public swarm with many peers, where the rarest-first
half of the rule would visibly reorder pieces *inside* the raised file; that half is pinned by
the mutation below instead.

**Mutation, as the acceptance asked.** Dropping the raised pool from `rarestUnstarted` fails four
picker tests, `aRaisedPieceIsTakenBeforeARarerOrdinaryOne` among them. Making the pool an order —
first raised piece by index — fails exactly one, `withinTheRaisedPoolTheRarestStillWins`, and
nothing else. Both restored by hand, not by `git checkout`, which would have taken the day's work
with them.

**Not done, and not claimed.** Changing a tier from the *add dialog*: it offers wanted/unwanted
only, and the tier is a decision about a running torrent, so the tab is where it is made.
Giving back started pieces when a file drops to skip: they finish, as the bullet said they would.
