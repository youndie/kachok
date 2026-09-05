---
id: B-52
title: "The UI on the real engine, not on a fixture"
status: done
priority: P1
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-48]
---

# B-52 — The UI on the real engine, not on a fixture

Every screen before this one is drawn from a fixture, which is what makes goldens possible. This is
the item that connects them to `Session` and finds out what the design assumed and the engine does
not do.

- **The decision and its reason.** The CLI's factory, lifted into the engine's `jvmMain` as
  `TorrentRuntime`, so both surfaces build the engine the same way; one `StateFlow` per torrent into
  the list, commands out through the one channel. A window that runs a real download.
- Rejected: a second wiring for the UI. Two factories mean two clients, and the second one is
  always the one that is wrong. `Download.kt` kept only what makes it a *command* — the rendering,
  the exit codes, the shutdown hook.
- **A test-only `:swarm` module.** The end-to-end needs a tracker that names a peer and a peer that
  serves the bytes, and there are now two surfaces that need the same one. A fake BitTorrent seed
  written twice is two fakes that disagree about the protocol in different places, so `SeedingPeer`
  moved out of `:cli`'s tests and `LocalSwarm` joined it. Nothing publishes it.
- Not covered: multiple torrents in one process — the engine is one `Session` per torrent today,
  and the list is built for many. That gap is this item's first finding, not a surprise.

## What the design assumed and the engine does not do

Three of them, all now named in code rather than papered over:

- **There is no rate.** `SessionState` carries `downloaded` and `uploaded`, which only go up, and
  nothing per-second — which is exactly why the design marks *speed down / up* `planned` in the
  details panel. But the *row* has DOWN KIB/S and UP KIB/S with real numbers in it, so the rate is
  the surface's own arithmetic: `RateMeter` divides two samples by the time between them, against
  an injected `TimeSource` rather than the wall clock.
- **There is no paused torrent.** The engine has `Command.Stop` and no paused state, and the design
  says so on the row itself. `PAUSED_IS_PLANNED` asserts that nothing the engine can report maps to
  *Paused*, so the day `Command.Pause` exists the test fails and somebody comes back here.
- **There is no stopping torrent either, and the design does not say so.** A stop is a command with
  no state to observe it by. `Lifecycle` is the surface's own: *Fetching* before a magnet's
  metadata arrives, *Stopping* between `Command.Stop` and the job ending — which is what keeps the
  window up for the design's ten seconds of announce, close, flush, record when it is closed.

Two smaller findings: a tracker's refusal is **not** a degraded session (`trackerError` is set,
`sessionError` is not, and the banner stays away — asserted, because "any error is the error row"
is the obvious wrong simplification); and `DHT off` is a different answer from `DHT 0 nodes`, so
the status bar takes a nullable count rather than an int.

- AC: the desktop app downloads the fixture torrent from a local swarm and the list shows it
  progressing; every field the design marked `planned` is still marked in the running app.
  **Automated:** `ui/src/desktopTest/.../session/AppDownloadTest.kt` — a real download from
  `:swarm`, sampled the way the window samples it, asserting the row is seen *downloading* at a
  percentage between nothing and all before it is seen *seeding* — plus
  `.../session/SessionRowTest.kt` and `.../session/FiguresTest.kt`.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt`,
  `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/runtime/TorrentRuntime.kt`,
  `swarm/src/main/kotlin/ru/workinprogress/kachok/swarm/`.
