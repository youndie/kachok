---
id: B-70
title: "Settings that reach a running session"
status: done
priority: P2
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-62]
---

# B-70 — Settings that reach a running session

The settings screen's own footnote says *changes apply to the running session immediately — no
restart, no Apply button*, with the design's `planned` badge on it. They do not: an edit reaches the
**next** torrent through `RuntimeOptions` and nothing reaches one that is already running.

- **The decision this needs.** Which of `SessionConfig`'s fields a running session can be told to
  change. The rate limits are a `TokenBucket` and could take a new rate between ticks; `maxPeers`
  and `pipelineDepth` change how much is in flight and would have to be applied where the picker
  reads them; the listening port cannot change without re-announcing every torrent, which is why it
  is the one field the screen already refuses.
- Rejected in advance: rebuilding the session. Every counter, the bitfield and the resume record
  live in it, and restarting a download to change a number is what "no Apply button" was written
  against.
- Not covered: writing the settings anywhere. They are held for the session and lost on exit, which
  is [B-71](B-71-settings-that-survive-a-restart.md).

## The decision, taken

**Three fields, named.** Both rate limits — token buckets, which take a rate between ticks — and how
many peers to keep up, which is read where the next dial is decided. `Command.Reconfigure` carries
nulls for what it is not changing.

**Two are deliberately absent, and each says so on its own row.** The listening port cannot change
without re-announcing every torrent under a new address. `pipelineDepth` is the buffer pool's
working set, sized when the pool was built, and a session cannot grow the pool it was handed.

**`SettingKey` grew a third state.** `disabledBecause` makes a row read-only, which is wrong for a
number that *can* be set — it is the next torrent that gets it. `nextTorrentBecause` says so beside
the field without locking anybody out. Saying nothing at all is what left the footnote claiming
every setting applied immediately while two did not; the footnote now names the three it is true of
and has lost its badge.

**`TokenBucket.retune` clamps rather than resets.** Raising a limit must not hand out a second's
worth of the old rate on top of what is already there; lowering one must not leave a bucket holding
more than its new depth.

## What the test found

**Lifting a limit left the session at a standstill.** Requests are issued when a block arrives, no
block arrives while nothing is asked for, and `refillRateLimits` — which exists to break exactly
that circle — returns immediately once there is no limit left to refill. A session throttled to a
stop and then unthrottled stayed stopped. `Reconfigure` now asks every peer for more, as well as
dialling.

- AC: changing a rate limit changes what a running torrent transfers, without stopping it; the
  footnote loses its badge for the fields that made it true and keeps it for the rest.
  **Automated:** `engine/src/commonTest/.../session/SessionTest.kt` —
  `raisingThePeerCountDialsMorePeersWithoutARestart`, `aRateLimitCanBeChangedAndLifted`;
  `ui/src/desktopTest/.../session/SettingsFromTest.kt` for the footnote and the third state.
  Checked by hand against a live download: capped at 300 KiB/s it ran at 254, lifting the cap took
  it to 509, and typing 60 into the field brought it back to about 100 KiB/s within a few seconds —
  all without the torrent stopping.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/session/SettingsFrom.kt`.
