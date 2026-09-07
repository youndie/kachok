---
id: B-54
title: "More than one torrent in one process"
status: done
priority: P1
size: L
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-52]
---

# B-54 — More than one torrent in one process

The engine was one `Session` per torrent with nothing above it holding several, so every phase-2
screen was built for many and given one. Found closing
[B-52](B-52-ui-on-the-real-engine.md), where it was named as that item's first finding rather than
a surprise.

- **The decision and its reason.** `TorrentSet` owns the three things that cannot be had twice —
  the listener, the DHT and the dispatcher — and `TorrentRuntime` gave them up. Even the headless
  client goes through a set of one, so there is one door and not two.
- **The listener reads before it routes.** There is one port, it is announced to every tracker, and
  an incoming peer names the torrent it wants in *its* handshake. `SocketPeerConnection` grew a
  two-step accept — `readHandshake` then `answer` — because answering first would mean guessing.
  A peer asking for a torrent this process does not have gets a closed socket rather than a
  handshake claiming one.
- **The buffer pool stays one per torrent, which is the sharing this deliberately does not do.**
  The cap *is* the back-pressure, and back-pressure that is global lets a fast torrent's peers take
  the buffers a slow torrent's writer is waiting for. Direct buffers are allocated lazily and never
  freed, so N pools cost what N torrents actually use rather than the sum of their caps.
- Rejected: a `Session` that holds many torrents. Its confinement — one writer, one timer,
  `limitedParallelism(1)` — is per session and is what makes its state race-free; making it
  multi-tenant would relitigate every one of those.
- **The same torrent twice is refused**, by info hash, where it is cheap to refuse: two sessions on
  one hash would announce twice, dial the same peers twice and write the same pieces into two
  directories.
- Not covered: persistence of the list across restarts. Also not covered: what sixteen torrents
  cost, which the measurement below is linear in only if the peers are.

## The measurement

Two 4 MiB torrents in 256 KiB pieces, downloading at once from two local swarms in one process:

| | peak outstanding | cap |
|---|---|---|
| torrent A | 18 | 178 |
| torrent B | 18 | 178 |

36 buffers, 576 KiB of direct memory at peak against a combined cap of 356. Written into the
research as §1.2c2, with the consequence — that the pool stays per session — beside it.

The fixture had to grow to produce a number at all: with the one-block pieces the earlier swarms
used, a pool hands out eight buffers however large the torrent is, and a measurement of that is a
measurement of the fixture. `LocalSwarm` takes a piece length now.

- AC: two torrents download at once in one window; the status bar's counts and the buffer pool's
  peak are both measured with two running and written into the research.
  **Automated:** `ui/src/desktopTest/.../session/ManyTorrentsTest.kt` — two swarms, one set, both
  finishing, `2 torrents, 2 seeding, 0 paused` on the status bar, and the peaks printed by the run
  that produced the table above.
- Anchors: `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/runtime/TorrentSet.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/io/SocketPeerConnection.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/App.kt`.
