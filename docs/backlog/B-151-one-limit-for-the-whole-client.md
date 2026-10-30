---
id: B-151
title: "One download and upload limit for the whole client, and a cap on downloads at once with the rest queued"
status: done
priority: P2
size: M
stage: m3-storage
epic: feature-download
blocked_by: []
---

# B-151 — One limit for the whole client

Asked for by the person using the client: eleven films to queue up at once without the downloads
taking the whole link — "a common limit, say 10 Mbit/s, that changes and shows through any
interface, and how many torrents download at once, with a queued state". Until this the only limits
were each torrent's own, set from the window's settings and applied to every torrent separately,
so eleven torrents at a 10 Mbit/s limit could take 110; the MCP engine had no limit at all.

- **The decision and its reason: one budget for the set.** `TorrentSet` owns a `SharedRateLimit` for
  each direction and hands both to every session it opens. A session spends from its own
  `TokenBucket` and the shared one together (`BothBudgets`): a byte goes out only when both have it,
  and when the shared one refuses, the session's own tokens are given back. The shared one is
  locked, since every session spends from its own thread, and refills from the clock on each
  question — a refill per session tick would multiply the rate by the number of sessions. Depth is
  one second of the rate, like the session's own.
- **The queue.** `SetLimits.maxActiveDownloads` caps torrents that are downloading — started, not
  paused, not complete. The set's loop, twice a second and on every change, holds the newest over
  the cap and releases the oldest waiting when there is room, in the order torrents were added. A
  held torrent gets `Command.Queue(held = true)`: the session pauses as it would for a person, but
  publishes `queued`, not `paused`. A person's pause wins (pausing a queued torrent makes it paused),
  a resume does not jump the queue, and a torrent that has to wait is started held
  (`TorrentRuntime.holdOnStart`), so it never announces or dials first. Seeding never waits.
- **Every surface.** MCP: `set_limits` takes `download`/`upload` as text — `10 Mbit/s`, `1.5 MB/s`,
  `800 KiB/s`, `off`; lowercase `b` is a bit and uppercase `B` a byte — and
  `max_active_downloads`; a field left out is kept. `network_status` says the limits in bits and
  bytes and the queue as running/queued; `list_torrents` and `torrent_status` say `queued`. The wire:
  `Snapshot` carries the three limits and `TorrentState.queued`, `Request.SetLimits` changes them. The
  window: the settings' upload and download limits are now the whole client's (KiB/s, as before),
  *Downloads at once* is new, a waiting row is *Queued* (drawn as a paused one), and the status bar
  says `1.14 MiB/s of 1.19 MiB/s` and `2 queued, 1 at once` when there is a limit. Changes from any
  of them reach the others: the window's screen follows `TorrentSet.limits`.
- **Kept between runs, in one file.** `limits.properties` beside the remembered torrents, read by
  whichever process owns the engine — the window or the CLI's MCP engine (B-81) — and written on
  every change. The window takes its old settings' figures only when the file does not exist yet.
- **What changed meaning.** The window's limit settings were per torrent and are now the client's; a
  torrent opened by the window no longer carries a limit of its own.

- Not covered, and worth doing next: a global cap on connections (each torrent may keep 250, which
  across a dozen torrents is a lot of state for a home router); limits on a schedule; a separate cap
  on torrents seeding at once. `kachok serve` does not persist limits a browser sets.
- The window's goldens include the settings screen, which has a new row: `:ui:viddikRecord` on a Mac.

- AC: one download limit across every torrent, changed while they run. **Met.** **Automated:**
  `engine/src/jvmTest/.../runtime/SharedRateLimitTest.kt`,
  `engine/src/commonTest/.../session/SessionTest.kt` — `theClientsDownloadLimitIsSpentBeforeAnythingIsAsked`.
- AC: no more downloads at once than allowed; the rest queued in order; queued is not paused.
  **Met.** **Automated:** `engine/src/jvmTest/.../runtime/TorrentSetQueueTest.kt`,
  `SessionTest` — `aHeldTorrentSaysQueuedAndItsTurnResumesIt`,
  `pausingAHeldTorrentMakesItPausedAndNotQueued`.
- AC: set and read through MCP in the units a person uses, and kept between runs. **Met.**
  **Automated:** `control/src/test/.../mcp/McpServerTest.kt` — `theClientsLimitsAreSetAndReadBack`,
  `control/src/test/.../RatesTest.kt`, `control/src/test/.../store/StoredLimitsTest.kt`.
- AC: the window shows the limits and the queue. **Met.** **Automated:**
  `ui/src/desktopTest/.../session/WindowFromTest.kt` — `theStatusLineSaysTheClientsLimitsWhenThereAreAny`,
  `SessionRowTest` — `aHeldTorrentIsQueued`, `SettingsScreenTest` — `whatIsTypedReachesTheNextTorrentsOptions`.
- Anchors: `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/runtime/TorrentSet.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/runtime/SharedRateLimit.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/choke/RateBudget.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`,
  `control/src/main/kotlin/io/github/youndie/kachok/control/Rates.kt`,
  `control/src/main/kotlin/io/github/youndie/kachok/control/store/StoredLimits.kt`,
  `control/src/main/kotlin/io/github/youndie/kachok/control/mcp/McpServer.kt`,
  `wire/src/commonMain/kotlin/io/github/youndie/kachok/wire/Protocol.kt`,
  `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/session/SettingsFrom.kt`.
