---
id: B-69
title: "A status per tracker, and re-announcing by hand"
status: done
priority: P2
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-54]
---

# B-69 — A status per tracker

There is one `trackerError` for the whole session. A torrent announces to every tracker in its list
and the design's *Trackers* tab shows what each one said, with a *Re-announce* button over them.

- **The decision this needs.** What a tracker's own state is: last announce, next announce, the
  peers it returned, and its complaint in its own words. The engine keeps none of it per tracker,
  and the design's own note is the reason to — *one tracker failing is not a failed torrent*.
- Rejected in advance: keeping only the last error, which is what `trackerError` is and why the
  screen exists.
- Not covered: adding or removing a tracker, which changes the torrent rather than the view of it.

## The decision, taken

**A report per announce URL, kept as attempts and rendered as three statuses.** *Working*, *failed*
with the tracker's own sentence, and *not tried* — which is what most of a torrent's trackers are,
because BEP 12 has a client stop at the first one that answers. Hiding the untried ones would make a
three-tracker torrent look like a one-tracker torrent.

**Cards, not table rows.** A refusal is somebody else's sentence and does not fit a column.

**The list is the metainfo's order and never the order things were tried**: a row that moved when a
tracker failed is a list nobody can read twice.

**`Command.Announce` does not reset the interval.** The loop's schedule is what the tracker asked
for; a person pressing *Re-announce* is overriding it once, not renegotiating it.

## Deviations, and why

- **The reference draws all three trackers `working`, with peers and a countdown each.** This engine
  cannot be in that state: BEP 12's multitracker rule means one announce reaches one tracker. The
  tab reports what happened — one failure, one success, one untouched — rather than three rows
  pretending to be in use.
- **The DHT card's times were added to the engine for it.** The routing table's size was already
  published; when the last pass announced was not, so the card would have read `214 nodes` where the
  reference reads `214 nodes · announced 6 m ago · next in 9 m`. They are absent until the first
  pass has announced, because a table with nodes in it has not necessarily said anything yet.
- **`plannedBecause` and the placeholder it fed are gone.** All four tabs draw the session now; what
  replaced the mechanism is a per-tab test that the words are on the screen.

- AC: the *Trackers* tab lists each announce URL with its status, its interval and its last words;
  *Re-announce* asks every tracker again and the row shows the result.
  **Automated:** `engine/src/commonTest/.../session/SessionTest.kt` — `everyTrackerGetsItsOwnStatus`,
  `aTrackerThatRefusesIsRecordedInItsOwnWords`, `announcingByHandAsksTheTrackerAgain`;
  `ui/src/desktopTest/.../details/TrackersTabTest.kt` and `.../session/DetailsFromTest.kt`; and the
  golden `details_planned-tabs.png` compared against `docs/design/screens/details-tabs.png`.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/tracker/`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/SessionState.kt`.
