---
id: B-133
title: "Sequential over MCP and the wire, and an agent's changes kept by the window"
status: done
priority: P3
size: M
stage: phase-3-server
blocked_by: []
---

# B-133 — Sequential over MCP and the wire

The engine downloads in order ([B-65](B-65-sequential-download.md)), switches it on a running
torrent ([B-89](B-89-sequential-on-a-running-torrent.md)) and fetches both ends of each file first
([B-121](B-121-sequential-does-not-serve-a-player.md)). The window's add dialog and Files tab reach
it, and so does `kachok download --sequential`. The MCP server did not: `add_torrent` had no such
argument and no tool switched it, so an agent asked for "this season, episodes in order" could only
approximate it with `set_file_priority` — one file `high`, the next `normal`, the rest `skip`, and a
step by hand as each finished. The socket's `Request` had no way to ask either. Reported as
[youndie/kachok#54](https://github.com/youndie/kachok/issues/54).

Found on the way, and taken with it because the item cannot keep its promise without it: **nothing
an agent did through a window's MCP server outlived the window.** `McpServer` put a torrent into the
engine and told nobody, so the window's list (`StoredTorrents`) did not have it on the next start —
and neither the order nor a tier an agent set on one of the window's own torrents was written down.

- **The decision and its reason.** `add_torrent` takes `sequential`, a new `set_sequential` switches
  it both ways through the same `TorrentRuntime.sequential` the Files tab calls, and
  `torrent_status` / `list_torrents` say which order a torrent is in. On the wire, `Request.Sequential`
  and `TorrentState.sequential`, which is also what the MCP snapshot resource carries.
- **Both descriptions say what in order is not.** Pieces go from the start of the torrent to its
  end, both ends of each file first — the order of the files *inside the torrent*, which is often by
  size or name. The torrent that started this issue listed episode 1, then 4, 6, 2, 3, 5, 7, 8, 10, 9.
  An agent that reads "sequential" as "the order the person means" promises something the client
  does not do, so the tool description points at `set_file_priority` for that.
- **`McpKeeper`, and the window's `StoredTorrentsKeeper`.** The server tells a keeper every change —
  add, remove, pause, tier, order — after the engine has been asked, and the window's keeper writes
  the same list its own clicks write. The server does not write the list itself because the list is
  the window's, in the window's format; a headless `kachok mcp` passes nothing, since it holds its
  torrents only while its pipe is open.
- **The alternative that was rejected.** The window sampling the `TorrentSet` and writing down
  whatever it finds. It would catch the add, but not *why* a tier changed or which way the order
  went, and a sampler that decides what to persist is a second place where a torrent's settings
  are decided.
- `McpServerTest` put paths into JSON without escaping them, so on Windows every test that added a
  torrent sent an invalid frame and waited 60 seconds for a reply that could not come. The paths are
  JSON strings now.
- Not covered: a magnet's add on the socket, which the backend still refuses.

- AC: an agent adds a torrent in order, reads the order back, and switches it both ways on a running
  torrent; a surface on the socket does the same; what an agent changes in a running window is in
  the window's list after a restart. **Met.**
  **Automated:** `control/src/test/.../mcp/McpServerTest.kt` —
  `theOrderIsSetOnAddAndSwitchedOnARunningTorrent`, `theKeeperIsToldWhatAnAgentChanged`;
  `cli/src/test/.../serve/BackendTest.kt` — `sequentialSentOverTheSocketReachesTheSessionBothWays`;
  `ui/src/desktopTest/.../session/StoredTorrentsTest.kt` — `whatAnAgentChangesIsInTheListOnTheNextStart`.
- Anchors: `control/src/main/kotlin/io/github/youndie/kachok/control/mcp/McpServer.kt`,
  `control/src/main/kotlin/io/github/youndie/kachok/control/mcp/McpKeeper.kt`,
  `wire/src/commonMain/kotlin/io/github/youndie/kachok/wire/Protocol.kt`,
  `cli/src/main/kotlin/io/github/youndie/kachok/cli/serve/Backend.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/session/StoredTorrents.kt`.
