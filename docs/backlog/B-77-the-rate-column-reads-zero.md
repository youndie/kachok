---
id: B-77
title: "The rate column reads zero while the torrent is downloading"
status: done
priority: P1
size: S
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-70]
---

# B-77 — The rate column reads zero while the torrent is downloading

Found while checking [B-70](B-70-settings-reach-a-running-session.md) against a live download: with
a 60 KiB/s limit on, the row's *DOWN KIB/S* read `0` and the status bar read `0 KiB/s` while the
progress bar went from 30% to 31% in ten seconds — about 98 KiB/s.

The surface's `RateMeter` is a one-second delta of `SessionState.downloaded`, and `downloaded`
advances only when a **piece** verifies. At 60 KiB/s with 256 KiB pieces a piece lands every four
seconds, so three samples in four are exactly zero and the column flickers `0 0 0 250`.

## The decision, taken — and the one this item pre-rejected

**The rate is the sum of the peers' rates, off the wire.** The engine already measures this
correctly and for its own reasons: every peer carries a `RateMeter` of five one-second buckets,
which the choker uses to decide whom to unchoke. Summing them is the whole of it, and the surface's
own meter class is gone.

**This item rejected exactly that when it was filed**, on the grounds that a rate off the wire counts
a piece that then fails its hash. That reasoning was mine and it was wrong about which of the two
lies is worse. Widening the delta meter's window was tried first and does not work: a five-sample
window over a four-second period holds one piece or two, and the column read `51 102 51 51 102`
instead of a steady 64. There is no window that divides a period nobody knows. Meanwhile a hash
failure costs five seconds of slightly-high rate, and how many pieces failed is its own figure two
rows down in the details panel.

- Not covered: the *Peers* tab's per-peer rates, which are the very numbers this now sums.

- AC: a torrent transferring steadily at a rate below one piece per second shows a rate near that
  figure rather than alternating with zero; a torrent that has genuinely stopped still reads zero
  within a few seconds.
  **Automated:** `ui/src/desktopTest/.../session/RatesOfTest.kt`. Checked by hand: the same 60 KiB/s
  download that read `0` now reads a steady 45 KiB/s on every tick, and the status bar agrees with
  the row.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/session/SessionRow.kt`,
  `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/choke/RateMeter.kt`.
