---
id: B-89
title: "Sequential download can be turned on for a torrent that is already running"
status: open
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
- Anchors: [`engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/session/`](../../engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/session),
  [`ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/details/DetailsPanel.kt`](../../ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/details/DetailsPanel.kt).
