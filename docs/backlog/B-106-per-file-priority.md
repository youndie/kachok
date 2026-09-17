---
id: B-106
title: "Per-file priority: which file of a torrent the picker fetches first"
status: open
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
- **Persisted in the resume record**, or a restart forgets the one thing the person set by hand.
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
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/resume/ResumeRecord.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/details/DetailsPanel.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/add/AddTorrent.kt`.
