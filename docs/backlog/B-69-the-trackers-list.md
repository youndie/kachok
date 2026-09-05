---
id: B-69
title: "A status per tracker, and re-announcing by hand"
status: open
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

- AC: the *Trackers* tab lists each announce URL with its status, its interval and its last words;
  *Re-announce* asks every tracker again and the row shows the result.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/tracker/`,
  `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/session/SessionState.kt`.
