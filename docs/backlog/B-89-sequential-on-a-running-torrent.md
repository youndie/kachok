---
id: B-89
title: "Sequential download can be turned on for a torrent that is already running"
status: done
priority: P3
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: []
---

# B-89 — Sequential download can be turned on for a torrent that is already running

Sequential order is decided in the add dialog and never again. The tick is not on the details panel,
and `RuntimeOptions.sequential` is read when the session is built — so somebody who starts a film
and then wants to watch the front of it has to remove the torrent and add it again, which throws
away everything already fetched unless they get the folder exactly right.

That is the moment the feature exists for. A person turns sequential *on* because they have started
watching, and they start watching after the download has started.

- **The decision this needs.** What happens to the pieces already in flight. The picker hands out
  rarest-first now; switching order mid-download either lets the outstanding requests finish and
  changes only what is chosen next — cheap, and the order is briefly a mixture — or cancels them,
  which is a `Cancel` per peer per block and a swarm that has just been asked for work twice.
- **And whether it may be turned off again.** Off is the easy direction: nothing in flight has to be
  reconsidered. Allowing only on-then-off-per-session would still cover the case above.
- Rejected in advance: rebuilding the session. It is what removing and re-adding does today, and it
  re-verifies every piece on the disk to find out what it already has.
- Not covered: per-file priority, which is the same machinery and a much larger screen —
  [B-67](B-67-per-file-selection.md)'s own not-covered half.

**The same shape of gap, one item along:** the file ticks in the details panel are indicators rather
than controls for exactly this reason, and changing *them* on a running torrent needs the picker to
give back pieces it has started. Whatever answers that question here answers it there.

- AC: a running torrent's order can be changed from the details panel and the next pieces asked for
  follow the new order; nothing already verified is re-fetched or re-hashed; the setting survives a
  restart.

## Done

**What is already asked for is left alone**, which was the item's first open decision. Cancelling
the outstanding requests is a `Cancel` per peer per block and a swarm asked for the same work twice;
letting them land costs one pipeline's worth of pieces in the old order — a second or two of mixture
at the front, and then the order somebody asked for. Nothing already verified is touched either way,
which is the half of the acceptance criterion that made removing and re-adding the torrent such a
bad answer.

**And it can be turned off again**, the item's second decision. Off is the easy direction — nothing
in flight has to be reconsidered — and somebody who has finished watching should go back to being a
good swarm member without removing anything.

**`PiecePicker.sequential` is a `var`, and that is the whole change in the engine.** It was a `val`
because the order was a decision taken when the session was built — which is a decision that can
only be taken *before* the one event that makes anybody want it.

**The control is on the *Files* tab, drawn the way *Re-announce* is drawn on *Trackers*.** That is
the panel's own vocabulary for a pressable thing in a tab head, and reusing it is what tells this
apart from the file ticks two rows below, which report what the add dialog decided and are not
controls at all. **Its label says what pressing it will do** — *Ask in order*, *Ask rarest first* —
because a two-state control that reads as a link and names its current state is one nobody can tell
from a label; what it *is* is in the semantics, where a test and a screen reader both read it.

**The state comes from the session, not from the last click.** A control drawing what it asked for
would disagree with the client the first time a command was lost or refused, and `SessionState`
carries the flag from its very first value rather than from the first restore — otherwise a large
torrent shows the wrong order for as long as its disk check takes.

### The seam that survived being deleted

Deleting the `Command.Reconfigure` branch that applies this changed **no test**: the picker's own
test knew the picker could be switched, the panel's test knew the control asked, and between them
sat the code that connects the two with nothing exercising it. `SessionTest` now sends the command
to a running session and reads the state back — and asserts the other direction too, that a
reconfigure carrying only a rate limit does not take one torrent's order away with it, which is what
the settings screen sends on every keystroke.

**Automated:** `engine/src/commonTest/.../session/SessionTest.kt` — the order changes on a running
session, changes back, and survives an unrelated reconfigure ·
`engine/src/commonTest/.../picker/PiecePickerTest.kt` — the picker's next choice follows and nothing
already verified is asked for again ·
`ui/src/desktopTest/.../details/FilesTabTest.kt` — the control asks and reads its state ·
`ui/src/desktopTest/.../session/StoredTorrentsTest.kt` — it is written down without disturbing the
pause, the folder or the file selection, and writes nothing for a torrent this client does not have.
- Anchors: [`engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/`](../../engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session),
  [`ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/details/DetailsPanel.kt`](../../ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/details/DetailsPanel.kt).
