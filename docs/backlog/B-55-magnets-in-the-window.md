---
id: B-55
title: "Magnets in the window, not only on the command line"
status: open
priority: P2
size: S
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-54]
---

# B-55 — Magnets in the window, not only on the command line

The add dialog recognises a magnet and shows everything it carries — the display name, the hash,
and the note that the metainfo comes from the swarm first — and then greys *Add*, because the
window has no `MetadataFetcher` in front of a session. The headless client has one: `Download.kt`
fetches the torrent and only then opens anything.

Found closing [B-50](B-50-add-torrent.md), where a greyed button with a reason on it was the
honest placeholder.

- **The decision this needs.** Where the fetch lives. The CLI does it before `TorrentSet.add`,
  which works because it has one torrent and can block. A window has a list, so the fetch is a row
  in *metadata* state that becomes a real torrent when it returns — which is exactly the state
  `Lifecycle.Fetching` and the design's *Metadata* row already draw and nothing yet produces.
- Rejected in advance: opening a session with a placeholder metainfo and swapping it. Every piece
  count, file layout and resume record is derived from the metainfo, and a session that has held
  the wrong one has written the wrong things.
- Not covered: a magnet with no trackers, which needs the DHT on and is a different sentence to
  show in the same dialog.

- AC: a magnet pasted into the window becomes a *Metadata* row, then a downloading one, against a
  local swarm; the row shows the info hash where the name would be until the metainfo arrives.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt`,
  `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/metainfo/MetadataFetcher.kt`,
  `cli/src/main/kotlin/ru/workinprogress/kachok/cli/Download.kt`.
