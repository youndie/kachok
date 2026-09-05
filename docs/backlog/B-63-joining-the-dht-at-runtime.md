---
id: B-63
title: "Joining the DHT from the settings screen, not from a restart"
status: done
priority: P1
size: S
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-62]
---

# B-63 — Joining the DHT from the settings screen

The DHT toggle did nothing. I had declared it un-editable in
[B-62](B-62-dead-controls-on-two-more-screens.md) — "the socket and the routing table are built with
the session set" — and that was an excuse rather than a reason: it described how `TorrentSet`
happened to be written, not anything that had to be true. Reported by a person on the first screen
they opened.

- **The decision and its reason.** The DHT is built the first time it is asked for, not at
  construction. Joining announces this machine's address to three public routers; that is a decision
  somebody takes, so it is taken when they take it, and it is not something to require a restart to
  change.
- **A running torrent keeps the `Dht` it was opened with**, which for one opened before the toggle
  is null. That is the same rule every setting on the screen follows — it reaches the next torrent —
  and the screen's footnote already says nothing reaches a running session.
- Rejected: rebuilding the set when the toggle flips. It holds every session, the listener and the
  dispatcher; tearing it down to change one socket would stop every download to answer a switch.

## What this leaves visible

Turning it on with a torrent already running shows `DHT 0 nodes` and stays there: the socket is
open, the table is empty, and nothing bootstraps it because bootstrapping is a session's loop and
no session holds this `Dht`. Truthful and unsatisfying. Bootstrapping from the set — so the table
fills whether or not a torrent is using it — is the next item's, not this one's.

- AC: the toggle turns the DHT on and off while the client runs; the status bar changes from
  `DHT off` to a node count, and a torrent added afterwards uses it.
  **Automated:** `ui/src/desktopTest/.../settings/SettingsScreenTest.kt#theDhtCanBeJoinedFromHere`
  and `.../main/WiringTest.kt#everyEditableSettingLeavesTheWindow`. Driven by hand as well: the
  status bar changed on the click.
- Anchors: `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/runtime/TorrentSet.kt`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt`.
