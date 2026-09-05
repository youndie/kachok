---
id: B-55
title: "Magnets in the window, not only on the command line"
status: done
priority: P2
size: S
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-54]
---

# B-55 — Magnets in the window, not only on the command line

The add dialog recognised a magnet and then greyed *Add*, because the window had no
`MetadataFetcher` in front of a session. Found closing [B-50](B-50-add-torrent.md), where a greyed
button with a reason on it was the honest placeholder.

- **The decision and its reason.** The fetch is a row, not a modal wait. A magnet said yes to
  becomes the design's *Metadata* state — the info hash where the name will be, an indeterminate
  bar, no size, ratio or ETA — and turns into a real torrent when `fetchMetainfo` returns. A
  window that showed nothing for the length of a swarm's answer would look broken rather than busy,
  and the state the design draws for exactly this had until now nothing that produced it.
- **`fetchMetainfo` moved out of the headless client** into the engine's `jvmMain`, the same
  argument as `TorrentSet`: two copies of a wiring are two clients, of which the second is always
  the one that is wrong. Deliberately *not* a method on the set — a fetch has no session, no files
  and no port of its own, and a set that held half-torrents would be a set with two kinds of member.
- **The fetched torrent goes through the same door a file goes through**, the channel into
  `TorrentSet.add`, so there is one place a session is opened.
- Rejected: opening a session with a placeholder metainfo and swapping it. Every piece count, file
  layout and resume record is derived from the metainfo, and a session that has held the wrong one
  has written the wrong things.
- **A fetch nobody answers removes the row rather than marking a session broken.** There is no
  session to mark: a magnet that no peer will explain is not a torrent yet.
- Not covered: a magnet with no trackers, which needs the DHT on — the toggle exists and the
  session honours it, but nothing in the window turns it on yet.

## What this needed below the window

`SeedingPeer` learned BEP 9. It answers the extension handshake with `ut_metadata` and its own id
for it — deliberately not the client's, because `m` is a per-peer mapping and a fetcher that
assumed both ends used the same number would work against itself and nothing else — and serves the
`info` dictionary in blocks. Without it there was no end-to-end magnet test anywhere: the engine's
own fetcher test drives an in-process `PeerConnection`, which proves the fetcher agrees with a stub.

- AC: a magnet pasted into the window becomes a *Metadata* row, then a downloading one, against a
  local swarm; the row shows the info hash where the name would be until the metainfo arrives.
  **Automated:** `ui MagnetTest#aMagnetBecomesATorrentAndDownloadsIt` — the metadata fetched over a
  socket, hashed against the hash the magnet named, then the whole file downloaded and compared
  byte for byte — plus `#aMagnetIsARowBeforeItIsATorrent` and
  `#aMagnetWithNoNameShowsItsHashWhereTheNameGoes`.
- Anchors: `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/runtime/MagnetFetch.kt`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt`,
  `swarm/src/main/kotlin/ru/workinprogress/kachok/swarm/SeedingPeer.kt`.
