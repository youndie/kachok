---
id: B-70
title: "Settings that reach a running session"
status: open
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

- AC: changing a rate limit changes what a running torrent transfers, without stopping it; the
  footnote loses its badge for the fields that made it true and keeps it for the rest.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/session/Session.kt`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/session/SettingsFrom.kt`.
