---
id: B-81
title: "The list of torrents survives a restart"
status: open
priority: P1
size: L
stage: phase-2-ui
epic: feature-ui
blocked_by: []
---

# B-81 — The list of torrents survives a restart

**Where the client keeps its state today, in full:**

| What | Where | Since |
|---|---|---|
| settings | `~/Library/Application Support/kachok/settings.properties`, `%APPDATA%\kachok\`, `$XDG_CONFIG_HOME/kachok/` | [B-71](B-71-settings-that-survive-a-restart.md) |
| which pieces are verified | `<name>.<8 hex of info hash>.resume`, **beside the data** | [B-60](B-60-two-torrents-one-path.md) |
| which torrents there are | **nowhere** | — |

Closing the window loses every torrent. `main` reopens exactly one thing — the path in `argv[0]` —
and `Client` opens nothing else. The resume records are still on the disk and still good; there is
simply nothing that knows they exist, so the work they represent is only recoverable by adding the
same `.torrent` again by hand.

- **The decision this needs.** What a torrent *is*, on disk. A resume record vouches for pieces and
  identifies its torrent by info hash; it does not carry the metainfo, the directory, the unwanted
  files or whether it was paused. Either the client keeps its own list — a file naming each
  torrent's metainfo path, directory and per-torrent choices — or it keeps a *copy* of each
  `.torrent`, which is what most clients do and is the only version that survives the original file
  being moved or deleted.
- Rejected in advance: scanning the download directory for `.resume` files. The record does not say
  where the metainfo is, and a torrent cannot be reopened without it.
- Rejected in advance: storing the list inside the settings file. Settings are a handful of scalars
  that a person edits; this is a growing list the client owns, and one damaged entry must not cost
  the other nine.
- Not covered: the order of the list, and the selection — both cheap once there is a list, and
  neither is worth an item.

**This blocks autostart being worth anything.** An application that starts with the operating system
and comes up with an empty list ([B-83](B-83-autostart-and-its-setting.md)) has started for no
reason.

- AC: a client that is closed with three torrents — one paused, one with a file skipped — comes back
  with the same three, in the same states, without re-verifying anything the records vouch for; a
  list entry whose metainfo has gone says so on the row rather than disappearing.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/session/StoredPreferences.kt`,
  `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/runtime/TorrentRuntime.kt`.
